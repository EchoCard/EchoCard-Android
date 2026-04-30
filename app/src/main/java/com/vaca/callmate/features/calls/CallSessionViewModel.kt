package com.vaca.callmate.features.calls

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaca.callmate.core.network.ChatSummaryService
import com.vaca.callmate.data.CallRecord
import com.vaca.callmate.data.IncomingCall
import com.vaca.callmate.data.MessageSender
import com.vaca.callmate.features.outbound.TtsCharacterStreamBuffer
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.INCOMING_AI_CHAIN_TAG
import com.vaca.callmate.core.ble.audioStop
import com.vaca.callmate.core.ble.hangup
import com.vaca.callmate.core.ble.hfpDisconnect
import com.vaca.callmate.data.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "CallSessionVM"
/** 与 iOS `disconnectReactionDelaySec` 默认 1s、`SimulationCallController.SIM_AI_HANGUP_DELAY_MS` 一致 */
private const val LIVE_AI_HANGUP_DELAY_MS = 1_000L

/** 与 iOS CallSessionController 状态对齐 */
enum class CallSessionStatus {
    Idle,
    Connecting,
    Ringing,
    Connected,
    Ended
}

data class TranscriptMessage(
    val id: Long,
    val sender: MessageSender,
    val text: String,
    val isStreaming: Boolean = false
)

private enum class LiveEndReason {
    /** 用户离开 Live 页 */
    ViewDismiss,
    /** STT/TTS ✿END✿、`type=end` */
    RemoteAiHangup,
    /** WS onFailure / 断开 */
    WsDisconnect,
    /** 右滑真人接听：与 iOS `handoffToHuman` */
    Handoff,
    /** BLE 已报 ended（MCU 已挂断） */
    BleTerminal,
}

class CallSessionViewModel : ViewModel(), LiveCallSessionEventSink {

    private val _status = MutableStateFlow(CallSessionStatus.Idle)
    val status: StateFlow<CallSessionStatus> = _status.asStateFlow()

    private val _messages = MutableStateFlow<List<TranscriptMessage>>(emptyList())
    val messages: StateFlow<List<TranscriptMessage>> = _messages.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0)
    val durationSeconds: StateFlow<Int> = _durationSeconds.asStateFlow()

    private val _incomingCall = MutableStateFlow<IncomingCall?>(null)
    val incomingCall: StateFlow<IncomingCall?> = _incomingCall.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _ttsStreamingText = MutableStateFlow("")
    val ttsStreamingText: StateFlow<String> = _ttsStreamingText.asStateFlow()

    private val _ttsStreamingLoading = MutableStateFlow(false)
    val ttsStreamingLoading: StateFlow<Boolean> = _ttsStreamingLoading.asStateFlow()

    /** 持久化完成后供 [LiveCallView] 跳转详情（与 Simulation `onSessionFinished` 一致） */
    private val _finishedCallRecord = MutableStateFlow<CallRecord?>(null)
    val finishedCallRecord: StateFlow<CallRecord?> = _finishedCallRecord.asStateFlow()

    @Volatile
    var liveFinishDeps: LiveFinishDeps? = null

    private var durationJob: Job? = null
    private var messageIdCounter = 0L
    private var sttAiHangupJob: Job? = null

    private var liveCallUuid: UUID = UUID.randomUUID()
    /** 外呼 Live：`startFromIncomingCall` 时从 [LiveFinishDeps.bleManager] 快照，与 iOS `activeOutboundTaskID` 一致。 */
    private var liveOutboundTaskId: String? = null
    private var sessionStartedAtMs: Long = 0L
    /** WS `hello` 返回的 session_id，与 [SimulationCallController] 的 `sessionId` 一样在握手后固化，避免仅靠 [LiveCallWsSessionHolder] 与 [IncomingCall] identity 漂移 */
    private var liveWsSessionId: String? = null
    private var wsHelloAckedForSession: Boolean = false
    private var persistCompleted: Boolean = false
    private var recorder: LiveCallConversationRecorder? = null

    private val completeMutex = Mutex()
    private val ttsStreamBuffer: TtsCharacterStreamBuffer

    init {
        ttsStreamBuffer = TtsCharacterStreamBuffer(
            scope = viewModelScope,
            baseSpeedMs = 30.0,
            bufferSpeedK = 10.0,
            onDisplayUpdate = { text ->
                _ttsStreamingText.value = text
                if (text.isNotEmpty()) {
                    _ttsStreamingLoading.value = false
                }
            },
            onFinished = { finalText ->
                _ttsStreamingLoading.value = false
                _ttsStreamingText.value = ""
                addMessage(MessageSender.Ai, finalText)
            }
        )
        LiveCallSessionRegistry.register(this)
    }

    /**
     * 与 iOS `activatePendingCallIfNeeded`：进入 Live 全屏后为 **Connecting**；
     * **Connected** 由 WS `hello` → [onLiveWsHelloAcked]。
     */
    fun startFromIncomingCall(call: IncomingCall) {
        val prev = _incomingCall.value
        val sameSession = prev != null &&
            call.bleSid != null && prev.bleSid != null &&
            call.bleSid == prev.bleSid
        if (sameSession && _status.value == CallSessionStatus.Connected) {
            _incomingCall.value = call
            refreshLiveOutboundSnapshotIfNeeded(call)
            return
        }
        if (sameSession && _status.value == CallSessionStatus.Connecting) {
            _incomingCall.value = call
            refreshLiveOutboundSnapshotIfNeeded(call)
            // 与完整路径一致：hello 已先到但本分支曾提前 return 时，holder 已有 session_id 则补一次 Connected
            if (LiveCallWsSessionHolder.get(call).isNotEmpty()) {
                onLiveWsHelloAcked(serverSessionId = null)
            }
            return
        }
        _incomingCall.value = call
        _messages.value = emptyList()
        messageIdCounter = 0L
        _durationSeconds.value = 0
        durationJob?.cancel()
        durationJob = null
        resetLiveTtsStreaming()
        liveCallUuid = UUID.randomUUID()
        sessionStartedAtMs = 0L
        liveWsSessionId = null
        wsHelloAckedForSession = false
        persistCompleted = false
        _finishedCallRecord.value = null
        sttAiHangupJob?.cancel()
        sttAiHangupJob = null
        stopRecordingBridgeOnly()
        refreshLiveOutboundSnapshotIfNeeded(call)
        _status.value = CallSessionStatus.Connecting
        if (LiveCallWsSessionHolder.get(call).isNotEmpty()) {
            onLiveWsHelloAcked(serverSessionId = null)
        }
    }

    private fun refreshLiveOutboundSnapshotIfNeeded(call: IncomingCall) {
        if (call.title != "[OUTBOUND_TASK]") {
            liveOutboundTaskId = null
            return
        }
        liveFinishDeps?.bleManager?.let { bm ->
            liveOutboundTaskId = bm.outboundTaskIdForLive()
        }
    }

    fun onIncomingCallFromCoordinator(call: IncomingCall) {
        val prev = _incomingCall.value
        val prevSid = prev?.bleSid
        val newSid = call.bleSid
        if (newSid != null && prevSid != null && newSid != prevSid) {
            Log.i(TAG, "SID changed $prevSid -> $newSid: clearing transcript for new call session")
            _messages.value = emptyList()
            messageIdCounter = 0L
            _durationSeconds.value = 0
            durationJob?.cancel()
            durationJob = null
            resetLiveTtsStreaming()
            liveWsSessionId = null
            wsHelloAckedForSession = false
            persistCompleted = false
            _finishedCallRecord.value = null
        }
        if (_status.value != CallSessionStatus.Idle && _status.value != CallSessionStatus.Ended) {
            if (newSid != null && prevSid != null && newSid != prevSid) {
                _incomingCall.value = call
                _status.value = CallSessionStatus.Ringing
            }
            return
        }
        _incomingCall.value = call
        _status.value = CallSessionStatus.Ringing
    }

    /**
     * @param serverSessionId WS `hello` 文本帧中的 `session_id`；为 null 时从 [LiveCallWsSessionHolder] 补全（竞态/断线前同步路径）
     */
    override fun onLiveWsHelloAcked(serverSessionId: String?) {
        if (_status.value == CallSessionStatus.Ended || _status.value == CallSessionStatus.Idle) return
        if (_status.value == CallSessionStatus.Connected) return
        val fromServer = serverSessionId?.trim()?.takeIf { it.isNotEmpty() }
        val fromHolder =
            _incomingCall.value?.let { LiveCallWsSessionHolder.get(it).trim() }.orEmpty().takeIf { it.isNotEmpty() }
        liveWsSessionId = fromServer ?: fromHolder
        wsHelloAckedForSession = true
        if (sessionStartedAtMs == 0L) {
            sessionStartedAtMs = System.currentTimeMillis()
        }
        _status.value = CallSessionStatus.Connected
        startDurationCounter()
        // 勿仅依赖 Live 页 LaunchedEffect：后台/未重组时也要开录，否则详情页无录音文件（对齐 iOS 会话录音始终跟随接通）
        liveFinishDeps?.let { deps ->
            startRecording(deps.appContext, deps.bleManager)
        }
    }

    fun startRecording(context: android.content.Context, bleManager: BleManager) {
        if (recorder != null) return
        if (_status.value != CallSessionStatus.Connected) return
        val r = LiveCallConversationRecorder(
            context.applicationContext,
            bleManager,
            viewModelScope,
            liveCallUuid
        )
        r.start()
        LiveCallRecordingBridge.onTtsOpus = { bytes -> r.onTtsOpusFrame(bytes) }
        recorder = r
    }

    fun consumeFinishedCallRecord() {
        _finishedCallRecord.value = null
    }

    /**
     * 与 iOS `prepareIncomingCall` 后 `scheduleAiHangupFromRemoteMarker` / `SimulationCallController`：`type=end`、✿END✿。
     */
    override fun scheduleRemoteAiHangup() {
        sttAiHangupJob?.cancel()
        sttAiHangupJob = viewModelScope.launch {
            delay(LIVE_AI_HANGUP_DELAY_MS)
            sttAiHangupJob = null
            completeLiveCallInternal(LiveEndReason.RemoteAiHangup)
        }
    }

    /** 与 iOS `processWebSocketDisconnect` + `end(abortReason: "ws_disconnect")`（已握手）一致 */
    override fun onLiveWsDisconnected() {
        viewModelScope.launch {
            completeLiveCallInternal(LiveEndReason.WsDisconnect)
        }
    }

    /**
     * 由 [LiveCallIncomingWebSocket] 在任意线程调用；内部 [Handler] 切主线程，与原先 WS 内逻辑一致。
     */
    override fun onLiveWsTerminated(hadHelloAck: Boolean, reason: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                if (hadHelloAck && _status.value == CallSessionStatus.Connecting) {
                    Log.i(
                        INCOMING_AI_CHAIN_TAG,
                        "live ws: sync VM Connected before disconnect (hadHello st was Connecting)"
                    )
                    onLiveWsHelloAcked(serverSessionId = null)
                }
                val stAfter = _status.value
                val incoming = _incomingCall.value
                val shouldComplete =
                    hadHelloAck ||
                        (incoming != null &&
                            stAfter != CallSessionStatus.Idle &&
                            stAfter != CallSessionStatus.Ended)
                if (shouldComplete) {
                    Log.i(
                        INCOMING_AI_CHAIN_TAG,
                        "live ws -> VM onLiveWsDisconnected reason=$reason hadHello=$hadHelloAck st=$stAfter incoming=${incoming != null}"
                    )
                    onLiveWsDisconnected()
                }
            } catch (e: Exception) {
                Log.e(INCOMING_AI_CHAIN_TAG, "onLiveWsTerminated failed reason=$reason", e)
            }
        }
    }

    fun onBleCallTerminal() {
        viewModelScope.launch {
            completeLiveCallInternal(LiveEndReason.BleTerminal)
        }
    }

    /** 用户离开 Live 全屏：与 iOS `onDisappear { controller.end() }` + persist 一致 */
    fun onLiveViewDisappear() {
        viewModelScope.launch {
            completeLiveCallInternal(LiveEndReason.ViewDismiss)
        }
    }

    fun handoff() {
        viewModelScope.launch {
            completeLiveCallInternal(LiveEndReason.Handoff)
        }
    }

    private suspend fun completeLiveCallInternal(reason: LiveEndReason) {
        completeMutex.withLock {
            if (persistCompleted) return
            val deps = liveFinishDeps
            val call = _incomingCall.value
            val holderSid = call?.let { LiveCallWsSessionHolder.get(it).trim() }.orEmpty()
            val persistedSid =
                liveWsSessionId?.trim()?.takeIf { it.isNotEmpty() } ?: holderSid
            val shouldPersist =
                (wsHelloAckedForSession || persistedSid.isNotEmpty()) &&
                    deps != null && call != null && deps.callRepository != null
            val isHandoff = reason == LiveEndReason.Handoff

            sttAiHangupJob?.cancel()
            sttAiHangupJob = null

            // ConversationStereoWavWriter.close 为同步磁盘 IO，禁止在 Main 执行（否则 Handler/主线程收尾会 ANR、掉帧）
            val recorderToStop = recorder
            recorder = null
            LiveCallRecordingBridge.onTtsOpus = null
            val recFile = try {
                withContext(Dispatchers.IO) {
                    recorderToStop?.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "recorder stop: ${e.message}")
                null
            }

            val abortReason = when (reason) {
                LiveEndReason.Handoff -> "handoff_to_human"
                LiveEndReason.ViewDismiss -> "live_view_dismiss"
                LiveEndReason.WsDisconnect -> "ws_disconnect"
                LiveEndReason.RemoteAiHangup -> "remote_ai_hangup"
                LiveEndReason.BleTerminal -> "ble_terminal"
            }
            IncomingCallSessionCoordinator.abortAndStopLiveWebSocket(abortReason)
            OutboundLiveSessionCoordinator.abortAndStopLiveWebSocket(abortReason)

            if (call != null && deps != null) {
                try {
                    when (reason) {
                        LiveEndReason.BleTerminal -> Unit
                        LiveEndReason.Handoff -> {
                            deps.bleManager.audioStop(call.bleSid)
                            deps.bleManager.hfpDisconnect()
                        }
                        else -> {
                            deps.bleManager.audioStop(call.bleSid)
                            // 陌生号 smart 模式下用户在 pickup 窗口内抢接时，我们没给 MCU 发
                            // answer；此时 AI 会话结束（RemoteAiHangup / WsDisconnect）不能再
                            // 下发 hangup，否则 MCU `AT+CHUP` 会挂掉用户正在说的通话。
                            // 对齐 iOS `shouldSuppressBLEHangup`（commit 883ede8d）。
                            if (IncomingCallSessionCoordinator.shouldSuppressBleHangup()) {
                                Log.i(
                                    TAG,
                                    "ble teardown: suppress hangup (userHandledEarly) reason=$reason sid=${call.bleSid}"
                                )
                            } else {
                                deps.bleManager.hangup(call.bleSid)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ble teardown: ${e.message}")
                }
            }

            durationJob?.cancel()
            durationJob = null
            val capturedOutboundTaskId = liveOutboundTaskId
            resetLiveTtsStreaming()

            if (!shouldPersist) {
                persistCompleted = true
                call?.let { LiveCallWsSessionHolder.clear(it) }
                _status.value = CallSessionStatus.Ended
                return
            }

            persistCompleted = true
            val lang = deps!!.language
            val isOutboundLive = call.title == "[OUTBOUND_TASK]"
            val savedSummaryBase =
                if (lang == Language.Zh) "通话录音已保存" else "Call recording saved"
            /*
             * 与 iOS 两条路径一致：
             * - `LiveCallView.persistCallIfNeeded`：外呼且 WS 已 hello（Live 会话）→ summary = "[OUTBOUND_TASK] " + 保存文案；label/phone 同 Live。
             * - `CallSummaryCoordinator.persistOutboundCall`：外呼但未走 Live WS 收尾 → summary = "[OUTBOUND_TASK] " + 号码或 "Outbound"；label 英文 Outbound Call / 号码。
             */
            val outboundLiveHelloPath = isOutboundLive && wsHelloAckedForSession
            val outboundCoordinatorPath = isOutboundLive && !wsHelloAckedForSession
            val summary = when {
                outboundLiveHelloPath ->
                    "[OUTBOUND_TASK] $savedSummaryBase"
                outboundCoordinatorPath -> {
                    val rawPhone = call!!.number.trim()
                    "[OUTBOUND_TASK] " + if (rawPhone.isEmpty()) "Outbound" else rawPhone
                }
                else -> savedSummaryBase
            }
            val fullSummary: String? = null
            val phone = when {
                outboundCoordinatorPath -> call!!.number.trim()
                else -> call!!.number.ifBlank {
                    if (lang == Language.Zh) "未知号码" else "Unknown"
                }
            }
            val label = when {
                outboundLiveHelloPath ->
                    call.caller.ifBlank { call.number }.ifBlank {
                        if (lang == Language.Zh) "外呼号码" else "Outbound Call"
                    }
                outboundCoordinatorPath -> {
                    val p = call.number.trim()
                    if (p.isEmpty()) "Outbound Call" else p
                }
                else ->
                    call.caller.ifBlank {
                        if (lang == Language.Zh) "未知来电" else "Unknown Caller"
                    }
            }
            val transcript = _messages.value.map { m ->
                val sender = when (m.sender) {
                    MessageSender.Caller -> "caller"
                    MessageSender.Ai -> "ai"
                    else -> "system"
                }
                sender to m.text
            }
            val sid = persistedSid
            val durationSec = _durationSeconds.value.coerceAtLeast(0)
            val wasAnswered = durationSec > 0 || transcript.isNotEmpty()
            val statusRaw = when {
                outboundCoordinatorPath ->
                    if (wasAnswered) "handled" else "missed"
                else -> "handled"
            }
            val started = sessionStartedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis()

            val repo = deps.callRepository!!
            val callIdStr = liveCallUuid.toString()
            val prefs = deps.preferences
            withContext(Dispatchers.IO) {
                try {
                    repo.insertCall(
                        id = callIdStr,
                        startedAtMillis = started,
                        durationSeconds = durationSec,
                        statusRaw = statusRaw,
                        phone = phone,
                        label = label,
                        summary = summary,
                        fullSummary = fullSummary,
                        languageRaw = if (lang == Language.Zh) "zh" else "en",
                        isSimulation = false,
                        transcript = transcript,
                        recordingFileName = recFile,
                        wsSessionId = sid.ifEmpty { null },
                        outboundTaskId = if (isOutboundLive) capturedOutboundTaskId else null
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "insertCall failed", e)
                }
                // 与 SimulationCallController.completeSession 一致：异步轮询摘要，不阻塞进详情；CallDetailScreen 通过 observeCallRecord 刷新 AI 卡片
                if (sid.isNotEmpty()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching {
                            ChatSummaryService.pollAndUpdate(
                                callId = callIdStr,
                                sessionId = sid,
                                preferences = prefs,
                                repository = repo,
                                appContext = deps.appContext,
                            )
                        }.onFailure { Log.w(TAG, "summary poll: ${it.message}") }
                    }
                }
                val record = repo.getById(callIdStr, lang)
                withContext(Dispatchers.Main) {
                    if (isHandoff) {
                        _toastMessage.value = if (lang == Language.Zh) {
                            "已转交真人接听（系统通话接管）"
                        } else {
                            "Handed off to phone (human takeover)."
                        }
                    }
                    _finishedCallRecord.value = record
                    _status.value = CallSessionStatus.Ended
                }
            }
            call?.let { LiveCallWsSessionHolder.clear(it) }
        }
    }

    private fun stopRecordingBridgeOnly() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder = null
        LiveCallRecordingBridge.onTtsOpus = null
    }

    fun setToast(message: String?) {
        _toastMessage.value = message
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    override fun onLiveTtsStart() {
        if (_status.value == CallSessionStatus.Ended) return
        ttsStreamBuffer.flushAndReset()
        _ttsStreamingLoading.value = true
    }

    override fun onLiveTtsSentenceStart(text: String) {
        if (_status.value == CallSessionStatus.Ended) return
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        ttsStreamBuffer.append(cleaned)
    }

    override fun onLiveTtsStop() {
        if (_status.value == CallSessionStatus.Ended) return
        ttsStreamBuffer.markDone()
    }

    override fun onLiveSttText(text: String) {
        if (_status.value == CallSessionStatus.Ended) return
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        addMessage(MessageSender.Caller, cleaned)
    }

    private fun resetLiveTtsStreaming() {
        ttsStreamBuffer.reset()
        _ttsStreamingText.value = ""
        _ttsStreamingLoading.value = false
        liveOutboundTaskId = null
    }

    private fun startDurationCounter() {
        durationJob?.cancel()
        durationJob = viewModelScope.launch {
            while (_status.value == CallSessionStatus.Connected) {
                delay(1000)
                _durationSeconds.value = _durationSeconds.value + 1
            }
        }
    }

    fun addMessage(sender: MessageSender, text: String, isStreaming: Boolean = false) {
        _messages.value = _messages.value + TranscriptMessage(
            id = ++messageIdCounter,
            sender = sender,
            text = text,
            isStreaming = isStreaming
        )
    }

    override fun onCleared() {
        LiveCallSessionRegistry.unregister(this)
        durationJob?.cancel()
        sttAiHangupJob?.cancel()
        ttsStreamBuffer.reset()
        stopRecordingBridgeOnly()
        super.onCleared()
    }
}
