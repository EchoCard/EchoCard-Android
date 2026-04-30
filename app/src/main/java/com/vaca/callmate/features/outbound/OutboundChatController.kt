package com.vaca.callmate.features.outbound

import android.content.Context
import android.util.Log
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.network.BackendAuthManager
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.ChatMessage
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.MessageSender
import com.vaca.callmate.data.local.OutboundPromptTemplateEntity
import com.vaca.callmate.data.outbound.OutboundContact
import com.vaca.callmate.data.outbound.OutboundCreateTaskSubmission
import com.vaca.callmate.data.outbound.OutboundTask
import com.vaca.callmate.data.outbound.OutboundDialRiskControl
import com.vaca.callmate.data.outbound.OutboundTaskJsonStore
import com.vaca.callmate.data.outbound.OutboundTaskStatus
import com.vaca.callmate.data.ProcessStrategyChange
import com.vaca.callmate.data.ProcessStrategyStore
import com.vaca.callmate.data.repository.OutboundRepository
import com.vaca.callmate.data.repository.OutboundTemplateLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import com.vaca.callmate.BuildConfig
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.jvm.Volatile

private const val TAG = "OutboundChatWS"
private val WS_URL get() = BuildConfig.WS_BASE_URL
/** 与 iOS `WebSocketService.prepareConnectResult` 中 `Client-Id` 字段一致（服务端按同一协议处理） */
private const val WS_CLIENT_ID = "CallMate-iOS"

/**
 * 与 iOS `WebSocketScene` 对齐：`outbound_chat`、`init_config`、`update_config`、`evaluation`、`call_outbound`（真实外呼通话）。
 */
enum class ManualWsScene {
    OUTBOUND_CHAT,
    /** 对标 iOS `WebSocketScene.initConfig`：AI 配置向导 */
    INIT_CONFIG,
    AI_AVATAR_UPDATE_CONFIG,
    EVALUATION,
    /** 真实外呼通话场景（与 iOS `WebSocketScene.callOutbound` 一致） */
    CALL_OUTBOUND,
    ;

    val serverScene: String
        get() = when (this) {
            OUTBOUND_CHAT -> "outbound_chat"
            INIT_CONFIG -> "init_config"
            AI_AVATAR_UPDATE_CONFIG -> "update_config"
            EVALUATION -> "evaluation"
            CALL_OUTBOUND -> "call_outbound"
        }
}

/** update_config v1：在 connect IO 阶段预取，供 [OutboundChatController.sendHello] 使用 */
private data class UpdateConfigHelloExtras(
    val greeting: String?,
    val strategyManifest: JSONArray?,
    /** null = 不传 `templateManifest` 键；非 null = 已开通外呼灰度（可为空数组） */
    val templateManifest: JSONArray?,
)
private const val PROTOCOL_VERSION = "1"

enum class OutboundChatConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Error
}

/**
 * 对标 iOS `WebSocketService` scene=outbound_chat + `FeedbackChatModalView` 工具处理。
 * 文本上行/下行与 tool_response 对齐；TTS 二进制下行暂不播放。
 */
class OutboundChatController(
    private val context: Context,
    private val bleManager: BleManager,
    private val preferences: AppPreferences,
    private val outboundRepository: OutboundRepository,
    private val queueService: OutboundTaskQueueService,
    private val language: Language,
    private val wsScene: ManualWsScene = ManualWsScene.OUTBOUND_CHAT,
    /** 与 iOS `FeedbackChatModalView.initMessagesOverride` 一致，供 `sessionInitMessages` 式合并 */
    private val avatarInitMessagesSeed: List<Pair<String, String>>? = null,
    /** 与 iOS `OnboardingView` `onboardingInitMessages` + hello `initiate.messages` 对齐（仅 scene=init_config） */
    private val initConfigMessagesSeed: List<Pair<String, String>>? = null,
    /** 与 iOS `CallDetailView.feedbackInitMessages` + hello `initiate.messages` 对齐 */
    private val evaluationInitMessagesSeed: List<Pair<String, String>>? = null,
    /** 与 iOS `evaluationChatHistory` → `template_vars.chatHistory` 对齐 */
    private val evaluationTranscriptTemplate: List<Pair<String, String>>? = null,
    /** 与 iOS `WebSocketService.connect(..., autoPlayIntro:)` → hello `initiate.auto_play_intro` 对齐 */
    private val autoPlayIntro: Boolean = false,
) {
    /** 与 iOS `WebSocketService` 内存 `bluetoothId` 一致：进程内随机，不随 DataStore 持久化 */
    private val wsBluetoothId: String = UUID.randomUUID().toString()

    /** 供声音克隆上报等与 WS hello 使用同一 `Client-Id` 语义侧的设备侧 ID */
    val reportBluetoothId: String get() = wsBluetoothId

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 防止两次 [start] 并发进入 [connectInner]（状态仍为 Disconnected 时即发起 IO，曾导致双 WebSocket、双 hello、服务端 1005） */
    private val connectMutex = Mutex()

    private val connectAttemptSeq = AtomicInteger(0)
    private val ttsPlayer = WsTtsAudioPlayer(scope)
    private var socket: WebSocket? = null
    private var sessionId: String? = null
    /** OkHttp [WebSocketListener.onOpen] 可能在非 Main 线程先发 hello，与下行 ack 并发读写；volatile 对齐内存可见性 */
    @Volatile
    private var helloAcked = false
    private var helloRetryJob: Job? = null
    private var lastUserSentAt = 0L

    /** 对标 iOS `CallSessionController`：按住说话 / 断连重连 */
    private var manualListenActive = false
    private var wsListeningStarted = false
    private var manualReconnectPending = false
    private var manualReconnectInFlight = false
    private var manualListenStartJob: Job? = null
    private var manualListenRecorder: WsManualListenRecorder? = null
    private var lastManualToggleAt = 0L
    private var manualLastRecordingStopAt = 0L

    /** 与 iOS `WebSocketService` 一致：不启用 OkHttp 的 RFC ping/pong 周期（避免 20s 无 pong 超时）；由业务层保活。 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _connectionState = MutableStateFlow(OutboundChatConnectionState.Disconnected)
    val connectionState: StateFlow<OutboundChatConnectionState> = _connectionState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 对标 iOS `TTSStreamingBubbleState`：流式气泡文案 */
    private val _ttsStreamingText = MutableStateFlow("")
    val ttsStreamingText: StateFlow<String> = _ttsStreamingText.asStateFlow()
    /** 对标 iOS：等待 AI 首字前显示 loading 三点 */
    private val _ttsStreamingLoading = MutableStateFlow(false)
    val ttsStreamingLoading: StateFlow<Boolean> = _ttsStreamingLoading.asStateFlow()

    /** 对标 iOS `CallSessionController.ttsStopCount`（用于 init_config 首段 TTS 后插入授权卡片） */
    private val _ttsStopCount = MutableStateFlow(0)
    val ttsStopCount: StateFlow<Int> = _ttsStopCount.asStateFlow()
    private var didInsertAuthCard = false

    private val _ruleChangesByCallId = MutableStateFlow<Map<String, RuleChangeRequest>>(emptyMap())
    val ruleChangesByCallId: StateFlow<Map<String, RuleChangeRequest>> = _ruleChangesByCallId.asStateFlow()

    /** 非 init_config：待用户在 UI 确认/取消的规则变更（与 iOS `FeedbackChatModalView` proposal 对齐） */
    private val _pendingRuleChangeForConfirm = MutableStateFlow<RuleChangeRequest?>(null)
    val pendingRuleChangeForConfirm: StateFlow<RuleChangeRequest?> = _pendingRuleChangeForConfirm.asStateFlow()

    private val _voiceCloneGuideCallId = MutableStateFlow<String?>(null)
    val voiceCloneGuideCallId: StateFlow<String?> = _voiceCloneGuideCallId.asStateFlow()

    private val _guideImageCaptionByImageId = MutableStateFlow<Map<String, String?>>(emptyMap())
    val guideImageCaptionByImageId: StateFlow<Map<String, String?>> = _guideImageCaptionByImageId.asStateFlow()

    /**
     * 与 iOS `FeedbackChatModalView.messages[].outboundConfirmationData` 对齐：按 `callId` 保存内嵌外呼确认卡状态，
     * 由 [com.vaca.callmate.ui.screens.FeedbackChatModal] 通过 `__outbound_confirm__:$callId` 哨兵消息检索渲染。
     */
    private val _outboundConfirmCards = MutableStateFlow<Map<String, OutboundConfirmCardState>>(emptyMap())
    val outboundConfirmCards: StateFlow<Map<String, OutboundConfirmCardState>> = _outboundConfirmCards.asStateFlow()

    val manualWsScene: ManualWsScene get() = wsScene

    private lateinit var ttsStreamBuffer: TtsCharacterStreamBuffer

    /** 与 iOS AI 分身页扬声器开关对齐：true 时不播放 TTS 二进制 */
    fun setTtsMuted(muted: Boolean) {
        ttsPlayer.muted = muted
    }

    init {
        _messages.value = when {
            wsScene == ManualWsScene.EVALUATION && !evaluationInitMessagesSeed.isNullOrEmpty() ->
                chatMessagesFromEvaluationSeed(evaluationInitMessagesSeed!!)
            wsScene == ManualWsScene.INIT_CONFIG -> emptyList()
            else -> listOf(welcomeMessage())
        }
        ttsStreamBuffer = TtsCharacterStreamBuffer(
            scope = scope,
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
                append(ChatMessage(System.nanoTime(), MessageSender.Ai, finalText))
            }
        )
    }

    private fun welcomeMessage(): ChatMessage {
        val text = when (wsScene) {
            ManualWsScene.OUTBOUND_CHAT -> if (language == Language.Zh) {
                "你好，我是 AI 外呼助手。我可以帮你创建话术模板，或给某个号码发起 AI 外呼。你想做什么？"
            } else {
                "Hi, I'm your AI outbound assistant. I can help create call templates or initiate an AI call. What would you like to do?"
            }
            ManualWsScene.AI_AVATAR_UPDATE_CONFIG -> if (language == Language.Zh) {
                "你好，我是你的专属AI分身。你可以直接告诉我需要查询的数据，或者想调整的接听策略。"
            } else {
                "Hi, I'm your AI personal secretary. Ask me anything or adjust call strategy."
            }
            ManualWsScene.EVALUATION -> if (language == Language.Zh) {
                "你好，我是通话反馈助手。"
            } else {
                "Hi, I'm here to collect your call feedback."
            }
            ManualWsScene.INIT_CONFIG -> if (language == Language.Zh) {
                "你好，我是 AI 配置向导。"
            } else {
                "Hi, I'm your AI setup wizard."
            }
            ManualWsScene.CALL_OUTBOUND -> if (language == Language.Zh) {
                "AI 外呼已接通，正在执行任务。"
            } else {
                "Outbound call connected, executing task."
            }
        }
        return ChatMessage(1L, MessageSender.Ai, text, isAudio = true, duration = 3)
    }

    private fun chatMessagesFromEvaluationSeed(seed: List<Pair<String, String>>): List<ChatMessage> {
        var id = 1L
        return seed.map { (role, text) ->
            val sender = when (role.lowercase()) {
                "assistant", "ai" -> MessageSender.Ai
                "user" -> MessageSender.User
                else -> MessageSender.User
            }
            val audio = sender == MessageSender.Ai
            ChatMessage(
                id++,
                sender,
                text,
                isAudio = audio,
                duration = if (audio) 3 else null,
            )
        }
    }

    fun start() {
        val inv = connectAttemptSeq.incrementAndGet()
        Log.i(
            TAG,
            "[connect] start() inv=$inv scene=${wsScene.serverScene} thread=${Thread.currentThread().name} " +
                "state=${_connectionState.value} socketNull=${socket == null} helloAcked=$helloAcked"
        )
        scope.launch(Dispatchers.IO) {
            if (_connectionState.value == OutboundChatConnectionState.Connecting) {
                Log.i(TAG, "[connect] start() inv=$inv ignored: already Connecting")
                return@launch
            }
            connectInner(inv)
        }
    }

    fun stop() {
        Log.i(
            TAG,
            "[connect] stop() scene=${wsScene.serverScene} state=${_connectionState.value} " +
                "socketNull=${socket == null}"
        )
        manualListenStartJob?.cancel()
        manualListenStartJob = null
        manualListenRecorder?.stop()
        manualListenRecorder = null
        manualListenActive = false
        wsListeningStarted = false
        manualReconnectPending = false
        manualReconnectInFlight = false
        helloRetryJob?.cancel()
        helloRetryJob = null
        helloAcked = false
        sessionId = null
        if (::ttsStreamBuffer.isInitialized) {
            ttsStreamBuffer.reset()
        }
        _ttsStreamingText.value = ""
        _ttsStreamingLoading.value = false
        _ttsStopCount.value = 0
        didInsertAuthCard = false
        _ruleChangesByCallId.value = emptyMap()
        _pendingRuleChangeForConfirm.value = null
        _voiceCloneGuideCallId.value = null
        _guideImageCaptionByImageId.value = emptyMap()
        ttsPlayer.release()
        socket?.close(1000, null)
        socket = null
        _connectionState.value = OutboundChatConnectionState.Disconnected
    }

    private suspend fun connectInner(inv: Int) {
        if (!connectMutex.tryLock()) {
            Log.w(
                TAG,
                "[diag] connectInner inv=$inv SKIPPED: mutex busy (concurrent start — 若出现请检查 UI 是否重复调用 start)"
            )
            return
        }
        try {
            connectInnerLocked(inv)
        } finally {
            connectMutex.unlock()
        }
    }

    private suspend fun connectInnerLocked(inv: Int) {
        Log.i(
            TAG,
            "[connect] connectInner() begin inv=$inv scene=${wsScene.serverScene} thread=${Thread.currentThread().name}"
        )
        // 已握手则勿重复建连（sessionId 可能为 null，与 iOS 一致，仍视为同一会话）
        if (socket != null && helloAcked && _connectionState.value == OutboundChatConnectionState.Connected) {
            Log.i(TAG, "[connect] connectInner() inv=$inv early exit: already Connected+helloAcked")
            return
        }
        // 正在建连且已有 socket，避免重复 newWebSocket
        if (socket != null && _connectionState.value == OutboundChatConnectionState.Connecting) {
            Log.i(TAG, "[connect] connectInner() inv=$inv early exit: socket exists and Connecting")
            return
        }
        val deviceId = bleManager.runtimeMCUDeviceID.value?.trim().orEmpty()
        val connectedAddrBle = bleManager.connectedAddress.value
        val connected = !connectedAddrBle.isNullOrBlank()
        val connectingAddr = bleManager.connectingAddress.value
        val bleReady = bleManager.isReady.value
        val bleCtrlReady = bleManager.isCtrlReady.value
        Log.i(
            TAG,
            "[connect] ble snapshot: connectedAddr=${connectedAddrBle ?: "null"} " +
                "connectingAddr=${connectingAddr ?: "null"} isReady=$bleReady isCtrlReady=$bleCtrlReady " +
                "deviceIdLen=${deviceId.length} connectedFlag=$connected"
        )
        if (!connected || deviceId.isEmpty()) {
            Log.w(
                TAG,
                "[connect] ABORT: MCU gate failed — connected=$connected deviceIdEmpty=${deviceId.isEmpty()} " +
                    "(showing system message)"
            )
            withContext(Dispatchers.Main) {
                _connectionState.value = OutboundChatConnectionState.Error
                clearManualReconnectFlags()
                appendSystem(
                    if (language == Language.Zh) {
                        "设备未连接或无法获取设备 ID。"
                    } else {
                        "MCU is not connected or device-id is unavailable."
                    }
                )
            }
            return
        }

        val token = BackendAuthManager.ensureToken(preferences)
        if (token == null || !BackendAuthManager.looksLikeJWT(token)) {
            Log.w(TAG, "[connect] ABORT: token missing or not JWT-like")
            withContext(Dispatchers.Main) {
                _connectionState.value = OutboundChatConnectionState.Error
                clearManualReconnectFlags()
                appendSystem(
                    if (language == Language.Zh) "无法连接 WebSocket：缺少登录凭证。"
                    else "Token missing; cannot connect WebSocket."
                )
            }
            return
        }

        BackendAuthManager.reportDevice(preferences, deviceId, wsBluetoothId, token)

        val processStrategyJson = ProcessStrategyStore.processStrategyJSONString(context)
        val sendInitConfigPrompt = preferences.initConfigSendPromptEnabledFlow.first()
        val sendAvatarPromptEval = preferences.avatarSendPromptEnabledFlow.first()
        val promptForHello: String = when (wsScene) {
            ManualWsScene.OUTBOUND_CHAT -> loadScenePrompt().orEmpty()
            ManualWsScene.INIT_CONFIG ->
                if (sendInitConfigPrompt) loadScenePrompt().orEmpty() else ""
            ManualWsScene.AI_AVATAR_UPDATE_CONFIG ->
                // v1：update_config 完全服务端托管 prompt，不再下发本地 assets
                ""
            ManualWsScene.EVALUATION ->
                if (sendAvatarPromptEval) loadEvaluationPrompt().orEmpty() else ""
            ManualWsScene.CALL_OUTBOUND -> ""
        }
        val updateConfigHelloExtras: UpdateConfigHelloExtras? =
            if (wsScene == ManualWsScene.AI_AVATAR_UPDATE_CONFIG) {
                val greeting = preferences.userGreetingFlow.first().trim().takeIf { it.isNotEmpty() }
                val strategyManifest = ProcessStrategyStore.strategyManifestJsonArray(context)
                val outboundEntitled = preferences.aiCallsTotalFlow.first() > 0
                val templateManifest: JSONArray? = if (outboundEntitled) {
                    outboundRepository.buildTemplateManifestJsonArray()
                } else {
                    null
                }
                UpdateConfigHelloExtras(
                    greeting = greeting,
                    strategyManifest = strategyManifest,
                    templateManifest = templateManifest,
                )
            } else {
                null
            }
        Log.i(
            TAG,
            "[diag] hello prep inv=$inv scene=${wsScene.serverScene} sendInitConfigPrompt=$sendInitConfigPrompt " +
                "sendAvatarPromptEval=$sendAvatarPromptEval promptLen=${promptForHello.length} " +
                "strategyJsonLen=${processStrategyJson?.length ?: 0} seedInitConfig=${initConfigMessagesSeed != null}"
        )

        if (wsScene == ManualWsScene.OUTBOUND_CHAT && promptForHello.isBlank()) {
            Log.w(TAG, "[connect] ABORT: outbound_chat prompt empty")
            withContext(Dispatchers.Main) {
                _connectionState.value = OutboundChatConnectionState.Error
                clearManualReconnectFlags()
                appendSystem(
                    if (language == Language.Zh) "缺少场景提示词资源（prompt 文件）。"
                    else "Missing prompt asset for this scene."
                )
            }
            return
        }

        val appellation = preferences.userAppellationFlow.first().trim().takeIf { it.isNotEmpty() }
        val phoneIdHeader = sha256Hex("config_scene")
        val req = Request.Builder()
            .url(WS_URL)
            .header("Device-Id", deviceId)
            .header("Client-Id", WS_CLIENT_ID)
            .header("Protocol-Version", PROTOCOL_VERSION)
            .header("phone_id", phoneIdHeader)
            .header("Authorization", "Bearer $token")
            .build()

        val reqHeaders = buildString {
            for (i in 0 until req.headers.size) {
                val name = req.headers.name(i)
                val value = req.headers.value(i)
                val display = if (name.equals("Authorization", ignoreCase = true)) {
                    value.take(24) + "…(len=${value.length})"
                } else {
                    value
                }
                append("  $name: $display\n")
            }
        }
        Log.i(
            TAG,
            "[connect] opening WebSocket inv=$inv url=$WS_URL scene=${wsScene.serverScene} " +
                "deviceId=$deviceId phone_id_prefix=${phoneIdHeader.take(8)}…\n" +
                "[connect] request headers:\n$reqHeaders"
        )

        withContext(Dispatchers.Main) {
            _connectionState.value = OutboundChatConnectionState.Connecting
            _lastError.value = null
        }

        withContext(Dispatchers.Main) {
            var openAtNanos = 0L
            var textMsgCount = 0
            var binaryMsgCount = 0

            socket = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openAtNanos = System.nanoTime()
                    val hdrSample = buildString {
                        var n = 0
                        for (i in 0 until response.headers.size) {
                            val line = "${response.headers.name(i)}: ${response.headers.value(i)}"
                            append(line)
                            append(" | ")
                            n += line.length
                            if (n > 1800) {
                                append("…")
                                break
                            }
                        }
                    }
                    Log.i(
                        TAG,
                        "[connect] WebSocket onOpen inv=$inv code=${response.code} scene=${wsScene.serverScene} " +
                            "protocol=${response.protocol} wsHash=${System.identityHashCode(webSocket)} " +
                            "hdrSample=$hdrSample"
                    )
                    helloRetryJob?.cancel()
                    helloAcked = false
                    sendHello(promptForHello, appellation, processStrategyJson, updateConfigHelloExtras, webSocket)
                    scope.launch(Dispatchers.Main) {
                        scheduleHelloRetriesRemaining(
                            promptForHello,
                            appellation,
                            processStrategyJson,
                            updateConfigHelloExtras,
                        )
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    textMsgCount++
                    Log.i(TAG, "[ws-rx] text #$textMsgCount len=${text.length} preview=${text.take(500)}")
                    scope.launch(Dispatchers.Main) { handleTextMessage(text) }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    binaryMsgCount++
                    if (binaryMsgCount <= 3) {
                        Log.i(TAG, "[ws-rx] binary #$binaryMsgCount size=${bytes.size}")
                    }
                    ttsPlayer.playOpusFrame(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    val cur = socket
                    val elapsedMs = if (openAtNanos > 0) (System.nanoTime() - openAtNanos) / 1_000_000 else -1
                    Log.w(
                        TAG,
                        "[connect] WebSocket onClosing inv=$inv code=$code reason='$reason' scene=${wsScene.serverScene} " +
                            "wsIsCurrent=${webSocket === cur} wsHash=${System.identityHashCode(webSocket)} " +
                            "elapsedSinceOpenMs=$elapsedMs textMsgRx=$textMsgCount binaryMsgRx=$binaryMsgCount " +
                            "helloAcked=$helloAcked"
                    )
                    scope.launch(Dispatchers.Main) {
                        helloRetryJob?.cancel()
                        stopManualListenDueToSocketLoss()
                        if (_connectionState.value == OutboundChatConnectionState.Connected) {
                            _connectionState.value = OutboundChatConnectionState.Disconnected
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val elapsedMs = if (openAtNanos > 0) (System.nanoTime() - openAtNanos) / 1_000_000 else -1
                    Log.e(
                        TAG,
                        "[connect] WebSocket onFailure inv=$inv scene=${wsScene.serverScene} " +
                            "http=${response?.code} msg=${t.message} wsHash=${System.identityHashCode(webSocket)} " +
                            "elapsedSinceOpenMs=$elapsedMs textMsgRx=$textMsgCount binaryMsgRx=$binaryMsgCount",
                        t
                    )
                    scope.launch(Dispatchers.Main) {
                        helloRetryJob?.cancel()
                        stopManualListenDueToSocketLoss()
                        clearManualReconnectFlags()
                        _ttsStreamingLoading.value = false
                        _connectionState.value = OutboundChatConnectionState.Error
                        _lastError.value = t.message
                        appendSystem(t.message ?: "WebSocket error")
                    }
                }
            })
        }
    }

    /** 首次 hello 已在 [WebSocketListener.onOpen] 同步发出；此处仅第 2、3 次重试（与 iOS 共 3 次尝试）。 */
    private fun scheduleHelloRetriesRemaining(
        prompt: String,
        appellation: String?,
        processStrategyJson: String?,
        updateConfigHelloExtras: UpdateConfigHelloExtras?,
    ) {
        helloRetryJob = scope.launch(Dispatchers.Main) {
            repeat(2) {
                delay(1000)
                if (helloAcked) return@launch
                sendHello(prompt, appellation, processStrategyJson, updateConfigHelloExtras, null)
            }
            if (!helloAcked) {
                Log.w(TAG, "[connect] hello retry exhausted (3x) scene=${wsScene.serverScene} — Error+stop")
                _connectionState.value = OutboundChatConnectionState.Error
                appendSystem(
                    if (language == Language.Zh) "握手超时，请重试。" else "Handshake timed out."
                )
                stop()
            }
        }
    }

    private fun sendHello(
        prompt: String,
        appellation: String?,
        processStrategyJson: String?,
        updateConfigHelloExtras: UpdateConfigHelloExtras? = null,
        wsOverride: WebSocket? = null,
    ) {
        val ws = wsOverride ?: socket ?: return
        val audioParams = JSONObject()
            .put("sample_rate", 16000)
            .put("channels", 1)
            .put("format", "opus")
            .put("frame_duration", 60)
        val initiate = JSONObject()
            .put("scene", wsScene.serverScene)
        val isUpdateConfigV1 = wsScene == ManualWsScene.AI_AVATAR_UPDATE_CONFIG
        if (!isUpdateConfigV1 && prompt.isNotBlank()) {
            initiate.put("prompt", prompt)
        }
        val templateVars = JSONObject()
        templateVars.put("languageName", if (language == Language.Zh) "中文" else "English")
        if (!appellation.isNullOrBlank()) {
            templateVars.put("appellation", appellation)
        }
        if (isUpdateConfigV1) {
            val ex = updateConfigHelloExtras
                ?: UpdateConfigHelloExtras(greeting = null, strategyManifest = null, templateManifest = null)
            if (!ex.greeting.isNullOrBlank()) {
                templateVars.put("greeting", ex.greeting)
            }
            ex.strategyManifest?.let { templateVars.put("strategyManifest", it) }
            if (ex.templateManifest != null) {
                templateVars.put("templateManifest", ex.templateManifest)
            }
        } else {
            if (!processStrategyJson.isNullOrBlank()) {
                templateVars.put("processStrategy", processStrategyJson)
            }
        }
        if (wsScene == ManualWsScene.EVALUATION && !evaluationTranscriptTemplate.isNullOrEmpty()) {
            buildChatHistoryTemplateJson(evaluationTranscriptTemplate)?.let { templateVars.put("chatHistory", it) }
        }
        if (templateVars.length() > 0) {
            initiate.put("template_vars", templateVars)
        }
        if (autoPlayIntro) {
            initiate.put("auto_play_intro", true)
        }
        // 与 iOS：outbound_chat 不传 messages；update_config 用 sessionInitMessages 合并结果
        if (wsScene != ManualWsScene.OUTBOUND_CHAT) {
            buildInitMessagesJsonForHello()?.let { initiate.put("messages", it) }
        }
        val hello = JSONObject()
            .put("type", "hello")
            .put("audio_params", audioParams)
            .put("initiate", initiate)
        val helloStr = hello.toString()
        ws.send(helloStr)

        val prettyHello = try {
            hello.toString(2)
        } catch (_: Exception) {
            helloStr
        }
        val clipped = if (prettyHello.length > 6000) prettyHello.take(6000) + "\n…(truncated)" else prettyHello
        Log.i(TAG, "[diag] hello payload (${helloStr.length} bytes):\n$clipped")
        Log.i(
            TAG,
            "[diag] hello sent scene=${wsScene.serverScene} bytes=${helloStr.length} " +
                "initiateHasPrompt=${initiate.has("prompt")} initiateHasMessages=${initiate.has("messages")} " +
                "initiateAutoPlayIntro=${initiate.opt("auto_play_intro")} " +
                "wsHash=${System.identityHashCode(ws)} thread=${Thread.currentThread().name} " +
                "syncOnOpen=${wsOverride != null}"
        )
    }

    /** 对标 iOS `FeedbackChatModalView.sessionInitMessages()`：最近 8 条与 seed 合并 */
    private fun buildInitMessagesJsonForHello(): JSONArray? {
        val seed: List<Pair<String, String>>? = when (wsScene) {
            ManualWsScene.INIT_CONFIG -> initConfigMessagesSeed
            ManualWsScene.AI_AVATAR_UPDATE_CONFIG -> avatarInitMessagesSeed
            ManualWsScene.EVALUATION -> evaluationInitMessagesSeed
            else -> return null
        }
        val rows = _messages.value
            .asSequence()
            .filter { it.sender == MessageSender.User || it.sender == MessageSender.Ai }
            .mapNotNull { m ->
                val c = m.text.trim()
                if (c.isEmpty()) return@mapNotNull null
                val role = when (m.sender) {
                    MessageSender.User -> "user"
                    MessageSender.Ai -> "assistant"
                    else -> return@mapNotNull null
                }
                role to c
            }
            .toList()
            .takeLast(8)
        val merged: List<Pair<String, String>> = when {
            seed != null && seed.isNotEmpty() && rows.size <= seed.size -> seed
            seed != null && seed.isNotEmpty() && rows.size > seed.size -> seed + rows.drop(seed.size)
            rows.isNotEmpty() -> rows
            else -> return null
        }
        val arr = JSONArray()
        for ((role, content) in merged) {
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        return arr
    }

    /** 与 iOS `WebSocketService.buildChatHistoryTemplateVar` 一致：JSON 字符串写入 template_vars.chatHistory */
    private fun buildChatHistoryTemplateJson(pairs: List<Pair<String, String>>): String? {
        if (pairs.isEmpty()) return null
        val arr = JSONArray()
        for ((roleRaw, contentRaw) in pairs) {
            val content = contentRaw.trim()
            if (content.isEmpty()) continue
            val role = when (roleRaw.lowercase()) {
                "assistant", "ai" -> "assistant"
                "other", "caller" -> "other"
                else -> "user"
            }
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        if (arr.length() == 0) return null
        return arr.toString()
    }

    fun sendUserText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val sid = sessionId
        // 与 iOS `WebSocketService.sendListenText`：`guard let sid = sessionId else { return }`
        if (sid == null || !helloAcked) return
        lastUserSentAt = System.currentTimeMillis()
        appendUser(t)
        // 与 iOS FeedbackChatModalView：用户气泡出现后显示「等待首字」loading
        _ttsStreamingLoading.value = true
        val msg = JSONObject()
            .put("session_id", sid)
            .put("type", "listen")
            .put("state", "text")
            .put("text", t)
        socket?.send(msg.toString())
    }

    /**
     * 对标 iOS `CallSessionController.beginManualListen`：按住说话开始（含断连则先重连 + hello）。
     */
    fun beginManualListen() {
        if (!supportsManualListen()) return
        manualListenActive = true
        val wsReady = helloAcked && sessionId != null &&
            _connectionState.value == OutboundChatConnectionState.Connected
        if (!wsReady) {
            reconnectWebSocketForManualListenIfNeeded()
            return
        }
        if (wsListeningStarted) return
        val now = System.currentTimeMillis()
        if (now - lastManualToggleAt < 100) return
        lastManualToggleAt = now

        ttsPlayer.onTtsStop()
        sendAbort()
        sendListenStart()
        wsListeningStarted = true
        Log.i(TAG, "manual listen start")

        manualListenStartJob?.cancel()
        manualListenStartJob = scope.launch(Dispatchers.Main) {
            delay(150)
            if (!manualListenActive) return@launch
            if (!wsListeningStarted) return@launch
            val lastStop = manualLastRecordingStopAt
            if (lastStop > 0) {
                val dt = System.currentTimeMillis() - lastStop
                if (dt < 250) delay(250 - dt)
            }
            if (!manualListenActive || !wsListeningStarted) return@launch
            ttsPlayer.onTtsStop()
            startManualRecordingPipeline()
        }
    }

    /**
     * 对标 iOS `CallSessionController.endManualListen`：松手发送路径。
     */
    fun endManualListen() {
        if (!supportsManualListen()) return
        manualListenActive = false
        manualReconnectPending = false
        manualListenStartJob?.cancel()
        manualListenStartJob = null
        if (!wsListeningStarted) return
        lastManualToggleAt = System.currentTimeMillis()
        sendListenStop()
        wsListeningStarted = false
        manualListenRecorder?.stop()
        manualListenRecorder = null
        manualLastRecordingStopAt = System.currentTimeMillis()
        Log.i(TAG, "manual listen stop")
    }

    /**
     * 对标 iOS `CallSessionController.cancelManualListen`：上移取消 = UI 本地取消，**不发 listen_stop**。
     *
     * 之前错误地调用 [sendListenStop]，服务端把它当正常结束触发 LLM 推理 + TTS 回复，
     * 用户明明红色松开想取消，却仍然收到 AI 回答 —— 对照 iOS 注释：
     * "Keep cancel path independent from endManualListen() so we don't send listen_stop."
     */
    fun cancelManualListen() {
        if (!supportsManualListen()) return
        manualListenActive = false
        manualReconnectPending = false
        manualListenStartJob?.cancel()
        manualListenStartJob = null
        if (!wsListeningStarted) return
        wsListeningStarted = false
        manualListenRecorder?.stop()
        manualListenRecorder = null
        manualLastRecordingStopAt = System.currentTimeMillis()
        Log.i(TAG, "manual listen cancel (ui/local only, no ws command)")
    }

    private fun supportsManualListen(): Boolean = true

    private fun clearManualReconnectFlags() {
        manualReconnectInFlight = false
        manualReconnectPending = false
    }

    private fun stopManualListenDueToSocketLoss() {
        manualListenStartJob?.cancel()
        manualListenStartJob = null
        manualListenRecorder?.stop()
        manualListenRecorder = null
        wsListeningStarted = false
    }

    private fun reconnectWebSocketForManualListenIfNeeded() {
        if (!supportsManualListen()) return
        if (manualReconnectInFlight) return
        val wsReady = helloAcked && sessionId != null &&
            _connectionState.value == OutboundChatConnectionState.Connected
        if (wsReady) return

        manualReconnectPending = true
        manualReconnectInFlight = true
        Log.i(TAG, "manual listen: WS not ready, reconnecting…")

        scope.launch(Dispatchers.IO) {
            helloRetryJob?.cancel()
            try {
                socket?.close(1000, null)
            } catch (_: Exception) {
            }
            socket = null
            sessionId = null
            helloAcked = false
            withContext(Dispatchers.Main) {
                _connectionState.value = OutboundChatConnectionState.Connecting
            }
            connectInner(connectAttemptSeq.incrementAndGet())
        }
    }

    private fun sendListenStart() {
        val sid = sessionId ?: return
        val msg = JSONObject()
            .put("session_id", sid)
            .put("type", "listen")
            .put("state", "start")
            .put("mode", "manual")
        socket?.send(msg.toString())
    }

    private fun sendListenStop() {
        val sid = sessionId ?: return
        val msg = JSONObject()
            .put("session_id", sid)
            .put("type", "listen")
            .put("state", "stop")
        socket?.send(msg.toString())
    }

    private fun sendAbort() {
        val sid = sessionId ?: return
        val msg = JSONObject()
            .put("session_id", sid)
            .put("type", "abort")
            .put("reason", "user_interrupt")
        socket?.send(msg.toString())
    }

    private fun startManualRecordingPipeline() {
        if (manualListenRecorder != null) return
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
                }
            )
            rec.start()
            manualListenRecorder = rec
        } catch (e: Exception) {
            Log.e(TAG, "startManualRecordingPipeline", e)
        }
    }

    /**
     * 与 iOS `appendOutboundConfirmationMessage`（`FeedbackChatModalView.swift:1251`）对齐：
     * 把 `initiate_call` / `schedule_call` tool 调用转为聊天内嵌的富卡片，而非系统弹窗。
     *
     * 1. 解析模板拿到 goal / keyPoints（`parseOutboundTemplateSections`）
     * 2. 解析联系人昵称（`contactsFlow.first()`）
     * 3. 写入 [_outboundConfirmCards]，并 `appendSystem` 哨兵消息，由 UI 订阅 + 渲染
     */
    private fun postOutboundConfirmCard(
        callId: String,
        phone: String,
        templateName: String,
        scheduledAtMillis: Long?,
        timeDescription: String?,
    ) {
        scope.launch {
            val entity = outboundRepository.findTemplateByName(templateName)
            val (goal, keyPoints) = if (entity != null) {
                parseOutboundTemplateSections(entity.content)
            } else {
                null to null
            }
            val contactName = resolveContactName(phone) ?: templateName
            val data = OutboundConfirmationData(
                phone = phone,
                contactName = contactName,
                goal = goal,
                keyPoints = keyPoints,
                templateName = templateName,
                scheduledAtMillis = scheduledAtMillis,
                timeDescription = timeDescription?.takeIf { it.isNotBlank() },
            )
            _outboundConfirmCards.value = _outboundConfirmCards.value + (callId to OutboundConfirmCardState(
                callId = callId,
                data = data,
                status = ProposalCardStatus.Pending,
            ))
            appendSystem("$OUTBOUND_CONFIRM_SENTINEL_PREFIX$callId")
        }
    }

    private suspend fun resolveContactName(phone: String): String? {
        val trimmed = phone.trim()
        if (trimmed.isEmpty()) return null
        val all = try { outboundRepository.contactsFlow.first() } catch (_: Exception) { emptyList() }
        val hit = all.firstOrNull { it.phone.trim() == trimmed && it.name.trim().isNotEmpty() }
        return hit?.name
    }

    private fun supportsOutboundToolsScene(): Boolean =
        wsScene == ManualWsScene.OUTBOUND_CHAT || wsScene == ManualWsScene.AI_AVATAR_UPDATE_CONFIG

    private fun hasPendingOutboundConfirmCard(): Boolean =
        _outboundConfirmCards.value.values.any { it.status == ProposalCardStatus.Pending }

    private fun isLikelyOutboundPhone(phone: String): Boolean {
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 7) return false
        if (OutboundDialRiskControl.isEmergencyNumber(phone)) return false
        return true
    }

    private fun formatIso8601OffsetMillis(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date(millis))
    }

    private fun findOutboundScheduleConflict(atMillis: Long): OutboundTask? {
        val windowMs = 10 * 60_000L
        return OutboundTaskJsonStore.load(context).firstOrNull { t ->
            val at = t.scheduledAt ?: return@firstOrNull false
            t.status == OutboundTaskStatus.Scheduled &&
                kotlin.math.abs(at - atMillis) < windowMs
        }
    }

    private fun formatScheduleConflictError(task: OutboundTask): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.CHINA)
        sdf.timeZone = TimeZone.getDefault()
        val center = checkNotNull(task.scheduledAt)
        val start = Date(center - 5 * 60_000L)
        val end = Date(center + 5 * 60_000L)
        return "与已有定时任务「${task.promptType}」冲突（${sdf.format(start)} - ${sdf.format(end)}）"
    }

    /**
     * 由 UI 在用户点击确认/取消后调用。`confirmed=true` 执行外呼入队（按卡片里的 scheduledAt 分立即/定时）；
     * 失败不移除卡片，而是把状态切到 [ProposalCardStatus.Failed] 并记录原因（与 iOS
     * `updateOutboundConfirmationResult` 对齐）。
     */
    fun resolveOutboundConfirmCard(callId: String, confirmed: Boolean, rejectionReason: String? = null) {
        val state = _outboundConfirmCards.value[callId] ?: return
        if (state.status != ProposalCardStatus.Pending) return
        if (!confirmed) {
            _outboundConfirmCards.value = _outboundConfirmCards.value + (callId to state.copy(
                status = ProposalCardStatus.Cancelled,
            ))
            sendToolResponse(
                callId,
                JSONObject().apply {
                    put("success", false)
                    put("action", "cancelled")
                    put("reason", "user_cancelled")
                },
            )
            return
        }
        scope.launch(Dispatchers.IO) {
            val entity = outboundRepository.findTemplateByName(state.data.templateName)
            if (entity == null) {
                withContext(Dispatchers.Main) {
                    _outboundConfirmCards.value = _outboundConfirmCards.value + (callId to state.copy(
                        status = ProposalCardStatus.Failed,
                        failureMessage = "未找到模板「${state.data.templateName}」",
                    ))
                    sendToolResponse(
                        callId,
                        error = "未找到模板「${state.data.templateName}」",
                    )
                }
                return@launch
            }
            val scheduledAt = state.data.scheduledAtMillis
            val id = queueService.createTask(
                OutboundCreateTaskSubmission(
                    promptName = entity.name,
                    promptContent = entity.content,
                    contacts = listOf(
                        OutboundContact(
                            phone = state.data.phone,
                            name = state.data.contactName ?: state.data.phone,
                        ),
                    ),
                    scheduledAtMillis = scheduledAt,
                    status = if (scheduledAt == null) OutboundTaskStatus.Running else OutboundTaskStatus.Scheduled,
                    callFrequency = 30,
                    redialMissed = false,
                ),
            )
            withContext(Dispatchers.Main) {
                val current = _outboundConfirmCards.value[callId] ?: return@withContext
                if (id != null) {
                    _outboundConfirmCards.value = _outboundConfirmCards.value + (callId to current.copy(
                        status = ProposalCardStatus.Applied,
                        failureMessage = null,
                    ))
                    if (scheduledAt != null) {
                        sendToolResponse(
                            callId,
                            JSONObject()
                                .put("success", true)
                                .put("scheduled_at", formatIso8601OffsetMillis(scheduledAt)),
                        )
                    } else {
                        sendToolResponse(
                            callId,
                            JSONObject().put("success", true).put("action", "dialing"),
                        )
                    }
                } else {
                    _outboundConfirmCards.value = _outboundConfirmCards.value + (callId to current.copy(
                        status = ProposalCardStatus.Failed,
                        failureMessage = if (language == Language.Zh) "创建任务失败" else "Failed to create task",
                    ))
                    sendToolResponse(
                        callId,
                        error = if (language == Language.Zh) "创建任务失败" else "Failed to create task",
                    )
                }
            }
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

    private fun handleTextMessage(raw: String) {
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        val type = json.optString("type", "")
        when (type) {
            "hello" -> {
                sessionId = parseHelloSessionId(json)
                helloAcked = true
                helloRetryJob?.cancel()
                helloRetryJob = null
                _connectionState.value = OutboundChatConnectionState.Connected
                manualReconnectInFlight = false
                Log.i(TAG, "hello ack sessionId=$sessionId")
                // 对标 iOS `OnboardingView`：会话已建立、首条 AI TTS 到来前显示流式 loading
                if (wsScene == ManualWsScene.INIT_CONFIG && _messages.value.isEmpty()) {
                    _ttsStreamingLoading.value = true
                }
                if (manualReconnectPending && manualListenActive) {
                    manualReconnectPending = false
                    scope.launch(Dispatchers.Main) {
                        if (manualListenActive) beginManualListen()
                    }
                } else if (manualReconnectPending) {
                    manualReconnectPending = false
                }
            }
            "stt" -> {
                val rawText = json.optString("text", "")
                val display = rawText.replace("✿END✿", "").trim()
                if (display.isEmpty()) return
                val now = System.currentTimeMillis()
                if (now - lastUserSentAt < 800) {
                    val lastUser = _messages.value.lastOrNull { it.sender == MessageSender.User }
                    if (lastUser != null && lastUser.text == display) return
                }
                appendUser(display)
            }
            "tts" -> handleTts(json)
            "filler" -> handleFillerDownlink(json)
            "error" -> {
                _ttsStreamingLoading.value = false
                val msg = json.optString("message", "")
                appendSystem(msg.ifEmpty { "Error" })
            }
            "tool_call" -> dispatchToolCall(json)
            "mcp" -> handleMcp(json)
            else -> Unit
        }
    }

    /**
     * 与 iOS `WebSocketService.swift` commit be506c13 对齐：静默窗口里服务器下发
     * `{type:"filler", id}` → MCU 播预加载好的短应答词。不带 sid、不等 ack。
     */
    private fun handleFillerDownlink(json: JSONObject) {
        val id = json.optString("id", "").trim()
        if (id.isEmpty()) {
            Log.i(TAG, "ws filler ignored: empty id")
            return
        }
        Log.i(TAG, "ws filler forward id=$id")
        val params = JSONObject().put("filler_id", id)
        bleManager.sendCommand("play_filler", params, expectAck = false)
    }

    private fun handleTts(json: JSONObject) {
        val state = json.optString("state", "")
        val rawText = json.optString("text", "")
        when (state) {
            "start" -> {
                // 与 iOS webSocketDidReceiveTTSStart：新 utterance 前先 flush 上一轮流式
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
                // 与 iOS：sentence_end 不入缓冲，避免重复；仅处理挂断标记
                if (rawText.contains("✿END✿")) {
                    Log.i(TAG, "TTS sentence_end hangup marker")
                }
            }
            "stop" -> {
                ttsStreamBuffer.markDone()
                ttsPlayer.onTtsStop()
                if (wsScene == ManualWsScene.INIT_CONFIG) {
                    _ttsStopCount.value = _ttsStopCount.value + 1
                    if (_ttsStopCount.value == 1 && !didInsertAuthCard) {
                        didInsertAuthCard = true
                        appendSystem("__auth_request__")
                    }
                }
            }
            else -> Unit
        }
    }

    private fun handleMcp(json: JSONObject) {
        val payload = json.optJSONObject("payload") ?: return
        val method = payload.optString("method", "")
        if (method != "tools/call") return
        val params = payload.optJSONObject("params") ?: return
        val name = params.optString("name", "")
        if (name.isEmpty()) return
        val callId = payload.optString("id", UUID.randomUUID().toString())
        val rawArgs = params.opt("arguments")
        val arguments = parseToolArguments(rawArgs)
        handleToolCallPayload(callId, name, arguments)
    }

    private fun dispatchToolCall(json: JSONObject) {
        val callId = json.optString("call_id", "")
        val tool = json.optJSONObject("tool") ?: return
        val name = tool.optString("name", "")
        if (callId.isEmpty() || name.isEmpty()) return
        val arguments = parseToolArguments(tool.opt("arguments"))
        handleToolCallPayload(callId, name, arguments)
    }

    private fun handleToolCallPayload(callId: String, name: String, arguments: Map<String, Any>) {
        Log.i(TAG, "tool_call name=$name callId=$callId")
        when (name) {
            "display_rule_change" -> {
                if (::ttsStreamBuffer.isInitialized) {
                    ttsStreamBuffer.flushAndReset()
                }
                val original = arguments.string("original_rule")?.trim().orEmpty()
                val summary = arguments.string("updated_rule_summary")?.trim().orEmpty()
                val items = parseRuleChangeItems(arguments)
                if (items.isEmpty() && original.isEmpty() && summary.isEmpty()) {
                    Log.w(TAG, "display_rule_change missing fields callId=$callId")
                    return
                }
                val request = RuleChangeRequest(
                    id = callId,
                    originalRule = original,
                    updatedRuleSummary = summary,
                    updatedRules = items
                )
                _ruleChangesByCallId.value = _ruleChangesByCallId.value + (callId to request)
                appendSystem("__rule_change__:$callId")
                if (wsScene == ManualWsScene.INIT_CONFIG) {
                    _pendingRuleChangeForConfirm.value = null
                    scope.launch(Dispatchers.IO) {
                        val updates = request.updatedRules.map {
                            ProcessStrategyChange(type = it.type, rule = it.rule, action = it.action)
                        }
                        ProcessStrategyStore.applyChanges(context, updates)
                        withContext(Dispatchers.Main) {
                            sendToolResponse(
                                callId,
                                JSONObject().put("operation", "confirm"),
                            )
                        }
                    }
                } else {
                    _pendingRuleChangeForConfirm.value = request
                }
            }
            "save_user_appellation" -> {
                val raw = arguments["appellation"] as? String
                val app = raw?.trim().orEmpty()
                if (app.isEmpty()) {
                    sendToolResponse(
                        callId,
                        error = if (language == Language.Zh) "称呼为空" else "Appellation empty",
                    )
                    return
                }
                scope.launch {
                    preferences.setUserAppellation(app)
                }
                sendToolResponse(callId, JSONObject().put("success", true))
            }
            "create_template" -> scope.launch(Dispatchers.IO) {
                val templateName = arguments.string("name")
                val templateContent = arguments.string("content")
                if (templateName.isNullOrBlank() || templateContent.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        sendToolResponse(
                            callId,
                            error = if (language == Language.Zh) "模板名称或内容不能为空" else "Name/content required"
                        )
                    }
                    return@launch
                }
                try {
                    val now = System.currentTimeMillis()
                    val saved = outboundRepository.insertOrUpdateTemplate(
                        OutboundPromptTemplateEntity(
                            id = UUID.randomUUID().toString(),
                            name = templateName.trim(),
                            content = templateContent.trim(),
                            createdAtMillis = now,
                            updatedAtMillis = now
                        )
                    )
                    val updatedAtIso = formatIso8601OffsetMillis(saved.updatedAtMillis)
                    withContext(Dispatchers.Main) {
                        sendToolResponse(
                            callId,
                            JSONObject()
                                .put("success", true)
                                .put("name", saved.name)
                                .put("updated_at", updatedAtIso),
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        sendToolResponse(callId, error = e.message ?: "insert failed")
                    }
                }
            }
            "load_rules" -> scope.launch(Dispatchers.IO) {
                val tag = arguments.string("tag")?.trim().orEmpty()
                if (tag.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        sendToolResponse(
                            callId,
                            error = if (language == Language.Zh) "缺少 tag" else "Missing tag",
                        )
                    }
                    return@launch
                }
                val rule = ProcessStrategyStore.getRuleContentForLoadRules(context, tag)
                if (rule == null) {
                    withContext(Dispatchers.Main) {
                        sendToolResponse(callId, error = "规则不存在: $tag")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        sendToolResponse(
                            callId,
                            JSONObject()
                                .put("tag", rule.tag)
                                .put("name", rule.name)
                                .put("content", rule.content),
                        )
                    }
                }
            }
            "load_template" -> scope.launch(Dispatchers.IO) {
                val q = arguments.string("name")?.trim().orEmpty()
                if (q.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        sendToolResponse(
                            callId,
                            error = if (language == Language.Zh) "模板名称不能为空" else "Template name required",
                        )
                    }
                    return@launch
                }
                when (val hit = outboundRepository.lookupTemplateByName(q)) {
                    OutboundTemplateLookup.NotFound -> withContext(Dispatchers.Main) {
                        sendToolResponse(callId, error = "未找到模板「$q」")
                    }
                    is OutboundTemplateLookup.Ambiguous -> withContext(Dispatchers.Main) {
                        val joined = hit.names.joinToString("、")
                        sendToolResponse(callId, error = "找到多个匹配模板：$joined")
                    }
                    is OutboundTemplateLookup.Single -> {
                        val e = hit.entity
                        val taskType = OutboundRepository.inferTaskTypeFromTemplateContent(e.content)
                        withContext(Dispatchers.Main) {
                            sendToolResponse(
                                callId,
                                JSONObject()
                                    .put("name", e.name)
                                    .put("task_type", taskType)
                                    .put("content", e.content),
                            )
                        }
                    }
                }
            }
            "initiate_call" -> {
                if (!supportsOutboundToolsScene()) {
                    sendToolResponse(
                        callId,
                        error = if (language == Language.Zh) "当前场景不支持外呼工具" else "Not available in this scene"
                    )
                    return
                }
                val phone = arguments.string("phone")?.trim().orEmpty()
                val templateName = arguments.string("template_name")?.trim().orEmpty()
                if (phone.isEmpty() || templateName.isEmpty()) {
                    sendToolResponse(
                        callId,
                        error = if (language == Language.Zh) "电话号码或模板名称不能为空" else "Phone/template required"
                    )
                    return
                }
                scope.launch(Dispatchers.IO) {
                    val pending = withContext(Dispatchers.Main) { hasPendingOutboundConfirmCard() }
                    if (pending) {
                        withContext(Dispatchers.Main) {
                            sendToolResponse(callId, error = "已有一通外呼待确认，请先处理")
                        }
                        return@launch
                    }
                    val template = outboundRepository.findTemplateByName(templateName)
                    if (template == null) {
                        withContext(Dispatchers.Main) {
                            sendToolResponse(callId, error = "未找到模板「$templateName」")
                        }
                        return@launch
                    }
                    if (!isLikelyOutboundPhone(phone)) {
                        withContext(Dispatchers.Main) {
                            sendToolResponse(callId, error = "号码格式不正确：$phone")
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        postOutboundConfirmCard(
                            callId,
                            phone,
                            template.name,
                            scheduledAtMillis = null,
                            timeDescription = null,
                        )
                    }
                }
            }
            "schedule_call" -> {
                if (!supportsOutboundToolsScene()) {
                    sendToolResponse(
                        callId,
                        error = if (language == Language.Zh) "当前场景不支持外呼工具" else "Not available in this scene"
                    )
                    return
                }
                val phone = arguments.string("phone")?.trim().orEmpty()
                val templateName = arguments.string("template_name")?.trim().orEmpty()
                val timeDescription = arguments.string("time_description").orEmpty().trim()
                if (phone.isEmpty() || templateName.isEmpty()) {
                    sendToolResponse(
                        callId,
                        error = if (language == Language.Zh) "电话号码或模板名称不能为空" else "Phone/template required"
                    )
                    return
                }
                if (timeDescription.isEmpty()) {
                    sendToolResponse(
                        callId,
                        error = if (language == Language.Zh) "缺少 time_description" else "Missing time_description",
                    )
                    return
                }
                scope.launch(Dispatchers.IO) {
                    val pending = withContext(Dispatchers.Main) { hasPendingOutboundConfirmCard() }
                    if (pending) {
                        withContext(Dispatchers.Main) {
                            sendToolResponse(callId, error = "已有一通外呼待确认，请先处理")
                        }
                        return@launch
                    }
                    val template = outboundRepository.findTemplateByName(templateName)
                    if (template == null) {
                        withContext(Dispatchers.Main) {
                            sendToolResponse(callId, error = "未找到模板「$templateName」")
                        }
                        return@launch
                    }
                    if (!isLikelyOutboundPhone(phone)) {
                        withContext(Dispatchers.Main) {
                            sendToolResponse(callId, error = "号码格式不正确：$phone")
                        }
                        return@launch
                    }
                    val rawIso = arguments.string("scheduled_time")?.trim().orEmpty()
                    val minutesRaw = arguments["minutes_from_now"]
                    val scheduledAt: Long = when {
                        rawIso.isNotEmpty() -> {
                            val parsed = parseIso8601(rawIso)
                            if (parsed == null) {
                                withContext(Dispatchers.Main) {
                                    sendToolResponse(callId, error = "时间格式无法识别：$rawIso")
                                }
                                return@launch
                            }
                            parsed
                        }
                        minutesRaw != null -> {
                            val minutes = (minutesRaw as? Number)?.toInt()
                                ?: (minutesRaw as? String)?.toIntOrNull()
                            if (minutes == null || minutes <= 0) {
                                withContext(Dispatchers.Main) {
                                    sendToolResponse(callId, error = "时间格式无法识别：$minutesRaw")
                                }
                                return@launch
                            }
                            System.currentTimeMillis() + minutes * 60_000L
                        }
                        else -> {
                            withContext(Dispatchers.Main) {
                                sendToolResponse(
                                    callId,
                                    error = if (language == Language.Zh) {
                                        "请提供 scheduled_time 或 minutes_from_now"
                                    } else {
                                        "Provide scheduled_time or minutes_from_now"
                                    },
                                )
                            }
                            return@launch
                        }
                    }
                    if (scheduledAt <= System.currentTimeMillis()) {
                        val label = if (rawIso.isNotEmpty()) rawIso else formatIso8601OffsetMillis(scheduledAt)
                        withContext(Dispatchers.Main) {
                            sendToolResponse(callId, error = "时间已过：$label")
                        }
                        return@launch
                    }
                    findOutboundScheduleConflict(scheduledAt)?.let { conflict ->
                        withContext(Dispatchers.Main) {
                            sendToolResponse(callId, error = formatScheduleConflictError(conflict))
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        postOutboundConfirmCard(
                            callId,
                            phone,
                            template.name,
                            scheduledAtMillis = scheduledAt,
                            timeDescription = timeDescription,
                        )
                    }
                }
            }
            "display_guide_image" -> {
                val imageId = arguments.string("image_id")?.trim().orEmpty()
                if (imageId.isEmpty()) {
                    sendToolResponse(
                        callId,
                        error = if (language == Language.Zh) "缺少 image_id" else "Missing image_id"
                    )
                    return
                }
                val caption = arguments.string("caption")?.trim()?.takeIf { it.isNotEmpty() }
                if (::ttsStreamBuffer.isInitialized) {
                    ttsStreamBuffer.flushAndReset()
                }
                _guideImageCaptionByImageId.value = _guideImageCaptionByImageId.value + (imageId to caption)
                appendSystem("__guide_image__:$imageId")
                sendToolResponse(callId, JSONObject().put("success", true))
            }
            "display_guide_card" -> {
                val cardId = arguments.string("card_id")?.trim().orEmpty()
                if (cardId.isEmpty()) {
                    sendToolResponse(
                        callId,
                        error = if (language == Language.Zh) "缺少 card_id" else "Missing card_id"
                    )
                    return
                }
                if (::ttsStreamBuffer.isInitialized) {
                    ttsStreamBuffer.flushAndReset()
                }
                if (cardId == "clone_start_reading") {
                    _voiceCloneGuideCallId.value = callId
                } else {
                    appendSystem("__guide_card__:${cardId}:$callId")
                }
            }
            else -> Log.w(TAG, "ignored tool: $name")
        }
    }

    private fun parseRuleChangeItems(arguments: Map<String, Any>): List<RuleChangeItem> {
        val raw = arguments["updated_rules"] ?: return emptyList()
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { el ->
            val m = el as? Map<*, *> ?: return@mapNotNull null
            val ms = m.entries.associate { it.key.toString() to it.value }
            val type = ms["type"]?.toString() ?: return@mapNotNull null
            val rule = ms["rule"]?.toString() ?: return@mapNotNull null
            val action = ms["action"]?.toString() ?: return@mapNotNull null
            val id = ms["id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
            RuleChangeItem(id = id, type = type, rule = rule, action = action)
        }
    }

    private fun parseIso8601(raw: String): Long? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.US)
                sdf.isLenient = false
                if (!p.endsWith("XXX")) {
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse(t)?.time?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun sendToolResponse(callId: String, result: JSONObject? = null, error: String? = null) {
        val o = JSONObject()
            .put("type", "tool_response")
            .put("call_id", callId)
        when {
            error != null -> o.put("error", error)
            result != null -> o.put("result", result)
        }
        socket?.send(o.toString())
    }

    /** 与 iOS `sendToolResponse(callId:operation:)` 对齐（非 init_config 场景由 UI 调用） */
    fun sendRuleChangeResponse(callId: String, operation: String) {
        scope.launch(Dispatchers.IO) {
            val req = _ruleChangesByCallId.value[callId]
            if (operation == "confirm" && req != null) {
                val updates = req.updatedRules.map {
                    ProcessStrategyChange(type = it.type, rule = it.rule, action = it.action)
                }
                ProcessStrategyStore.applyChanges(context, updates)
            }
            withContext(Dispatchers.Main) {
                if (operation == "confirm") {
                    sendToolResponse(callId, JSONObject().put("operation", "confirm"))
                } else {
                    sendToolResponse(
                        callId,
                        JSONObject().apply {
                            put("success", false)
                            put("action", "cancelled")
                            put("reason", "user_cancelled")
                            put("operation", "cancel")
                        },
                    )
                }
                if (_pendingRuleChangeForConfirm.value?.id == callId) {
                    _pendingRuleChangeForConfirm.value = null
                }
            }
        }
    }

    /** 与 iOS `respondToGuideCard` 对齐 */
    fun respondToGuideCard(callId: String, accepted: Boolean) {
        if (_voiceCloneGuideCallId.value == callId) {
            _voiceCloneGuideCallId.value = null
        }
        if (accepted) {
            sendToolResponse(callId, JSONObject().put("success", true))
        } else {
            sendToolResponse(callId, error = if (language == Language.Zh) "用户拒绝" else "User declined")
        }
    }

    fun clearVoiceCloneGuideCallId() {
        _voiceCloneGuideCallId.value = null
    }

    /** 与 iOS `sendAuthorizationAcceptedText` → `sendListenText` 对齐 */
    fun sendAuthorizationAcceptedText() {
        val text = if (language == Language.Zh) {
            "我确认授权AI分身帮你处理来电。"
        } else {
            "I confirm authorization for the AI assistant to handle incoming calls for the owner."
        }
        sendUserText(text)
    }

    private fun appendUser(text: String) {
        append(ChatMessage(System.nanoTime(), MessageSender.User, text))
    }

    private fun appendAi(text: String) {
        _ttsStreamingText.value = ""
        _ttsStreamingLoading.value = false
        append(ChatMessage(System.nanoTime(), MessageSender.Ai, text))
    }

    private fun appendSystem(text: String) {
        append(ChatMessage(System.nanoTime(), MessageSender.System, text))
    }

    private fun append(m: ChatMessage) {
        _messages.value = _messages.value + m
    }

    private fun loadScenePrompt(): String? {
        val path = when (wsScene) {
            ManualWsScene.OUTBOUND_CHAT -> "prompts/outbound_call.txt"
            ManualWsScene.INIT_CONFIG -> "prompts/init_config.txt"
            ManualWsScene.AI_AVATAR_UPDATE_CONFIG -> "prompts/avatar_update_config.txt"
            ManualWsScene.EVALUATION -> "prompts/config.txt"
            ManualWsScene.CALL_OUTBOUND -> ""
        }
        return loadAssetText(path)
    }

    /** 与 iOS `initAndEvaluationPromptResourceName = "config"` 对齐：init 与 evaluation 共用 config.txt。 */
    private fun loadEvaluationPrompt(): String? {
        return loadAssetText("prompts/config.txt")
    }

    private fun loadAssetText(path: String): String? {
        return try {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "load asset $path: ${e.message}")
            null
        }
    }

    private fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}

private fun Map<String, Any>.string(key: String): String? {
    val v = this[key] ?: return null
    return v as? String ?: v.toString()
}

private fun parseToolArguments(raw: Any?): Map<String, Any> {
    if (raw is JSONObject) return raw.toAnyMap()
    if (raw is String) {
        val t = raw.trim()
        if (t.isEmpty()) return emptyMap()
        return try {
            JSONObject(t).toAnyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
    return emptyMap()
}

private fun JSONObject.toAnyMap(): Map<String, Any> {
    val out = linkedMapOf<String, Any>()
    val keys = keys()
    while (keys.hasNext()) {
        val k = keys.next()
        when (val v = get(k)) {
            JSONObject.NULL -> continue
            is JSONObject -> out[k] = v.toAnyMap()
            is JSONArray -> out[k] = v.toAnyList()
            else -> out[k] = v
        }
    }
    return out
}

private fun JSONArray.toAnyList(): List<Any> {
    val out = ArrayList<Any>(length())
    for (i in 0 until length()) {
        when (val v = get(i)) {
            JSONObject.NULL -> continue
            is JSONObject -> out.add(v.toAnyMap())
            is JSONArray -> out.add(v.toAnyList())
            else -> out.add(v)
        }
    }
    return out
}
