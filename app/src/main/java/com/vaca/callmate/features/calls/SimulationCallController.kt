package com.vaca.callmate.features.calls

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.util.Log
import com.vaca.callmate.core.audio.CallRecordingFiles
import com.vaca.callmate.core.audio.ConversationStereoWavWriter
import com.vaca.callmate.core.network.ChatSummaryService
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.network.BackendAuthManager
import com.vaca.callmate.data.CallRecord
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.repository.CallRepository
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.features.outbound.OutboundChatConnectionState
import com.vaca.callmate.features.outbound.TtsCharacterStreamBuffer
import com.vaca.callmate.features.outbound.WsManualListenRecorder
import com.vaca.callmate.features.outbound.WsTtsAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import com.vaca.callmate.BuildConfig
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "SimulationCallWS"
private val WS_URL get() = BuildConfig.WS_BASE_URL
private const val WS_CLIENT_ID = "CallMate-iOS"
private const val PROTOCOL_VERSION = "1"

/** 与 iOS `disconnectReactionDelaySec` 默认一致：STT ✿END✿ / `type=end` / TTS ✿END✿ 挂断均用同一延迟 */
private const val SIM_AI_HANGUP_DELAY_MS = 1_000L

/** 与 iOS `SimulationView` + `CallSessionController(scene: .call)` 对齐 */
enum class SimulationUiPhase {
    Connecting,
    /** 对应 iOS ringing / EchoMate 接听中 */
    PickingUp,
    InCall,
    EndedUser,
    EndedAi,
    Error
}

data class SimDialogMessage(
    val id: Long,
    val text: String,
    val isAi: Boolean
)

/**
 * 模拟通话：WebSocket `scene=call` + 麦克风实时上行（[VOICE_COMMUNICATION] 回声消除）+ TTS 播放
 * + 本地 **立体声** WAV（左=麦克风，右=TTS，墙钟时间轴混音，与 iOS `ConversationAudioWriter` 一致）+ Room 持久化。
 */
class SimulationCallController(
    private val context: Context,
    private val bleManager: BleManager,
    private val preferences: AppPreferences,
    private val language: Language,
    private val callRepository: CallRepository,
    private val onSessionFinished: (CallRecord?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val endSessionMutex = Mutex()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val callId: UUID = UUID.randomUUID()
    private val wsBluetoothId: String = UUID.randomUUID().toString()

    private var socket: WebSocket? = null
    private var sessionId: String? = null
    private var helloAcked = false
    private var helloRetryJob: Job? = null
    private var wsListeningStarted = false
    private var realtimeRecorder: WsManualListenRecorder? = null
    private var wavWriter: ConversationStereoWavWriter? = null
    private var recordingFileName: String? = null
    private var sessionStartedAtMs: Long = 0L
    private var durationJob: Job? = null
    private var pickingUpJob: Job? = null
    private var sttAiHangupJob: Job? = null
    private val messageId = AtomicLong(1L)
    private var lastUserSttAt = 0L
    private var isAiHangup = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val ttsPlayer = WsTtsAudioPlayer(scope)

    private val _phase = MutableStateFlow(SimulationUiPhase.Connecting)
    val phase: StateFlow<SimulationUiPhase> = _phase.asStateFlow()

    private val _dialogMessages = MutableStateFlow<List<SimDialogMessage>>(emptyList())
    val dialogMessages: StateFlow<List<SimDialogMessage>> = _dialogMessages.asStateFlow()

    private val _ttsStreamingText = MutableStateFlow("")
    val ttsStreamingText: StateFlow<String> = _ttsStreamingText.asStateFlow()

    private val _ttsStreamingLoading = MutableStateFlow(false)
    val ttsStreamingLoading: StateFlow<Boolean> = _ttsStreamingLoading.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0)
    val durationSeconds: StateFlow<Int> = _durationSeconds.asStateFlow()

    private val _connectionState = MutableStateFlow(OutboundChatConnectionState.Disconnected)
    val connectionState: StateFlow<OutboundChatConnectionState> = _connectionState.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

    private lateinit var ttsStreamBuffer: TtsCharacterStreamBuffer

    init {
        ttsStreamBuffer = TtsCharacterStreamBuffer(
            scope = scope,
            baseSpeedMs = 30.0,
            bufferSpeedK = 10.0,
            onDisplayUpdate = { text ->
                _ttsStreamingText.value = text
                if (text.isNotEmpty()) {
                    _ttsStreamingLoading.value = false
                    if (_phase.value == SimulationUiPhase.PickingUp) {
                        _phase.value = SimulationUiPhase.InCall
                    }
                }
            },
            onFinished = { finalText ->
                _ttsStreamingLoading.value = false
                _ttsStreamingText.value = ""
                appendAiMessage(finalText)
            }
        )
    }

    fun start() {
        scope.launch(Dispatchers.IO) {
            connectInner()
        }
    }

    private suspend fun connectInner() {
        val deviceId = bleManager.runtimeMCUDeviceID.value?.trim().orEmpty()
        val connectedAddr = bleManager.connectedAddress.value
        val connected = !connectedAddr.isNullOrBlank()
        if (!connected || deviceId.isEmpty()) {
            withContext(Dispatchers.Main) {
                _phase.value = SimulationUiPhase.Error
                _errorText.value = if (language == Language.Zh) {
                    "请先连接 EchoCard 后再试。"
                } else {
                    "Please connect EchoCard first."
                }
            }
            return
        }
        val token = BackendAuthManager.ensureToken(preferences)
        if (token == null || !BackendAuthManager.looksLikeJWT(token)) {
            withContext(Dispatchers.Main) {
                _phase.value = SimulationUiPhase.Error
                _errorText.value = if (language == Language.Zh) "无法连接：缺少登录凭证。" else "Missing login token."
            }
            return
        }
        BackendAuthManager.reportDevice(preferences, deviceId, wsBluetoothId, token)
        val prompt = loadDaijiePrompt()
        if (prompt.isNullOrBlank()) {
            withContext(Dispatchers.Main) {
                _phase.value = SimulationUiPhase.Error
                _errorText.value = if (language == Language.Zh) "缺少代接提示词资源。" else "Missing daijie prompt asset."
            }
            return
        }
        val processStrategyJson = com.vaca.callmate.data.ProcessStrategyStore.processStrategyJSONString(context)
        val appellation = preferences.userAppellationFlow.first().trim().takeIf { it.isNotEmpty() }
        val phoneIdHeader = sha256Hex("模拟通话")

        val req = Request.Builder()
            .url(WS_URL)
            .header("Device-Id", deviceId)
            .header("Client-Id", WS_CLIENT_ID)
            .header("Protocol-Version", PROTOCOL_VERSION)
            .header("phone_id", phoneIdHeader)
            .header("Authorization", "Bearer $token")
            .build()

        withContext(Dispatchers.Main) {
            _connectionState.value = OutboundChatConnectionState.Connecting
            _phase.value = SimulationUiPhase.Connecting
        }

        withContext(Dispatchers.Main) {
            socket = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch(Dispatchers.Main) {
                        scheduleHelloRetries(prompt, appellation, processStrategyJson)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch(Dispatchers.Main) { handleTextMessage(text) }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    ttsPlayer.playOpusFrame(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "WS onClosing code=$code reason=$reason")
                    handleWsTerminated(failure = null, logLabel = "onClosing")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WS onClosed code=$code reason=$reason")
                    handleWsTerminated(failure = null, logLabel = "onClosed")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WS failure", t)
                    handleWsTerminated(failure = t, logLabel = "onFailure")
                }
            })
        }
    }

    /**
     * 与 iOS [processWebSocketDisconnect] / `end(abortReason: "ws_disconnect")` 一致：已握手则持久化并 [onSessionFinished]（结束页）。
     * 握手前则保持 Error 全屏，不写入记录。
     */
    private fun handleWsTerminated(failure: Throwable?, logLabel: String) {
        scope.launch(Dispatchers.Main) {
            val p = _phase.value
            if (p == SimulationUiPhase.EndedUser || p == SimulationUiPhase.EndedAi) {
                _connectionState.value = OutboundChatConnectionState.Disconnected
                return@launch
            }
            if (!helloAcked) {
                Log.w(TAG, "WS $logLabel before hello — error UI")
                teardownAudioPipeline()
                _connectionState.value = OutboundChatConnectionState.Error
                _phase.value = SimulationUiPhase.Error
                _errorText.value = failure?.message ?: if (language == Language.Zh) {
                    "连接已断开"
                } else {
                    "Connection closed"
                }
                return@launch
            }
            Log.i(TAG, "WS $logLabel — completeSession (ws_disconnect)")
            launch(Dispatchers.IO) {
                completeSession(asAiHangup = false)
            }
        }
    }

    /**
     * STT ✿END✿、`type=end`、TTS `stop`（✿END✿ 挂断）共用：与 iOS `webSocketDidReceiveAIHangup` 相同延迟后结束会话。
     */
    private fun scheduleAiHangupFromRemoteMarker() {
        sttAiHangupJob?.cancel()
        sttAiHangupJob = scope.launch(Dispatchers.IO) {
            delay(SIM_AI_HANGUP_DELAY_MS)
            sttAiHangupJob = null
            if (_phase.value != SimulationUiPhase.InCall && _phase.value != SimulationUiPhase.PickingUp) return@launch
            completeSession(asAiHangup = true)
        }
    }

    private fun loadDaijiePrompt(): String? = try {
        context.assets.open("prompts/daijie.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
        Log.e(TAG, "daijie prompt", e)
        null
    }

    private fun scheduleHelloRetries(prompt: String, appellation: String?, processStrategyJson: String?) {
        helloAcked = false
        helloRetryJob?.cancel()
        helloRetryJob = scope.launch {
            repeat(3) {
                if (helloAcked) return@launch
                sendHello(prompt, appellation, processStrategyJson)
                delay(1000)
            }
            if (!helloAcked) {
                _phase.value = SimulationUiPhase.Error
                _errorText.value = if (language == Language.Zh) "握手超时，请重试。" else "Handshake timed out."
                abandonWithoutPersist()
            }
        }
    }

    private fun sendHello(prompt: String, appellation: String?, processStrategyJson: String?) {
        val ws = socket ?: return
        val audioParams = JSONObject()
            .put("sample_rate", 16000)
            .put("channels", 1)
            .put("format", "opus")
            .put("frame_duration", 60)
        val initiate = JSONObject()
            .put("scene", "call")
            .put("prompt", prompt)
        val templateVars = JSONObject()
        templateVars.put("languageName", if (language == Language.Zh) "中文" else "English")
        if (!appellation.isNullOrBlank()) {
            templateVars.put("appellation", appellation)
        }
        if (!processStrategyJson.isNullOrBlank()) {
            templateVars.put("processStrategy", processStrategyJson)
        }
        initiate.put("template_vars", templateVars)
        val hello = JSONObject()
            .put("type", "hello")
            .put("audio_params", audioParams)
            .put("initiate", initiate)
        ws.send(hello.toString())
        Log.i(TAG, "hello sent scene=call")
    }

    private suspend fun handleTextMessage(raw: String) {
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        val type = json.optString("type", "")
        when (type) {
            "end" -> {
                Log.i(TAG, "WS type=end — AI hangup")
                scheduleAiHangupFromRemoteMarker()
            }
            "hello" -> {
                sessionId = parseHelloSessionId(json)
                helloAcked = true
                helloRetryJob?.cancel()
                helloRetryJob = null
                _connectionState.value = OutboundChatConnectionState.Connected
                sessionStartedAtMs = System.currentTimeMillis()
                Log.i(TAG, "hello ack sessionId=$sessionId")
                _phase.value = SimulationUiPhase.PickingUp
                sendListenRealtime()
                yield()
                startRealtimeRecordingPipeline()
                pickingUpJob?.cancel()
                pickingUpJob = scope.launch {
                    delay(400)
                    if (_phase.value == SimulationUiPhase.PickingUp) {
                        _phase.value = SimulationUiPhase.InCall
                    }
                }
                startDurationTicker()
            }
            "stt" -> {
                val rawText = json.optString("text", "")
                if (rawText.contains("✿END✿")) {
                    Log.i(TAG, "STT ✿END✿ — AI hangup (iOS webSocketDidReceiveAIHangup)")
                    scheduleAiHangupFromRemoteMarker()
                }
                val display = rawText.replace("✿END✿", "").trim()
                if (display.isEmpty()) return
                val now = System.currentTimeMillis()
                if (now - lastUserSttAt < 800) {
                    val last = _dialogMessages.value.lastOrNull { !it.isAi }
                    if (last != null && last.text == display) return
                }
                lastUserSttAt = now
                if (_phase.value == SimulationUiPhase.PickingUp) {
                    _phase.value = SimulationUiPhase.InCall
                }
                appendCallerMessage(display)
            }
            "tts" -> handleTts(json)
            "error" -> {
                _ttsStreamingLoading.value = false
                val msg = json.optString("message", "")
                if (msg.isNotEmpty()) {
                    _errorText.value = msg
                }
            }
            else -> Unit
        }
    }

    private fun handleTts(json: JSONObject) {
        val state = json.optString("state", "")
        val rawText = json.optString("text", "")
        when (state) {
            "start" -> {
                // 新一轮 TTS 开始：清除上一轮 sentence_end 可能留下的挂断标记
                isAiHangup = false
                ttsStreamBuffer.flushAndReset()
                ttsPlayer.onTtsStart()
            }
            "sentence_start" -> {
                val display = rawText.replace("✿END✿", "").trim()
                if (display.isNotEmpty()) {
                    ttsStreamBuffer.append(display)
                }
            }
            "sentence_end" -> {
                if (rawText.contains("✿END✿")) {
                    isAiHangup = true
                }
            }
            "stop" -> {
                ttsStreamBuffer.markDone()
                ttsPlayer.onTtsStop()
                if (isAiHangup) {
                    scheduleAiHangupFromRemoteMarker()
                }
            }
            else -> Unit
        }
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
        val sid = sessionId ?: return
        val msg = JSONObject()
            .put("session_id", sid)
            .put("type", "listen")
            .put("state", "start")
            .put("mode", "realtime")
        socket?.send(msg.toString())
        wsListeningStarted = true
        Log.i(TAG, "listen start realtime")
    }

    private fun sendListenStop() {
        val sid = sessionId ?: return
        val msg = JSONObject()
            .put("session_id", sid)
            .put("type", "listen")
            .put("state", "stop")
        socket?.send(msg.toString())
        wsListeningStarted = false
    }

    private fun sendAbort() {
        val sid = sessionId ?: return
        val msg = JSONObject()
            .put("session_id", sid)
            .put("type", "abort")
            .put("reason", "user_interrupt")
        socket?.send(msg.toString())
    }

    private fun startRealtimeRecordingPipeline() {
        if (realtimeRecorder != null) return
        val wav = CallRecordingFiles.file(context, "sim_${callId}.wav")
        val writer = ConversationStereoWavWriter(wav)
        wavWriter = writer
        recordingFileName = wav.name
        ttsPlayer.onDecodedPcmForRecording = { pcm, len ->
            writer.appendRightPcm(pcm, len)
        }
        try {
            val rec = WsManualListenRecorder(
                context = context,
                onOpusPacket = { opus ->
                    val s = socket
                    if (s != null && helloAcked && wsListeningStarted) {
                        try {
                            s.send(opus.toByteString())
                        } catch (e: Exception) {
                            Log.w(TAG, "opus send: ${e.message}")
                        }
                    }
                },
                audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                onPcmFrame = { pcm, len ->
                    try {
                        writer.appendLeftPcm(pcm.copyOf(len), len)
                    } catch (e: Exception) {
                        Log.w(TAG, "wav write: ${e.message}")
                    }
                }
            )
            rec.start()
            realtimeRecorder = rec
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            } catch (e: Exception) {
                Log.w(TAG, "MODE_IN_COMMUNICATION: ${e.message}")
            }
            ttsPlayer.bindDuplexPlaybackSession(rec.audioSessionId())
        } catch (e: Exception) {
            Log.e(TAG, "startRealtimeRecordingPipeline", e)
        }
    }

    private fun teardownAudioPipeline() {
        pickingUpJob?.cancel()
        pickingUpJob = null
        durationJob?.cancel()
        durationJob = null
        if (sessionId != null) {
            sendListenStop()
        }
        ttsPlayer.onDecodedPcmForRecording = null
        ttsPlayer.clearDuplexPlaybackSession()
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
        realtimeRecorder?.stop()
        realtimeRecorder = null
        try {
            wavWriter?.close()
        } catch (_: Exception) {
        }
        wavWriter = null
    }

    private fun startDurationTicker() {
        durationJob?.cancel()
        durationJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (sessionStartedAtMs > 0) {
                    _durationSeconds.value = ((System.currentTimeMillis() - sessionStartedAtMs) / 1000).toInt()
                }
            }
        }
    }

    private fun appendCallerMessage(text: String) {
        val id = messageId.incrementAndGet()
        _dialogMessages.value = _dialogMessages.value + SimDialogMessage(id, text, isAi = false)
    }

    private fun appendAiMessage(text: String) {
        val id = messageId.incrementAndGet()
        _dialogMessages.value = _dialogMessages.value + SimDialogMessage(id, text, isAi = true)
    }

    /** 用户挂断：持久化并 [onSessionFinished]（与 iOS `persistCallIfNeeded` + `onEnd` 一致） */
    fun userHangUp() {
        scope.launch(Dispatchers.IO) {
            completeSession(asAiHangup = false)
        }
    }

    private suspend fun completeSession(
        asAiHangup: Boolean,
    ) {
        endSessionMutex.withLock {
            sttAiHangupJob?.cancel()
            sttAiHangupJob = null
            if (_phase.value == SimulationUiPhase.EndedUser || _phase.value == SimulationUiPhase.EndedAi) {
                return
            }
            val savedSessionId = sessionId
            val started = sessionStartedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis()
            val durationSec = _durationSeconds.value.coerceAtLeast(0)
            val messagesSnapshot = _dialogMessages.value
            val recFile = recordingFileName
            withContext(Dispatchers.Main) {
                _phase.value = if (asAiHangup) SimulationUiPhase.EndedAi else SimulationUiPhase.EndedUser
                sendAbort()
                teardownAudioPipeline()
                try {
                    socket?.close(1000, null)
                } catch (_: Exception) {
                }
                socket = null
                helloAcked = false
                sessionId = null
                _connectionState.value = OutboundChatConnectionState.Disconnected
            }
            val summary = if (language == Language.Zh) "模拟通话已保存" else "Simulation saved"
            val fullSummary = if (language == Language.Zh) {
                "本次模拟通话的转写已保存到本地记录中。"
            } else {
                "Transcript for this simulation has been saved locally."
            }
            val phone = if (language == Language.Zh) "模拟测试" else "Simulation"
            val label = if (language == Language.Zh) "陌生号码" else "Unknown"
            val transcript = messagesSnapshot.map { m ->
                val sender = if (m.isAi) "ai" else "caller"
                sender to m.text
            }
            withContext(Dispatchers.IO) {
                callRepository.insertCall(
                    id = callId.toString(),
                    startedAtMillis = started,
                    durationSeconds = durationSec,
                    statusRaw = "handled",
                    phone = phone,
                    label = label,
                    summary = summary,
                    fullSummary = fullSummary,
                    languageRaw = if (language == Language.Zh) "zh" else "en",
                    isSimulation = true,
                    transcript = transcript,
                    recordingFileName = recFile,
                    wsSessionId = savedSessionId
                )
                val rec = callRepository.getById(callId.toString(), language)
                val sid = savedSessionId?.trim().orEmpty()
                if (sid.isNotEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            ChatSummaryService.pollAndUpdate(
                                callId = callId.toString(),
                                sessionId = sid,
                                preferences = preferences,
                                repository = callRepository
                            )
                        }.onFailure { Log.w(TAG, "chat summary poll: ${it.message}") }
                    }
                } else {
                    Log.i(TAG, "[Summary] skip poll: missing session_id")
                }
                withContext(Dispatchers.Main) {
                    onSessionFinished(rec)
                }
            }
        }
    }

    /** 离开页面且未正常结束时：结束连接但不写入通话记录（与 iOS 丢弃未持久化录音类似） */
    fun abandonWithoutPersist() {
        scope.launch(Dispatchers.IO) {
            sttAiHangupJob?.cancel()
            sttAiHangupJob = null
            if (_phase.value == SimulationUiPhase.EndedUser || _phase.value == SimulationUiPhase.EndedAi) {
                return@launch
            }
            val wavName = recordingFileName
            withContext(Dispatchers.Main) {
                _phase.value = SimulationUiPhase.EndedUser
                sendAbort()
                teardownAudioPipeline()
                try {
                    socket?.close(1000, null)
                } catch (_: Exception) {
                }
                socket = null
                helloAcked = false
                sessionId = null
                _connectionState.value = OutboundChatConnectionState.Disconnected
            }
            wavName?.let { name ->
                try {
                    CallRecordingFiles.file(context, name).delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun dispose() {
        abandonWithoutPersist()
    }

    private fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
