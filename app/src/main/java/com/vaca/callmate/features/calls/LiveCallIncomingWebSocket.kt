package com.vaca.callmate.features.calls

import android.content.Context
import android.util.Log
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.CallMateBleEvent
import com.vaca.callmate.core.ble.INCOMING_AI_CHAIN_TAG
import com.vaca.callmate.core.network.BackendAuthManager
import com.vaca.callmate.data.AbnormalCallRecordStore
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.IncomingCall
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.ProcessStrategyStore
import com.vaca.callmate.data.repository.OutboundRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.text.Charsets
import com.vaca.callmate.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val WS_URL get() = BuildConfig.WS_BASE_URL
private const val WS_CLIENT_ID = "CallMate-iOS"
private const val PROTOCOL_VERSION = "1"
/**
 * Pre-gate buffer cap: TTS frames buffered before MCU reports `audio_streaming`.
 * 10 frames × 60ms = 600ms — enough to preserve the AI greeting's opening
 * without accumulating seconds of stale audio that would flood the MCU queue
 * (48 slots / 2.88s) and create latency for the caller.
 *
 * Was 100 (6s), which caused massive bursts on flush, overwhelming the MCU.
 */
private const val MAX_PENDING_TTS_FRAMES = 10

/**
 * 与 iOS `CallSessionController.startFromIncomingCall` + `WebSocketService` + BLE 音频桥接对齐：
 * - `hello` 后 `listen` realtime（来电走 MCU 采集，不经手机麦）
 * - WS 二进制帧 → **先** [BleManager.sendUplinkOpus]（低延迟到 MCU），会话录音异步，避免在 OkHttp 读线程同步解码挡后续包
 * - [CallMateBleEvent.AudioDownlinkOpus] / mSBC → WS 二进制上行至云端
 * - 会话侧事件经 [LiveCallSessionRegistry] 投递，不持有 [CallSessionViewModel]
 */
class LiveCallIncomingWebSocket(
    private val context: Context,
    private val bleManager: BleManager,
    private val preferences: AppPreferences,
    private val language: Language,
    private val scope: CoroutineScope,
    private val wsBluetoothId: String = UUID.randomUUID().toString()
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var helloRetryJob: Job? = null
    private var bleBridgeJob: Job? = null
    private var helloAcked = false
    /** 与 iOS `wsListeningStarted` 对齐：收到 `hello` ack 且已发 `listen start` */
    private var wsListeningStarted = false
    private var wsSessionId: String? = null
    @Volatile
    private var bridgeActive = false
    private val pendingBleDownlink = ArrayDeque<ByteArray>()
    private var bleDownlinkToWsCount = 0
    private var wsBinaryToBleCount = 0

    /**
     * Gate: MCU drops uplink audio before SCO connects (`protocol_handle_audio_data`
     * `!env->sco_connected`).  Buffer WS TTS frames here until [BleManager.isIncomingAiAudioStartAcked]
     * (== MCU `call_state(audio_streaming)` received) so no frames are wasted.
     */
    private val pendingTtsFrames = ArrayDeque<ByteArray>()
    private var ttsGateOpen = false
    /** 当前 WS 会话对应的 BLE uid（日志用） */
    private var activeBleUid: Int = 0
    /** [connectInner] 传入，供 [handleTextMessage] 写入 [LiveCallWsSessionHolder]（含 bleUid=0） */
    private var activeIncomingCall: IncomingCall? = null
    /** 与 [SimulationCallController] `sentence_end` ✿END✿ 标记一致 */
    private var ttsPendingAiHangup = false
    /**
     * OkHttp 会先 [WebSocketListener.onClosing] 再 [WebSocketListener.onClosed]；部分机型/网络下
     * `onClosed` 严重滞后或长期不到，若仅在 `onClosed` 里 [notifyVmAfterWsTerminated]，Live 会话无法结束。
     * 在 **onClosing 首行** 读取 [helloAcked]（早于 [stop] 清空），与 `onClosed`/`onFailure` 幂等合并。
     */
    private val wsTerminateDispatched = AtomicBoolean(false)

    fun sendAbort(reason: String = "user_interrupt") {
        val sid = wsSessionId ?: return
        try {
            socket?.send(
                JSONObject()
                    .put("session_id", sid)
                    .put("type", "abort")
                    .put("reason", reason)
                    .toString()
            )
        } catch (_: Exception) {
        }
    }

    fun stop(reason: String = "stop") {
        Log.i(INCOMING_AI_CHAIN_TAG, "live ws stop reason=$reason")
        bridgeActive = false
        bleBridgeJob?.cancel()
        bleBridgeJob = null
        helloRetryJob?.cancel()
        helloRetryJob = null
        sendListenStopIfNeeded()
        pendingBleDownlink.clear()
        pendingTtsFrames.clear()
        ttsGateOpen = false
        try {
            socket?.close(1000, reason)
        } catch (_: Exception) { }
        socket = null
        helloAcked = false
        wsListeningStarted = false
        wsSessionId = null
        activeIncomingCall = null
    }

    fun connectForIncomingCall(call: IncomingCall) {
        scope.launch(Dispatchers.IO) {
            connectInner(call, helloPromptOverride = null)
        }
    }

    /**
     * 与 iOS `handleBLECallStateOutgoingAnswered` + `setCallHelloPromptOverride` 对齐：
     * 在 `call_state(outgoing_answered)` 后建 WS，`hello.initiate.prompt` 为外呼任务模板，非代接词。
     */
    fun connectForOutboundAnswered(call: IncomingCall, promptOverride: String) {
        scope.launch(Dispatchers.IO) {
            connectInner(call, helloPromptOverride = promptOverride)
        }
    }

    private suspend fun connectInner(call: IncomingCall, helloPromptOverride: String?) {
        bleBridgeJob?.cancel()
        bleBridgeJob = null
        bridgeActive = false
        pendingBleDownlink.clear()
        wsListeningStarted = false
        wsSessionId = null
        helloAcked = false
        bleDownlinkToWsCount = 0
        wsBinaryToBleCount = 0
        pendingTtsFrames.clear()
        ttsGateOpen = false
        ttsPendingAiHangup = false
        wsTerminateDispatched.set(false)
        activeBleUid = call.bleUid
        activeIncomingCall = call
        val deviceId = bleManager.runtimeMCUDeviceID.value?.trim().orEmpty()
        val connectedAddr = bleManager.connectedAddress.value
        val connected = !connectedAddr.isNullOrBlank()
        Log.i(
            INCOMING_AI_CHAIN_TAG,
            "live ws connectInner start uid=${call.bleUid} bleConnected=$connected deviceIdLen=${deviceId.length}"
        )
        if (!connected || deviceId.isEmpty()) {
            Log.w(INCOMING_AI_CHAIN_TAG, "live ws abort: device_id_not_synced uid=${call.bleUid}")
            AbnormalCallRecordStore.getInstance(context).append(
                "device_id_not_synced",
                if (language == Language.Zh) "设备未连接或 device-id 未同步" else "MCU not connected or device-id unavailable"
            )
            activeIncomingCall = null
            return
        }
        val token = BackendAuthManager.ensureToken(preferences)
        if (token == null || !BackendAuthManager.looksLikeJWT(token)) {
            Log.w(INCOMING_AI_CHAIN_TAG, "live ws abort: no valid token uid=${call.bleUid}")
            AbnormalCallRecordStore.getInstance(context).append(
                "websocket_connect_failed",
                if (language == Language.Zh) "缺少登录凭证" else "Token missing; cannot connect WebSocket."
            )
            activeIncomingCall = null
            return
        }
        BackendAuthManager.reportDevice(preferences, deviceId, wsBluetoothId, token)
        val prompt: String
        val systemCallType: String
        if (helloPromptOverride != null) {
            val t = helloPromptOverride.trim()
            if (t.isEmpty()) {
                Log.w(INCOMING_AI_CHAIN_TAG, "live ws abort: outbound prompt empty uid=${call.bleUid}")
                AbnormalCallRecordStore.getInstance(context).append(
                    "websocket_connect_failed",
                    if (language == Language.Zh) "外呼提示词为空" else "Outbound prompt is empty."
                )
                activeIncomingCall = null
                return
            }
            prompt = t
            systemCallType = "outbound"
        } else {
            val p = loadDaijiePrompt()
            if (p.isNullOrBlank()) {
                Log.w(INCOMING_AI_CHAIN_TAG, "live ws abort: daijie prompt missing uid=${call.bleUid}")
                AbnormalCallRecordStore.getInstance(context).append(
                    "websocket_connect_failed",
                    if (language == Language.Zh) "缺少代接提示词资源" else "Missing daijie prompt asset."
                )
                activeIncomingCall = null
                return
            }
            prompt = p
            systemCallType = "inbound"
        }
        val processStrategyJson = ProcessStrategyStore.processStrategyJSONString(context)
        val appellation = preferences.userAppellationFlow.first().trim().takeIf { it.isNotEmpty() }
        val phoneIdHeader = sha256Hex(call.number.ifBlank { "incoming" })

        val req = Request.Builder()
            .url(WS_URL)
            .header("Device-Id", deviceId)
            .header("Client-Id", WS_CLIENT_ID)
            .header("Protocol-Version", PROTOCOL_VERSION)
            .header("phone_id", phoneIdHeader)
            .header("Authorization", "Bearer $token")
            .build()

        Log.i(INCOMING_AI_CHAIN_TAG, "live ws newWebSocket uid=${call.bleUid} url=$WS_URL")
        // 勿用 Main：与 UI/收尾磁盘争用会拖慢 BLE→WS 上行（云端 ASR）
        bleBridgeJob = scope.launch(Dispatchers.IO) {
            bridgeActive = true
            try {
                bleManager.bleEvents.collect { ev ->
                    if (!bridgeActive) return@collect
                    when (ev) {
                        is CallMateBleEvent.AudioDownlinkOpus ->
                            forwardBleDownlinkToWs(ev.payload)
                        is CallMateBleEvent.AudioDownlinkMsbcPayload57 ->
                            forwardBleDownlinkToWs(ev.payload)
                        else -> Unit
                    }
                }
            } catch (_: Exception) {
            }
        }
        withContext(Dispatchers.Main) {
            socket = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(INCOMING_AI_CHAIN_TAG, "live ws onOpen uid=${call.bleUid} code=${response.code}")
                    scope.launch(Dispatchers.Main) {
                        scheduleHelloRetries(prompt, appellation, processStrategyJson, call, systemCallType)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch(Dispatchers.Main) { handleTextMessage(text) }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val arr = bytes.toByteArray()
                    if (arr.isEmpty()) return
                    wsBinaryToBleCount++
                    if (wsBinaryToBleCount == 1) {
                        Log.i(
                            INCOMING_AI_CHAIN_TAG,
                            "live ws first WS TTS binary rx bytes=${arr.size}"
                        )
                    }
                    enqueueOrSendWsTtsToBle(arr)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    val savedHello = helloAcked
                    Log.i(
                        INCOMING_AI_CHAIN_TAG,
                        "live ws onClosing uid=${call.bleUid} code=$code reason=$reason hadHello=$savedHello"
                    )
                    dispatchWsTerminateIfNeeded(logLabel = "onClosing", hadHelloAck = savedHello)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val savedHello = helloAcked
                    Log.i(
                        INCOMING_AI_CHAIN_TAG,
                        "live ws onClosed uid=${call.bleUid} code=$code reason=$reason hadHello=$savedHello"
                    )
                    dispatchWsTerminateIfNeeded(logLabel = "onClosed", hadHelloAck = savedHello)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val savedHello = helloAcked
                    Log.e(INCOMING_AI_CHAIN_TAG, "live ws onFailure uid=${call.bleUid}", t)
                    if (!savedHello) {
                        recordWsFailure(t.message)
                    }
                    dispatchWsTerminateIfNeeded(logLabel = "onFailure", hadHelloAck = savedHello)
                }
            })
        }
    }

    /**
     * 仅首次生效；[stop] 会清空 [helloAcked]，故 [hadHelloAck] 必须在各回调**调用本函数前**快照。
     */
    private fun dispatchWsTerminateIfNeeded(logLabel: String, hadHelloAck: Boolean) {
        if (!wsTerminateDispatched.compareAndSet(false, true)) {
            Log.i(INCOMING_AI_CHAIN_TAG, "live ws terminate skip duplicate ($logLabel)")
            return
        }
        Log.i(
            INCOMING_AI_CHAIN_TAG,
            "live ws terminate apply $logLabel hadHello=$hadHelloAck — stop + notify VM"
        )
        stop("ws_$logLabel")
        notifyVmAfterWsTerminated(reason = logLabel, hadHelloAck = hadHelloAck)
    }

    /** 服务器/网络断开：经 [LiveCallSessionRegistry] 投递 [LiveCallSessionEventSink.onLiveWsTerminated]，不直接依赖 ViewModel。 */
    private fun notifyVmAfterWsTerminated(reason: String, hadHelloAck: Boolean) {
        LiveCallSessionRegistry.currentSink()?.onLiveWsTerminated(hadHelloAck = hadHelloAck, reason = reason)
    }

    /**
     * 云端 TTS 二进制 → MCU：须避免在 [WebSocketListener.onMessage] 读线程上先跑录音解码（会阻塞收包与下行）。
     *
     * **Gate**: MCU 在 SCO 未建立前会丢弃所有上行音频（`protocol_handle_audio_data` 中 `!sco_connected` 判断）。
     * 本方法将 WS TTS 帧缓存在 [pendingTtsFrames]，直到 [BleManager.isIncomingAiAudioStartAcked]
     * （MCU `call_state(audio_streaming)`）后一次性 flush，避免 AI 问候语的前几秒被 MCU 丢弃。
     */
    private fun enqueueOrSendWsTtsToBle(arr: ByteArray) {
        if (!ttsGateOpen) {
            if (bleManager.isIncomingAiAudioStartAcked()) {
                ttsGateOpen = true
                flushPendingTtsFrames()
            } else {
                while (pendingTtsFrames.size >= MAX_PENDING_TTS_FRAMES) {
                    pendingTtsFrames.removeFirst()
                }
                pendingTtsFrames.addLast(arr)
                return
            }
        }
        forwardTtsToBle(arr)
    }

    private fun flushPendingTtsFrames() {
        val count = pendingTtsFrames.size
        if (count == 0) return
        Log.i(INCOMING_AI_CHAIN_TAG, "live ws flushing $count buffered TTS frames to BLE")
        /* Stagger the flush: forward all frames but let the BLE writer's
         * per-packet Handler.post scheduling naturally pace the GATT writes.
         * With MAX_PENDING_TTS_FRAMES=10 this is at most 10 frames (~30 BLE
         * packets), well within the MCU's 48-slot queue. */
        while (pendingTtsFrames.isNotEmpty()) {
            forwardTtsToBle(pendingTtsFrames.removeFirst())
        }
    }

    private fun forwardTtsToBle(arr: ByteArray) {
        bleManager.sendUplinkOpus(arr)
        val tap = LiveCallRecordingBridge.onTtsOpus ?: return
        val payload = arr.copyOf()
        scope.launch(Dispatchers.IO) {
            try {
                tap(payload)
            } catch (_: Exception) {
            }
        }
    }

    private fun scheduleHelloRetries(
        prompt: String,
        appellation: String?,
        processStrategyJson: String?,
        call: IncomingCall,
        systemCallType: String = "inbound",
    ) {
        helloAcked = false
        helloRetryJob?.cancel()
        helloRetryJob = scope.launch {
            repeat(3) {
                if (helloAcked) return@launch
                sendHello(prompt, appellation, processStrategyJson, call, systemCallType)
                delay(1000)
            }
            if (!helloAcked) {
                Log.w(INCOMING_AI_CHAIN_TAG, "live ws hello exhausted uid=${call.bleUid}")
                AbnormalCallRecordStore.getInstance(context).append(
                    "websocket_connect_failed",
                    if (language == Language.Zh) "握手超时" else "Handshake timed out."
                )
                stop("hello_exhausted")
            }
        }
    }

    private fun sendHello(
        prompt: String,
        appellation: String?,
        processStrategyJson: String?,
        call: IncomingCall,
        systemCallType: String,
    ) {
        val ws = socket ?: return
        val audioParams = JSONObject()
            .put("sample_rate", 16000)
            .put("channels", 1)
            .put("format", "opus")
            .put("frame_duration", 60)
        val templateVars = JSONObject()
        templateVars.put("languageName", if (language == Language.Zh) "中文" else "English")
        if (!appellation.isNullOrBlank()) templateVars.put("appellation", appellation)
        if (!processStrategyJson.isNullOrBlank()) templateVars.put("processStrategy", processStrategyJson)
        templateVars.put("callerName", call.caller)
        templateVars.put("callerType", if (call.isContact) "contact" else "stranger")
        templateVars.put("isContact", call.isContact)
        templateVars.put("systemCallType", systemCallType)

        val scene: String
        val initiate: JSONObject
        if (systemCallType == "outbound") {
            scene = "call_outbound"
            val bizVars = OutboundRepository.parseBusinessVariables(prompt)
            val businessPrompt = OutboundRepository.extractBusinessPrompt(prompt, bizVars)
            templateVars.put("business_prompt", businessPrompt)
            val dialCtx = bleManager.outboundDialContextForLive()
            if (dialCtx.targetPhone.isNotEmpty()) templateVars.put("target_phone", dialCtx.targetPhone)
            if (dialCtx.callerName.isNotEmpty()) templateVars.put("callerName", dialCtx.callerName)
            if (dialCtx.taskGoal.isNotEmpty()) templateVars.put("task_goal", dialCtx.taskGoal)
            initiate = JSONObject()
                .put("scene", scene)
                .put("template_vars", templateVars)
        } else {
            scene = "call"
            initiate = JSONObject()
                .put("scene", scene)
                .put("prompt", prompt)
                .put("template_vars", templateVars)
        }
        val hello = JSONObject()
            .put("type", "hello")
            .put("audio_params", audioParams)
            .put("initiate", initiate)
        ws.send(hello.toString())
        Log.i(
            INCOMING_AI_CHAIN_TAG,
            "live ws hello sent uid=${call.bleUid} scene=$scene systemCallType=$systemCallType callerType=${if (call.isContact) "contact" else "stranger"}"
        )
    }

    private fun handleTextMessage(raw: String) {
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        when (json.optString("type", "").lowercase()) {
            "hello" -> {
                helloAcked = true
                helloRetryJob?.cancel()
                helloRetryJob = null
                wsSessionId = parseHelloSessionId(json)
                Log.i(
                    INCOMING_AI_CHAIN_TAG,
                    "live ws hello ack uid=$activeBleUid session_id_len=${wsSessionId?.length ?: 0}"
                )
                activeIncomingCall?.let { LiveCallWsSessionHolder.put(it, wsSessionId) }
                LiveCallSessionRegistry.currentSink()?.onLiveWsHelloAcked(serverSessionId = wsSessionId)
                sendListenRealtime()
                flushPendingBleDownlinkToWs()
            }
            "stt" -> {
                val rawText = json.optString("text", "")
                if (rawText.contains("✿END✿")) {
                    Log.i(INCOMING_AI_CHAIN_TAG, "live ws STT ✿END✿ — schedule AI hangup")
                    LiveCallSessionRegistry.currentSink()?.scheduleRemoteAiHangup()
                }
                val display = rawText.replace("✿END✿", "").trim()
                if (display.isNotEmpty()) {
                    LiveCallSessionRegistry.currentSink()?.onLiveSttText(display)
                }
            }
            "end" -> {
                Log.i(INCOMING_AI_CHAIN_TAG, "live ws type=end — schedule AI hangup")
                LiveCallSessionRegistry.currentSink()?.scheduleRemoteAiHangup()
            }
            "tts" -> handleLiveTts(json)
            "filler" -> handleFillerDownlink(json)
            "error" -> {
                val msg = json.optString("message", "")
                if (msg.isNotEmpty()) {
                    Log.w(INCOMING_AI_CHAIN_TAG, "live ws server error uid=$activeBleUid msg=${msg.take(80)}")
                    recordServerError(msg)
                }
            }
            else -> Unit
        }
    }

    /** 对标 iOS `WebSocketService.processTTSMessage` + [OutboundChatController.handleTts] */
    private fun handleLiveTts(json: JSONObject) {
        val state = json.optString("state", "")
        val rawText = json.optString("text", "")
        when (state) {
            "start" -> {
                ttsPendingAiHangup = false
                LiveCallSessionRegistry.currentSink()?.onLiveTtsStart()
            }
            "sentence_start" -> {
                val display = rawText.replace("✿END✿", "").trim()
                if (display.isNotEmpty()) {
                    LiveCallSessionRegistry.currentSink()?.onLiveTtsSentenceStart(display)
                }
            }
            "sentence_end" -> {
                if (rawText.contains("✿END✿")) {
                    ttsPendingAiHangup = true
                }
            }
            "stop" -> {
                LiveCallSessionRegistry.currentSink()?.onLiveTtsStop()
                if (ttsPendingAiHangup) {
                    ttsPendingAiHangup = false
                    LiveCallSessionRegistry.currentSink()?.scheduleRemoteAiHangup()
                }
            }
            else -> Unit
        }
    }

    /**
     * 与 iOS `WebSocketService.swift` commit be506c13 的 `filler` 分支对齐：
     * 服务器在 AI 思考 / TTS 首包静默窗口下发 `{type:"filler", id:"mm_short", text:"嗯"}`
     * → iOS/Android 立刻让 MCU 播本地已预加载的短应答词盖过延迟。不带 sid、不等 ack。
     */
    private fun handleFillerDownlink(json: JSONObject) {
        val id = json.optString("id", "").trim()
        if (id.isEmpty()) {
            Log.i(INCOMING_AI_CHAIN_TAG, "live ws filler ignored: empty id")
            return
        }
        Log.i(INCOMING_AI_CHAIN_TAG, "live ws filler forward id=$id")
        val params = JSONObject().put("filler_id", id)
        bleManager.sendCommand("play_filler", params, expectAck = false)
    }

    private fun parseHelloSessionId(json: JSONObject): String? {
        val a = json.opt("session_id") ?: json.opt("sessionId")
        return when (a) {
            is String -> a.trim().takeIf { it.isNotEmpty() }
            is Number -> a.toString()
            else -> null
        }
    }

    private fun sendListenRealtime() {
        val sid = wsSessionId ?: return
        val msg = JSONObject()
            .put("session_id", sid)
            .put("type", "listen")
            .put("state", "start")
            .put("mode", "realtime")
        socket?.send(msg.toString())
        wsListeningStarted = true
        Log.i(INCOMING_AI_CHAIN_TAG, "live ws listen start realtime uid=$activeBleUid")
    }

    private fun sendListenStopIfNeeded() {
        val sid = wsSessionId ?: return
        if (!wsListeningStarted) return
        try {
            socket?.send(
                JSONObject()
                    .put("session_id", sid)
                    .put("type", "listen")
                    .put("state", "stop")
                    .toString()
            )
        } catch (_: Exception) {
        }
        wsListeningStarted = false
    }

    private fun forwardBleDownlinkToWs(payload: ByteArray) {
        if (payload.isEmpty()) return
        val s = socket
        if (wsListeningStarted && s != null && wsSessionId != null) {
            bleDownlinkToWsCount++
            if (bleDownlinkToWsCount == 1) {
                Log.i(INCOMING_AI_CHAIN_TAG, "live ws first BLE->WS uplink bytes=${payload.size}")
            }
            try {
                s.send(payload.toByteString())
            } catch (e: Exception) {
                Log.w(INCOMING_AI_CHAIN_TAG, "live ws uplink send: ${e.message}")
            }
        } else {
            while (pendingBleDownlink.size >= 40) {
                pendingBleDownlink.removeFirst()
            }
            pendingBleDownlink.addLast(payload.copyOf())
        }
    }

    private fun flushPendingBleDownlinkToWs() {
        val s = socket ?: return
        if (!wsListeningStarted || wsSessionId == null) return
        while (pendingBleDownlink.isNotEmpty()) {
            val p = pendingBleDownlink.removeFirst()
            try {
                s.send(p.toByteString())
            } catch (_: Exception) {
            }
        }
    }

    private fun recordServerError(message: String) {
        val m = message.trim()
        if (m.contains("MCU is not connected", ignoreCase = true) ||
            m.contains("device-id is unavailable", ignoreCase = true)
        ) {
            AbnormalCallRecordStore.getInstance(context).append("device_id_not_synced", m.take(120))
        } else {
            AbnormalCallRecordStore.getInstance(context).append("websocket_connect_failed", m.take(120))
        }
    }

    private fun recordWsFailure(message: String?) {
        val m = message?.trim().orEmpty()
        if (m.contains("MCU is not connected", ignoreCase = true) ||
            m.contains("device-id is unavailable", ignoreCase = true)
        ) {
            AbnormalCallRecordStore.getInstance(context).append("device_id_not_synced", m.take(120))
        } else if (m.isNotEmpty()) {
            AbnormalCallRecordStore.getInstance(context).append("websocket_connect_failed", m.take(120))
        } else {
            AbnormalCallRecordStore.getInstance(context).append(
                "websocket_connect_failed",
                if (language == Language.Zh) "WebSocket 连接失败" else "WebSocket connection failed"
            )
        }
    }

    private fun loadDaijiePrompt(): String? = try {
        context.assets.open("prompts/daijie.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
        Log.e(INCOMING_AI_CHAIN_TAG, "daijie prompt load failed", e)
        null
    }

    private fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
