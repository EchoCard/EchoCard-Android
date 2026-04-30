package com.vaca.callmate.core.ble

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import com.vaca.callmate.data.AbnormalCallRecordStore
import com.vaca.callmate.data.IncomingCall
import com.vaca.callmate.data.outbound.OutboundDialRiskControl
import com.vaca.callmate.features.calls.CallIncomingGateState
import com.vaca.callmate.data.ProcessStrategyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

/**
 * 与 iOS `CallMateBLEClient` 主类 + GATT 回调对齐；协议分发见 [BleControlDispatch]，
 * 命令扩展见 [BleManagerCommands.kt]，模型见 [BleModels.kt]。
 */
@SuppressLint("MissingPermission")
@OptIn(ExperimentalCoroutinesApi::class)
class BleManager(private val context: Context) : BleControlHost {

    companion object {
        private const val TAG = "CallMateBLE"
        private const val ECHO_CARD_NAME_PREFIX = "EchoCard-"
        /** onServiceChanged / 空表 discover 后 `gatt.refresh()` + rediscover 最多尝试次数 */
        private const val MAX_EMPTY_GATT_REFRESH = 3
        /**
         * 无 [deviceMap] 缓存时先扫再连；超时后尝试直连。部分 ROM（如 MIUI）需先有扫描再 GATT。
         */
        private const val RECONNECT_SCAN_FALLBACK_MS = 6_000L
        /**
         * 停止扫描后若立刻 [connectGatt]，部分机型会 LE_CREATE_CONNECTION / 133 失败；
         * 与 RxAndroidBle FAQ、多份 SO 讨论一致，留出空隙再给栈收尾。
         */
        private const val POST_STOP_SCAN_BEFORE_GATT_MS = 400L
    }

    private val bleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** GATT 音频 notify → [bleEvents]：勿用 Main，否则 UI/磁盘卡顿会拖住 BLE→云端转发 */
    private val bleAudioRelayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /**
     * 录音专用 tap（[bleEventsRecording]）：与 [bleAudioRelayScope] 分离，避免 tryEmit / 解码与实时 emit 争用同一线程；
     * GATT 回调内仅 schedule，不在蓝牙线程同步执行录音逻辑。
     */
    private val bleRecordingRelayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val leScanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner

    val echoCardServiceUuid = BleGattUuids.SERVICE

    /** 与 iOS `audioRxCount` / `audioRxBytes` / `audioTxPackets` / `audioTxBytes` 对齐（不含 speed test）。 */
    private var audioRxCount: Int = 0
    private var audioRxBytes: Int = 0
    private var audioTxPackets: Int = 0
    private var audioTxBytes: Int = 0
    private var lastAudioRxLogMs: Long = 0L

    /** 与 iOS `latencyTestEchoMode`：下行 Opus 立即环回上行，供延迟测试。 */
    @Volatile
    private var latencyTestEchoMode: Boolean = false

    /**
     * 与 iOS `latencyTestLoopbackOpusObserver`：收到下行完整 Opus 帧时回调（主线程），用于测量。
     */
    var latencyTestLoopbackOpusObserver: ((ByteArray, Long) -> Unit)? = null

    private val audioDownlinkReassembler = BleAudioDownlinkReassembler()

    private val callRateMonitor = BleCallRateMonitor(
        scope = bleScope,
        tag = TAG,
        rxBytes = { audioRxBytes },
        txBytes = { audioTxBytes },
        rxPackets = { audioRxCount },
        txPackets = { audioTxPackets }
    )

    private val ackTracker = BleAckTracker(bleScope) { bytes -> enqueueWrite(bytes) }
    private val controlDispatch = BleControlDispatch(this)

    /** Reassemble ctrl JSON split across multiple notifications (no newline between fragments). */
    private val ctrlJsonBuffer = StringBuilder()

    private val _otaTransferActive = MutableStateFlow(false)
    val otaTransferActive: StateFlow<Boolean> = _otaTransferActive.asStateFlow()

    private val otaDirectQueue = BleOtaDirectQueue(
        gattProvider = { currentGatt },
        otaCharProvider = { otaChar },
        onTransferActiveChanged = { active -> _otaTransferActive.value = active }
    )

    /**
     * 对标 iOS `CallMateBLEClient+Preload.swift`：与 OTA 同构，单独走 preload characteristic。
     * See [BleGattUuids.PRELOAD] and `docs/tts-filler-low-latency.md §6`.
     */
    private val _preloadTransferActive = MutableStateFlow(false)
    val preloadTransferActive: StateFlow<Boolean> = _preloadTransferActive.asStateFlow()

    private val preloadDirectQueue = BleOtaDirectQueue(
        gattProvider = { currentGatt },
        otaCharProvider = { preloadChar },
        onTransferActiveChanged = { active -> _preloadTransferActive.value = active }
    )

    /** 云端 TTS→BLE 上行与 [BleAudioWriter] 刷队列专用，避免与主线程互抢 */
    private val audioPumpThread = HandlerThread("BleAudioPump", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val audioPumpHandler = Handler(audioPumpThread.looper)

    private val audioWriter = BleAudioWriter(
        gattProvider = { currentGatt },
        audioCharProvider = { audioChar },
        onPacketWritten = { data ->
            BleAudioProtocol.uplinkAudioPayloadBytesIfAny(data)?.let { n ->
                audioTxPackets++
                audioTxBytes += n
            }
        },
        pumpHandler = audioPumpHandler
    )

    private val speedTestController = BleSpeedTestController(
        scope = bleScope,
        isReady = { _isReady.value },
        isAudioReady = { _isAudioReady.value },
        sendSpeedTestCommand = { params, expectAck ->
            sendCommand("speed_test", params, expectAck)
        },
        enqueueAudio = { bytes -> audioWriter.enqueue(bytes) }
    )

    /** 与 iOS speed test 发布字段对齐；用于 Compose 收集。 */
    val speedTest: BleSpeedTestController get() = speedTestController

    private var regDumpSession: BleRegDumpSession? = null

    override val scope: CoroutineScope get() = bleScope

    private val _bluetoothState = MutableStateFlow(BluetoothAdapter.STATE_OFF)
    val bluetoothState: StateFlow<Int> = _bluetoothState.asStateFlow()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _connectingAddress = MutableStateFlow<String?>(null)
    val connectingAddress: StateFlow<String?> = _connectingAddress.asStateFlow()

    private val _connectedAddress = MutableStateFlow<String?>(null)
    val connectedAddress: StateFlow<String?> = _connectedAddress.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /**
     * 本次 GATT 连接上是否曾进入过 ctrl 就绪（[setReadyIfPossible] 为 true）。
     * 用于 UI：在 `onServiceChanged` 后短暂 `!isReady` 但链路未断时仍显示「已连接」，避免与首次连接混淆。
     * 仅在 [resetGattState]（真正断开）时清零。
     */
    private val _sessionHadReadyCtrl = MutableStateFlow(false)
    val sessionHadReadyCtrl: StateFlow<Boolean> = _sessionHadReadyCtrl.asStateFlow()

    private val _isCtrlReady = MutableStateFlow(false)
    val isCtrlReady: StateFlow<Boolean> = _isCtrlReady.asStateFlow()

    private val _isAudioReady = MutableStateFlow(false)
    val isAudioReady: StateFlow<Boolean> = _isAudioReady.asStateFlow()

    private val _isKvReady = MutableStateFlow(false)
    val isKvReady: StateFlow<Boolean> = _isKvReady.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _deviceANCSEnabled = MutableStateFlow<Boolean?>(null)
    val deviceANCSEnabled: StateFlow<Boolean?> = _deviceANCSEnabled.asStateFlow()

    private val _deviceANCSVerifyCount = MutableStateFlow(0)
    val deviceANCSVerifyCount: StateFlow<Int> = _deviceANCSVerifyCount.asStateFlow()

    private val _deviceBattery = MutableStateFlow<Int?>(null)
    val deviceBattery: StateFlow<Int?> = _deviceBattery.asStateFlow()

    private val _deviceCharging = MutableStateFlow<Boolean?>(null)
    val deviceCharging: StateFlow<Boolean?> = _deviceCharging.asStateFlow()

    private val _deviceInfoCounter = MutableStateFlow<Int?>(null)
    val deviceInfoCounter: StateFlow<Int?> = _deviceInfoCounter.asStateFlow()

    private val _deviceFirmwareVersion = MutableStateFlow<String?>(null)
    val deviceFirmwareVersion: StateFlow<String?> = _deviceFirmwareVersion.asStateFlow()

    private val _deviceHfpState = MutableStateFlow<String?>(null)
    val deviceHfpState: StateFlow<String?> = _deviceHfpState.asStateFlow()

    private val _deviceBleBondState = MutableStateFlow<String?>(null)
    val deviceBleBondState: StateFlow<String?> = _deviceBleBondState.asStateFlow()

    private val _deviceChipName = MutableStateFlow<String?>(null)
    val deviceChipName: StateFlow<String?> = _deviceChipName.asStateFlow()

    private val _deviceLEDEnabled = MutableStateFlow<Boolean?>(null)
    val deviceLEDEnabled: StateFlow<Boolean?> = _deviceLEDEnabled.asStateFlow()

    private val _deviceLEDBrightness = MutableStateFlow<Int?>(null)
    val deviceLEDBrightness: StateFlow<Int?> = _deviceLEDBrightness.asStateFlow()

    private val _devicePA20LevelHigh = MutableStateFlow<Boolean?>(null)
    val devicePA20LevelHigh: StateFlow<Boolean?> = _devicePA20LevelHigh.asStateFlow()

    private val _flashdbUsage = MutableStateFlow<FlashDBUsage?>(null)
    val flashdbUsage: StateFlow<FlashDBUsage?> = _flashdbUsage.asStateFlow()

    private val _flashdbLastResult = MutableStateFlow<Int?>(null)
    val flashdbLastResult: StateFlow<Int?> = _flashdbLastResult.asStateFlow()

    private val _flashdbLastMessage = MutableStateFlow<String?>(null)
    val flashdbLastMessage: StateFlow<String?> = _flashdbLastMessage.asStateFlow()

    private val _runtimeMCUDeviceID = MutableStateFlow<String?>(null)
    val runtimeMCUDeviceID: StateFlow<String?> = _runtimeMCUDeviceID.asStateFlow()

    private val _pendingDeviceStrategy = MutableStateFlow<String?>(null)
    val pendingDeviceStrategy: StateFlow<String?> = _pendingDeviceStrategy.asStateFlow()

    private val _mcuRegDumpState = MutableStateFlow<McuRegDumpState>(McuRegDumpState.Idle)
    val mcuRegDumpState: StateFlow<McuRegDumpState> = _mcuRegDumpState.asStateFlow()

    private val kvSync = BleKvSyncCoordinator(
        appContext = context.applicationContext,
        scope = bleScope,
        isKvReady = { _isKvReady.value },
        sendKv = { params, expect -> sendKvCommand(params, expect) },
        sendKvChunkedSet = { key, data -> sendKVChunkedSet(key, data) },
        setRuntimeDeviceId = { id -> _runtimeMCUDeviceID.value = id },
        setPendingStrategy = { s -> _pendingDeviceStrategy.value = s },
        emitFlashdb = { ev -> bleScope.launch { _bleEvents.emit(ev) } },
        setFlashdbUsage = { usage -> _flashdbUsage.value = usage },
        setFlashdbLast = { r, m ->
            _flashdbLastResult.value = r
            _flashdbLastMessage.value = m
        }
    )

    private val _deviceDiagnostics = MutableStateFlow<DeviceDiagnostics?>(null)
    val deviceDiagnostics: StateFlow<DeviceDiagnostics?> = _deviceDiagnostics.asStateFlow()

    private val _mcuCrashLogState = MutableStateFlow<McuCrashLogState>(McuCrashLogState.Idle)
    val mcuCrashLogState: StateFlow<McuCrashLogState> = _mcuCrashLogState.asStateFlow()

    private val _deviceHFPPairingNeeded = MutableStateFlow(false)
    val deviceHFPPairingNeeded: StateFlow<Boolean> = _deviceHFPPairingNeeded.asStateFlow()

    private val _currentCallSid = MutableStateFlow<Long?>(null)
    val currentCallSid: StateFlow<Long?> = _currentCallSid.asStateFlow()

    private val _activeCallSession = MutableStateFlow<CallSessionToken?>(null)
    val activeCallSessionFlow: StateFlow<CallSessionToken?> = _activeCallSession.asStateFlow()

    /**
     * 与 iOS `aiAnswerRequested` + `handleBLECallStateActive` 对齐：协调器发 [answerCall] 后，
     * 在 MCU 上报 `call_state(active)` 时发 `audio_start`（否则 HFP 已接通但 BLE 音频链不会起来）。
     */
    @Volatile
    private var pendingIncomingAiAudioStart: Boolean = false

    /** `audio_start` JSON-RPC 已 ACK（停止重试）；MCU 仍可能在 SCO 建立前返回 0，故不可单独作为上行放行条件。 */
    @Volatile
    private var incomingAiAudioStartCommandAcked: Boolean = false

    /** MCU `call_state(audio_streaming)`：SCO + 上行 Opus 链已就绪，与固件日志「Pending audio start applied」一致。 */
    @Volatile
    private var incomingAiAudioStartAcked: Boolean = false

    private var incomingAiAudioStartRetryJob: Job? = null

    /**
     * 与 iOS `handleBLECallStateOutgoingAnswered`：`outgoing_answered` 时补发 `audio_start`（外呼无 `answer` 路径）。
     */
    @Volatile
    private var pendingOutboundAiAudioStart: Boolean = false

    @Volatile
    private var outboundAiAudioStartCommandAcked: Boolean = false

    private var outboundAiAudioStartRetryJob: Job? = null

    /**
     * App 发起外呼前由 [OutboundTaskQueueService] 写入；与 iOS `pendingOutboundTaskID` + prompt 对齐。
     */
    data class OutboundDialContext(
        val taskId: UUID?,
        val promptRule: String,
        val targetPhone: String = "",
        val callerName: String = "",
        val taskGoal: String = "",
    )

    @Volatile
    private var outboundDialContext: OutboundDialContext? = null

    /** 与 iOS `ble.lastDialedNumber`：最后一次 `dial` 号码（外呼合成 Live 上下文用）。 */
    @Volatile
    private var lastDialedNumber: String? = null

    /**
     * 与 iOS `CallSessionController+Lifecycle`：`isAppManaged` = taskId 非空 **或** prompt 非空。
     */
    fun hasAppManagedOutboundDialPending(): Boolean {
        val c = outboundDialContext ?: return false
        return c.taskId != null || c.promptRule.trim().isNotEmpty()
    }

    fun prepareOutboundDialContext(
        taskId: UUID?,
        promptRule: String,
        targetPhone: String = "",
        callerName: String = "",
        taskGoal: String = "",
    ) {
        outboundDialContext = OutboundDialContext(taskId, promptRule, targetPhone, callerName, taskGoal)
        Log.i(
            INCOMING_AI_CHAIN_TAG,
            "prepareOutboundDialContext taskId=${taskId?.toString() ?: "null"} promptLen=${promptRule.trim().length} phone=$targetPhone"
        )
    }

    fun clearOutboundDialContext() {
        outboundDialContext = null
        lastDialedNumber = null
        _outboundLivePresentation.value = false
    }

    fun outboundDialContextForLive(): OutboundDialContext =
        outboundDialContext ?: OutboundDialContext(null, "")

    fun outboundPromptRuleForLive(): String? =
        outboundDialContext?.promptRule?.trim()?.takeIf { it.isNotEmpty() }

    fun outboundTaskIdForLive(): String? =
        outboundDialContext?.taskId?.toString()

    fun outboundTargetPhoneForLive(): String? =
        outboundDialContext?.targetPhone?.takeIf { it.isNotEmpty() }

    fun outboundCallerNameForLive(): String? =
        outboundDialContext?.callerName?.takeIf { it.isNotEmpty() }

    fun outboundTaskGoalForLive(): String? =
        outboundDialContext?.taskGoal?.takeIf { it.isNotEmpty() }

    /**
     * 与 iOS `CallMateIncomingCall(uid:-1,title:\"[OUTBOUND_TASK]\")` 对齐，供 `call_state(active)` 进 Live。
     */
    fun syntheticOutboundIncomingCallOrNull(): IncomingCall? {
        if (!hasAppManagedOutboundDialPending()) {
            Log.d(INCOMING_AI_CHAIN_TAG, "synthetic outbound: skip (no app-managed dial context)")
            return null
        }
        val num = lastDialedNumber?.trim().orEmpty()
        if (num.isEmpty()) {
            Log.w(INCOMING_AI_CHAIN_TAG, "synthetic outbound: skip (lastDialedNumber empty — dial may not be from app)")
            return null
        }
        Log.i(INCOMING_AI_CHAIN_TAG, "synthetic outbound: ok numberLen=${num.length} sid=$callSid")
        return IncomingCall(
            callId = "ble-outbound-${num.hashCode()}",
            caller = num,
            number = num,
            title = "[OUTBOUND_TASK]",
            bleUid = -1,
            bleSid = callSid,
            isContact = false,
        )
    }


    internal fun noteLastDialedNumber(raw: String) {
        lastDialedNumber = raw.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 与 iOS `CallSessionController+BLERuntime`：真人接听/转交发 `hfp_disconnect` 后短时内勿自动 `hfp_connect`。
     */
    @Volatile
    private var hfpDisconnectCooldownUntilMs: Long = 0L

    /** 已下发的接管模式 HFP 策略；GATT 断开后 [resetGattState] 置空以便重连补发。 */
    @Volatile
    private var lastAppliedHfpPolicyMode: String? = null

    /** 智能模式（`semi`）下最近一次发往 MCU 的 HFP 意向：`connect` | `disconnect`，用于去重。 */
    @Volatile
    private var lastSemiHfpCommand: String? = null

    private var hfpPolicyRetryJob: Job? = null

    override var callSid: Long?
        get() = _currentCallSid.value
        set(value) {
            _currentCallSid.value = value
        }

    override var activeCallSession: CallSessionToken?
        get() = _activeCallSession.value
        set(value) {
            _activeCallSession.value = value
        }

    private val _incomingCallEvents = MutableSharedFlow<IncomingCall>(extraBufferCapacity = 8)
    val incomingCallEvents: SharedFlow<IncomingCall> = _incomingCallEvents.asSharedFlow()

    private val _callStateEvents = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val callStateEvents: SharedFlow<String> = _callStateEvents.asSharedFlow()

    /**
     * 外呼进 Live：MainTabView 在非「接电话」Tab 时 [CallsView] 不在组合树，会漏掉 [callStateEvents]；
     * 在 [emitCallState] 里根据 MCU 状态置位，[CallsView] 一旦组合即可拉进实时转写。
     */
    private val _outboundLivePresentation = MutableStateFlow(false)
    val outboundLivePresentation: StateFlow<Boolean> = _outboundLivePresentation.asStateFlow()

    /** 与 iOS 关闭 Live 来电 UI 对齐：网络不可用忽略接听等场景请求回到 Dashboard */
    private val _dismissIncomingCallUi = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val dismissIncomingCallUi: SharedFlow<Unit> = _dismissIncomingCallUi.asSharedFlow()

    override fun requestDismissIncomingCallUi() {
        bleScope.launch { _dismissIncomingCallUi.emit(Unit) }
    }

    override fun isLatencyTestEchoMode(): Boolean = latencyTestEchoMode

    fun setLatencyTestEchoMode(value: Boolean) {
        latencyTestEchoMode = value
        if (!value) {
            latencyTestLoopbackOpusObserver = null
        }
    }

    private val _bleEvents = MutableSharedFlow<CallMateBleEvent>(extraBufferCapacity = 64)
    val bleEvents: SharedFlow<CallMateBleEvent> = _bleEvents.asSharedFlow()

    /**
     * 仅 Live 会话录音（左声道）使用，与 [bleEvents] 解耦：缓冲满时丢最旧帧，不阻塞 GATT/实时桥接。
     * 仅 [CallMateBleEvent.AudioDownlinkOpus] 会进入此流。
     */
    private val _bleEventsRecording = MutableSharedFlow<CallMateBleEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val bleEventsRecording: SharedFlow<CallMateBleEvent> = _bleEventsRecording.asSharedFlow()

    var bindingScanModeEnabled = false
        private set

    private val deviceMap = mutableMapOf<String, DiscoveredDevice>()
    private var currentGatt: BluetoothGatt? = null
    private var ctrlChar: BluetoothGattCharacteristic? = null
    private var audioChar: BluetoothGattCharacteristic? = null
    private var otaChar: BluetoothGattCharacteristic? = null
    private var preloadChar: BluetoothGattCharacteristic? = null

    private var ctrlNotifyReady = false
    private var ctrlHasEverReceivedValue = false
    private var notifyPhase = NotifyPhase.IDLE

    private enum class NotifyPhase { IDLE, CTRL, AUDIO, OTA, DONE }

    private var nextJsonRpcId = 1
    private var nextKvJsonRpcId = 1
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false
    /** 合并 GATT 写失败重试，避免并发 delay(50) 风暴；成功写入后重置退避。 */
    private var pumpRetryJob: Job? = null
    private var pumpBackoffMs = 64L
    /** 握手后延迟启动 KV，须在 GATT 刷新时取消，避免对已失效的 ctrl 写 kv_get。 */
    private var kvChannelReadyJob: Job? = null

    /**
     * 部分 ROM 在 MTU 已协商后再次 [BluetoothGatt.requestMtu] 不会回调 [onMtuChanged]，
     * 导致 [beginNotifyPipeline] 永不执行（无 notify、无 postConnectHandshake、无 KV）。
     * 超时后强制走 notify 管线；[onMtuChanged] 与 fallback 通过 [notifyPipelineBeginRequested] 去重。
     */
    private var mtuFallbackJob: Job? = null
    private var awaitingMtuForNotifyPipeline = false
    private var notifyPipelineBeginRequested = false
    /** 首次 [onMtuChanged] 成功后为 true；同一连接上 onServiceChanged 后无需再 requestMtu。 */
    private var mtuNegotiated = false

    /**
     * 最近一次 [BluetoothGattCallback.onMtuChanged] 的 ATT MTU。
     * 连接建立初值为 **23**（规范最小 ATT MTU，协商前占位）；协商成功后常见 247/512/517 等，与系统日志
     * `onConfigureMTU() ... mtu=517` 一致，此时 [effectiveAttMtuForUplink] 会用到 517。
     */
    private var negotiatedAttMtu: Int = 23

    /**
     * discover 成功但 [BluetoothGatt.getServices] 仍为空时，已执行的 `refresh()+rediscover` 次数。
     * 在 [setupCharacteristicsAndBeginPipeline] 成功或 [resetConnectionCharacteristics] 时清零。
     */
    private var emptyGattAfterDiscoveryCount = 0

    private var isReadyCache = false
    private var didPostConnectHandshake = false

    private var savedAutoConnectAddress: String? = null

    /** 绑定页扫描以外：回连时允许 [startScanning]（与 [bindingScanModeEnabled] 二选一即可）。 */
    private var reconnectScanEnabled: Boolean = false

    /** 避免对未 start 的 [ScanCallback] 调 stopScan（系统报 could not find callback wrapper）。 */
    private var bleScanActive: Boolean = false

    /** 扫描调试日志按地址去重，避免高频广播刷爆 logcat。 */
    private val bindingScanDecisionLogged = mutableSetOf<String>()
    private val reconnectScanIgnoredLogged = mutableSetOf<String>()
    private var lastPublishedBindingCount = -1
    private var scanSessionId = 0
    private var scanSessionStartedAtMs = 0L
    private var scanRawCallbackCount = 0
    private var scanAcceptedCount = 0
    private var scanRejectedCount = 0
    private var scanBatchCallbackCount = 0
    private var scanFirstCallbackLogged = false
    private var scanWatchdogJob: Job? = null

    /** [autoConnectToSaved] 无缓存设备时：超时后尝试直连 GATT。 */
    private var reconnectScanFallbackJob: Job? = null

    /**
     * BLE 扫描时 [BluetoothDevice.getName] 经常为 null（名称尚未写入系统缓存），
     * 但同一包广播里的 [ScanRecord.getDeviceName] 往往已有 Complete/Short Local Name（如 EchoCard-0203）。
     * 不要把未知设备兜底成 `EchoCard`，否则绑定页会把周边无名 BLE 设备误认为本产品。
     */
    private fun normalizeDiscoveredDeviceName(raw: String?): String? = raw?.trim()?.takeIf { it.isNotBlank() }

    private fun isEchoCardName(name: String?): Boolean =
        name?.startsWith(ECHO_CARD_NAME_PREFIX, ignoreCase = true) == true

    private fun advertisesEchoCardService(result: ScanResult): Boolean =
        result.scanRecord?.serviceUuids?.any { it.uuid == echoCardServiceUuid } == true

    private fun isBindingCandidate(
        result: ScanResult,
        resolvedName: String,
        existing: DiscoveredDevice?
    ): Boolean = isEchoCardName(resolvedName) || isEchoCardName(existing?.name) || advertisesEchoCardService(result)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabledForBle(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun bluetoothStateLabel(state: Int?): String =
        when (state) {
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "UNKNOWN($state)"
        }

    private fun scanModeLabel(): String =
        if (bindingScanModeEnabled) "binding(app_filter_name_or_service_uuid)" else "reconnect(all_adv_match_saved_mac)"

    private fun scanEnvironmentSummary(): String {
        val hasScanPermission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasPermission(Manifest.permission.BLUETOOTH_SCAN) else true
        val hasConnectPermission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasPermission(Manifest.permission.BLUETOOTH_CONNECT) else true
        val hasLocationPermission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) else true
        return "mode=${scanModeLabel()} bt=${bluetoothStateLabel(adapter?.state)} scanner=${leScanner != null} " +
            "permScan=$hasScanPermission permConnect=$hasConnectPermission permLocation=$hasLocationPermission " +
            "locationEnabled=${isLocationEnabledForBle()} systemFilter=none " +
            "appFilter=${if (bindingScanModeEnabled) "name_or_service_uuid(${echoCardServiceUuid})" else "saved_mac"} " +
            "target=${savedAutoConnectAddress ?: "-"}"
    }

    private fun scanStatsSummary(): String =
        "session=$scanSessionId raw=$scanRawCallbackCount batch=$scanBatchCallbackCount accepted=$scanAcceptedCount rejected=$scanRejectedCount cache=${deviceMap.size}"

    private fun startScanWatchdog(sessionId: Int) {
        scanWatchdogJob?.cancel()
        scanWatchdogJob =
            bleScope.launch {
                delay(3_000)
                if (!bleScanActive || sessionId != scanSessionId) return@launch
                if (scanRawCallbackCount == 0) {
                    Log.w(TAG, "[BLE] scan watchdog: no callbacks after 3s ${scanEnvironmentSummary()}")
                } else {
                    Log.i(TAG, "[BLE] scan watchdog: 3s ${scanStatsSummary()}")
                }
                delay(5_000)
                if (!bleScanActive || sessionId != scanSessionId) return@launch
                if (scanRawCallbackCount == 0) {
                    Log.w(TAG, "[BLE] scan watchdog: still no callbacks after 8s ${scanEnvironmentSummary()}")
                } else {
                    Log.i(TAG, "[BLE] scan watchdog: 8s ${scanStatsSummary()}")
                }
            }
    }

    private fun stopScanWatchdog() {
        scanWatchdogJob?.cancel()
        scanWatchdogJob = null
    }

    private fun resetScanDebugState() {
        bindingScanDecisionLogged.clear()
        reconnectScanIgnoredLogged.clear()
        lastPublishedBindingCount = -1
        scanRawCallbackCount = 0
        scanAcceptedCount = 0
        scanRejectedCount = 0
        scanBatchCallbackCount = 0
        scanFirstCallbackLogged = false
    }

    private fun noteRawScanCallback(callbackType: Int, result: ScanResult) {
        scanRawCallbackCount++
        if (scanFirstCallbackLogged) return
        scanFirstCallbackLogged = true
        val advName = normalizeDiscoveredDeviceName(result.scanRecord?.deviceName) ?: "-"
        val deviceName = normalizeDiscoveredDeviceName(result.device.name) ?: "-"
        val uuids = result.scanRecord?.serviceUuids?.joinToString(limit = 4) { it.uuid.toString() } ?: "-"
        Log.i(
            TAG,
            "[BLE] firstScanCallback session=$scanSessionId type=$callbackType addr=${result.device.address} " +
                "rssi=${result.rssi} adv=$advName device=$deviceName uuids=$uuids"
        )
    }

    private fun bindingDecisionSummary(
        result: ScanResult,
        resolvedName: String,
        existing: DiscoveredDevice?
    ): String {
        val advName = normalizeDiscoveredDeviceName(result.scanRecord?.deviceName) ?: "-"
        val deviceName = normalizeDiscoveredDeviceName(result.device.name) ?: "-"
        val existingName = normalizeDiscoveredDeviceName(existing?.name) ?: "-"
        return "addr=${result.device.address} resolved=$resolvedName adv=$advName device=$deviceName existing=$existingName serviceUuid=${advertisesEchoCardService(result)}"
    }

    private fun logBindingDecisionOnce(
        result: ScanResult,
        resolvedName: String,
        existing: DiscoveredDevice?,
        accepted: Boolean
    ) {
        val key = "${if (accepted) "accept" else "reject"}:${result.device.address}"
        if (!bindingScanDecisionLogged.add(key)) return
        val action = if (accepted) "accept" else "reject"
        Log.i(TAG, "[BLE] bindingScan $action ${bindingDecisionSummary(result, resolvedName, existing)}")
    }

    private fun logReconnectIgnoredEchoCardOnce(
        result: ScanResult,
        resolvedName: String,
        savedAddress: String
    ) {
        val address = result.device.address
        if (!reconnectScanIgnoredLogged.add(address)) return
        val looksLikeEchoCard = isEchoCardName(resolvedName) || advertisesEchoCardService(result)
        if (!looksLikeEchoCard) return
        Log.i(
            TAG,
            "[BLE] reconnectScan ignore non-target echo candidate addr=$address target=$savedAddress " +
                "resolved=$resolvedName serviceUuid=${advertisesEchoCardService(result)}"
        )
    }

    private fun publishBindingDevices() {
        val published =
            deviceMap.values
                .asSequence()
                .sortedBy { it.address }
                .toList()
        _devices.value = published
        if (published.size != lastPublishedBindingCount) {
            lastPublishedBindingCount = published.size
            Log.i(
                TAG,
                "[BLE] bindingScan publish count=${published.size} cache=${deviceMap.size} " +
                    "names=${published.joinToString(limit = 5) { it.name }}"
            )
        }
    }

    private fun clearDiscoveredDevices() {
        deviceMap.clear()
        _devices.value = emptyList()
        resetScanDebugState()
        Log.i(TAG, "[BLE] scan cache cleared")
    }

    private fun resolveDiscoveredDeviceName(result: ScanResult, existing: DiscoveredDevice?): String {
        val fromAdv = normalizeDiscoveredDeviceName(result.scanRecord?.deviceName)
        val fromDevice = normalizeDiscoveredDeviceName(result.device.name)
        val fromExisting = normalizeDiscoveredDeviceName(existing?.name)
        return fromAdv ?: fromDevice ?: fromExisting ?: "Unknown BLE"
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            noteRawScanCallback(callbackType, result)
            val device = result.device
            val address = device.address
            val existing = deviceMap[address]
            val name = resolveDiscoveredDeviceName(result, existing)
            val saved = savedAutoConnectAddress
            if (reconnectScanEnabled && !bindingScanModeEnabled) {
                if (saved.isNullOrBlank() || !address.equals(saved, ignoreCase = true)) {
                    if (!saved.isNullOrBlank()) {
                        logReconnectIgnoredEchoCardOnce(result, name, saved)
                    }
                    return
                }
            }
            if (bindingScanModeEnabled && !isBindingCandidate(result, name, existing)) {
                scanRejectedCount++
                logBindingDecisionOnce(result, name, existing, accepted = false)
                return
            }
            val rssi = result.rssi
            val discovered = DiscoveredDevice(
                address = address,
                name = name,
                rssi = rssi,
                firstSeen = existing?.firstSeen ?: System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis()
            )
            deviceMap[address] = discovered
            // 回连扫描为无过滤广播，周边设备极多时每次更新 StateFlow 会拖死主线程；仅绑定页需要实时列表。
            if (bindingScanModeEnabled) {
                scanAcceptedCount++
                logBindingDecisionOnce(result, name, existing, accepted = true)
                publishBindingDevices()
            }

            if (saved != null &&
                address.equals(saved, ignoreCase = true) &&
                _connectedAddress.value == null &&
                _connectingAddress.value == null
            ) {
                connect(discovered)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            val batch = results ?: mutableListOf()
            scanBatchCallbackCount++
            Log.i(TAG, "[BLE] onBatchScanResults session=$scanSessionId size=${batch.size}")
            batch.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            stopScanWatchdog()
            val errorName =
                when (errorCode) {
                    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
                    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                    ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES
                    } else {
                        Int.MIN_VALUE
                    } -> "OUT_OF_HARDWARE_RESOURCES"
                    else -> "UNKNOWN"
                }
            _lastError.value = "Scan failed: $errorCode"
            Log.w(
                TAG,
                "[BLE] onScanFailed code=$errorCode($errorName) ${scanStatsSummary()} ${scanEnvironmentSummary()}"
            )
            if (bindingScanModeEnabled) {
                publishBindingDevices()
            }
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            updateBluetoothState()
            if (adapter?.state == BluetoothAdapter.STATE_ON) {
                val addr = savedAutoConnectAddress
                if (!addr.isNullOrBlank()) {
                    bleScope.launch {
                        delay(400)
                        autoConnectToSaved(addr)
                    }
                }
            }
        }
    }

    /**
     * 部分机型在 onServiceChanged / 重 discover 后会换 Gatt 包装实例，引用 != currentGatt 会丢回调；
     * 以设备地址为准并同步 [currentGatt]。
     */
    @SuppressLint("MissingPermission")
    private fun syncGattIfSameDevice(gatt: BluetoothGatt): Boolean {
        val cur = currentGatt ?: return false
        if (!gatt.device.address.equals(cur.device.address, ignoreCase = true)) return false
        if (gatt !== cur) {
            Log.w(TAG, "[BLE] GATT callback instance != currentGatt; resyncing (same address)")
            currentGatt = gatt
        }
        return true
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!syncGattIfSameDevice(gatt)) return
            Log.i(
                TAG,
                "[BLE] onConnectionStateChange addr=${gatt.device.address} status=$status newState=$newState"
            )
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectScanFallbackJob?.cancel()
                    reconnectScanFallbackJob = null
                    _connectingAddress.value = null
                    _connectedAddress.value = gatt.device.address
                    resetConnectionCharacteristics()
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    resetGattState()
                    gatt.close()
                    if (status != 0) {
                        _lastError.value = "Disconnected: $status"
                    }
                    val addr = savedAutoConnectAddress
                    if (!addr.isNullOrBlank()) {
                        bleScope.launch {
                            delay(800)
                            autoConnectToSaved(addr)
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(
                TAG,
                "onServicesDiscovered status=$status refMatch=${gatt === currentGatt} addr=${gatt.device.address}"
            )
            if (!syncGattIfSameDevice(gatt)) {
                Log.w(TAG, "onServicesDiscovered: ignored (not current connection)")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _lastError.value = "Service discovery failed"
                _isReady.value = false
                return
            }
            applyDiscoveredServices(gatt)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!syncGattIfSameDevice(gatt)) return
            awaitingMtuForNotifyPipeline = false
            mtuFallbackJob?.cancel()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtuNegotiated = true
                negotiatedAttMtu = mtu
                Log.i(TAG, "[BLE] MTU: mtu=$mtu")
            } else {
                Log.w(TAG, "[BLE] MTU: request failed status=$status")
            }
            beginNotifyPipeline(gatt)
        }

        @TargetApi(Build.VERSION_CODES.S)
        override fun onServiceChanged(gatt: BluetoothGatt) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            if (!syncGattIfSameDevice(gatt)) return
            val addr = gatt.device.address
            Log.w(TAG, "[BLE] onServiceChanged — GATT table changed; rediscover services + re-notify")
            kvChannelReadyJob?.cancel()
            kvChannelReadyJob = null
            mtuFallbackJob?.cancel()
            mtuFallbackJob = null
            prepareGattForServiceRediscovery()
            emptyGattAfterDiscoveryCount = 0
            // 同步在回调里 discover 时，部分 ROM 上 onServicesDiscovered 与 currentGatt 引用不一致会整段丢失；延后 + 检查返回值。
            // 先 refresh 清缓存，否则常见 status=0 但 services 仍为空（count=0）。
            bleScope.launch {
                delay(150)
                val g = currentGatt
                if (g == null || !g.device.address.equals(addr, ignoreCase = true)) {
                    Log.w(TAG, "[BLE] onServiceChanged: currentGatt missing or address changed after delay")
                    return@launch
                }
                val refreshed = refreshGattCache(g)
                Log.i(TAG, "[BLE] onServiceChanged gatt.refresh()=$refreshed")
                delay(100)
                var ok = g.discoverServices()
                Log.i(TAG, "[BLE] onServiceChanged discoverServices()=$ok")
                if (!ok) {
                    delay(300)
                    val g2 = currentGatt
                    if (g2 != null && g2.device.address.equals(addr, ignoreCase = true)) {
                        ok = g2.discoverServices()
                        Log.w(TAG, "[BLE] onServiceChanged discoverServices retry=$ok")
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!syncGattIfSameDevice(gatt)) return
            if (descriptor.uuid != BleGattUuids.CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _lastError.value = "Enable notify failed"
                return
            }
            when (descriptor.characteristic.uuid) {
                BleGattUuids.CTRL -> {
                    Log.i(TAG, "[BLE] ctrl notify=true")
                    ctrlNotifyReady = true
                    // 不在这里 readCharacteristic：Android GATT 串行，会与后续 CCCD 写冲突。
                    setReadyIfPossible()
                    advanceNotifyPipelineAfterCtrl(gatt)
                }
                BleGattUuids.AUDIO -> {
                    Log.i(TAG, "[BLE] audio notify=true")
                    _isAudioReady.value = true
                    if (otaChar != null) {
                        startNotifyPhase(gatt, NotifyPhase.OTA)
                    } else {
                        notifyPhase = NotifyPhase.DONE
                        tryPostConnectHandshakeAfterNotifyPipeline()
                    }
                }
                BleGattUuids.OTA -> {
                    Log.i(TAG, "[BLE] ota notify=true")
                    notifyPhase = NotifyPhase.DONE
                    tryPostConnectHandshakeAfterNotifyPipeline()
                }
                else -> {}
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (!syncGattIfSameDevice(gatt)) return
            when (characteristic.uuid) {
                BleGattUuids.CTRL -> {
                    handleCtrlCharacteristicPayload(characteristic.value)
                }
                BleGattUuids.AUDIO -> {
                    val value = characteristic.value ?: return
                    handleGattAudioNotification(value)
                }
                BleGattUuids.OTA -> {
                    /* 控制面 JSON 在 OTA notify 上较少；预留 */
                }
                else -> {}
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (!syncGattIfSameDevice(gatt)) return
            when (characteristic.uuid) {
                BleGattUuids.CTRL -> {
                    writeInProgress = false
                    pumpBackoffMs = 64L
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        writeQueue.pollFirst()
                    } else {
                        writeQueue.pollFirst()
                        _lastError.value = "Write failed: $status"
                    }
                    pumpWrites()
                }
                BleGattUuids.AUDIO -> {
                    audioWriter.onWriteCompleted()
                }
                BleGattUuids.OTA -> {
                    otaDirectQueue.onWriteWindowMaybeOpen()
                }
                BleGattUuids.PRELOAD -> {
                    preloadDirectQueue.onWriteWindowMaybeOpen()
                }
                else -> {}
            }
        }
    }

    init {
        updateBluetoothState()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.applicationContext.registerReceiver(btStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.applicationContext.registerReceiver(btStateReceiver, filter)
        }
        bleScope.launch {
            ProcessStrategyStore.processStrategyJSONFlow(context.applicationContext)
                .distinctUntilChanged()
                .collect { json ->
                    kvSync.onLocalStrategyJsonChanged(json)
                }
        }
    }

    // region BleControlHost
    override fun emitEvent(event: CallMateBleEvent) {
        bleScope.launch { _bleEvents.emit(event) }
    }

    override fun emitIncomingCall(call: IncomingCall) {
        Log.i(
            INCOMING_AI_CHAIN_TAG,
            "emitIncomingCall -> flow uid=${call.bleUid} sid=${call.bleSid} isContact=${call.isContact} number=${call.number}"
        )
        bleScope.launch { _incomingCallEvents.emit(call) }
    }

    override fun cancelIncomingAiTakeover(reason: String) {
        Log.i(INCOMING_AI_CHAIN_TAG, "cancelIncomingAiTakeover reason=$reason")
        com.vaca.callmate.features.calls.IncomingCallSessionCoordinator.cancelPendingAiTakeover(
            context.applicationContext,
            this,
            reason
        )
    }

    override fun emitCallState(state: String) {
        bleScope.launch {
            _callStateEvents.emit(state)
            val s = state.lowercase()
            if (s == "ended" || s == "rejected" || s == "phone_handled") {
                _outboundLivePresentation.value = false
            } else if (s == "active" || s == "outgoing_answered" || s == "audio_streaming") {
                if (hasAppManagedOutboundDialPending() && syntheticOutboundIncomingCallOrNull() != null) {
                    _outboundLivePresentation.value = true
                }
            }
        }
    }

    override fun sendJsonRpc(method: String, params: JSONObject, expectAck: Boolean) {
        sendCommand(method, params, expectAck)
    }

    override fun onAckHandled(cmd: String, sid: Long?, id: Int?) {
        ackTracker.handleAck(cmd, sid, id)
    }

    override fun onResolveCmdForJsonRpcId(id: Int): String =
        ackTracker.resolveCmdForJsonRpcId(id)

    override fun applyAckSideEffects(cmd: String, result: Int) {
        if (cmd == "audio_start" && result == 0) {
            incomingAiAudioStartCommandAcked = true
            outboundAiAudioStartCommandAcked = true
            incomingAiAudioStartRetryJob?.cancel()
            incomingAiAudioStartRetryJob = null
            outboundAiAudioStartRetryJob?.cancel()
            outboundAiAudioStartRetryJob = null
            return
        }
        if (cmd == "audio_stop" && (result == 0 || result == -1)) {
            callRateMonitor.stop("ack_audio_stop")
            return
        }
        if (cmd == "speed_test") {
            speedTestController.onSpeedTestAck(result)
            return
        }
        if (cmd == "answer") {
            if (result == 0) {
                Log.i(INCOMING_AI_CHAIN_TAG, "ack answer ok result=0")
            } else {
                Log.w(INCOMING_AI_CHAIN_TAG, "ack answer failed result=$result")
                if (result != -2) {
                    _deviceHFPPairingNeeded.value = true
                    AbnormalCallRecordStore.getInstance(context).append(
                        "answer_failed",
                        "result=$result"
                    )
                }
            }
        }
    }

    override fun setDeviceBattery(value: Int?) {
        _deviceBattery.value = value
    }

    override fun setDeviceCharging(value: Boolean?) {
        _deviceCharging.value = value
    }

    override fun setDeviceInfoCounter(value: Int?) {
        _deviceInfoCounter.value = value
    }

    override fun setDeviceFirmware(value: String?) {
        if (value != null) _deviceFirmwareVersion.value = value
    }

    override fun setDeviceHfp(value: String?) {
        _deviceHfpState.value = value
    }

    override fun setDeviceBleBond(value: String?) {
        _deviceBleBondState.value = value
    }

    override fun setDeviceChipName(value: String?) {
        _deviceChipName.value = value
    }

    override fun setDeviceLedFromInfo(enabled: Boolean?, brightness: Int?, pa20High: Boolean?) {
        if (enabled != null) _deviceLEDEnabled.value = enabled
        if (brightness != null) _deviceLEDBrightness.value = brightness
        if (pa20High != null) _devicePA20LevelHigh.value = pa20High
    }

    override fun setDeviceAncsFromDeviceInfo(enabled: Boolean) {
        _deviceANCSEnabled.value = enabled
    }

    override fun setAncsStatus(enabled: Boolean, incrementVerify: Boolean) {
        _deviceANCSEnabled.value = enabled
        if (incrementVerify) {
            _deviceANCSVerifyCount.value = _deviceANCSVerifyCount.value + 1
        }
    }

    override fun setFlashdbUsage(usage: FlashDBUsage?) {
        _flashdbUsage.value = usage
    }

    override fun setFlashdbLast(result: Int, message: String) {
        _flashdbLastResult.value = result
        _flashdbLastMessage.value = message
    }

    override fun setDeviceDiagnostics(d: DeviceDiagnostics) {
        _deviceDiagnostics.value = d
    }

    override fun setMcuCrashLogState(state: McuCrashLogState) {
        _mcuCrashLogState.value = state
    }

    override fun setHfpPairingNeeded(needed: Boolean) {
        _deviceHFPPairingNeeded.value = needed
    }

    override fun onKvResponse(json: JSONObject) {
        kvSync.handleKvRsp(json)
    }

    override fun onKvChunkMessage(json: JSONObject) {
        kvSync.handleKvChunk(json)
    }

    override fun onRegDumpMeta(json: JSONObject) {
        regDumpSession = BleRegDumpSession(
            sendRegDumpChunkRequest = { idx ->
                sendCommand("reg_dump_get_chunk", JSONObject().put("index", idx), false)
            },
            setState = { st -> _mcuRegDumpState.value = st }
        )
        regDumpSession?.onMeta(json)
    }

    override fun onRegDumpChunk(json: JSONObject) {
        regDumpSession?.onChunk(json)
    }

    override fun onCallStateAudioStreamingReady() {
        incomingAiAudioStartAcked = true
        callRateMonitor.startIfNeeded("call_state_audio_streaming")
        requestHighConnectionPriority("audio_streaming")
        Log.i(
            INCOMING_AI_CHAIN_TAG,
            "call_state audio_streaming -> TTS uplink allowed (MCU SCO/uplink path ready)"
        )
        // 事件驱动补发：链就绪时即可再发 audio_start，不必等协程里 delay(2s)（后台不准）
        val codec = defaultMcuAudioCodecName()
        val sid = callSid
        if (pendingIncomingAiAudioStart && !incomingAiAudioStartCommandAcked) {
            Log.i(
                INCOMING_AI_CHAIN_TAG,
                "audio_streaming -> audio_start (pending jsonrpc ack) sid=$sid codec=$codec"
            )
            audioStart(sid, codec)
        }
        if (pendingOutboundAiAudioStart && !outboundAiAudioStartCommandAcked) {
            Log.i(
                INCOMING_AI_CHAIN_TAG,
                "audio_streaming -> outbound audio_start (pending jsonrpc ack) sid=$sid codec=$codec"
            )
            audioStart(sid, codec)
        }
    }

    override fun onCallStateBecameActive() {
        speedTestController.onCallStateActive()
        callRateMonitor.startIfNeeded("call_state_active")
        requestHighConnectionPriority("call_active")
        if (pendingIncomingAiAudioStart) {
            val sid = callSid
            val codec = defaultMcuAudioCodecName()
            Log.i(
                INCOMING_AI_CHAIN_TAG,
                "call_state active -> audio_start sid=$sid codec=$codec (incoming AI)"
            )
            audioStart(sid, codec)
            startIncomingAiAudioStartRetryLoop()
        }
    }

    override fun onOutgoingAnswered() {
        if (!hasAppManagedOutboundDialPending()) {
            Log.i(INCOMING_AI_CHAIN_TAG, "outgoing_answered: skip (no app outbound context)")
            return
        }
        pendingOutboundAiAudioStart = true
        outboundAiAudioStartCommandAcked = false
        val sid = callSid
        val codec = defaultMcuAudioCodecName()
        Log.i(
            INCOMING_AI_CHAIN_TAG,
            "outgoing_answered -> audio_start sid=$sid codec=$codec (outbound AI)"
        )
        audioStart(sid, codec)
        startOutboundAiAudioStartRetryLoop()
    }

    override fun onCallStateTerminated(state: String) {
        pendingIncomingAiAudioStart = false
        incomingAiAudioStartCommandAcked = false
        incomingAiAudioStartAcked = false
        incomingAiAudioStartRetryJob?.cancel()
        incomingAiAudioStartRetryJob = null
        pendingOutboundAiAudioStart = false
        outboundAiAudioStartCommandAcked = false
        outboundAiAudioStartRetryJob?.cancel()
        outboundAiAudioStartRetryJob = null
        clearOutboundDialContext()
        callRateMonitor.stop("call_state_$state")
        requestBalancedConnectionPriority("call_terminated_$state")
        if (state == "ended" || state == "rejected" || state == "phone_handled") {
            CallIncomingGateState.resetAfterCallTerminated()
        }
    }

    /**
     * Request CONNECTION_PRIORITY_HIGH to reduce BLE connection interval (~7.5-15ms).
     * Critical during SCO+BLE coexistence: the default interval (~48ms) causes
     * severe TTS data starvation on the MCU uplink, resulting in choppy audio.
     */
    private fun requestHighConnectionPriority(reason: String) {
        val gatt = currentGatt ?: return
        try {
            val ok = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            Log.i(TAG, "[BLE] requestConnectionPriority(HIGH) reason=$reason ok=$ok")
        } catch (e: Exception) {
            Log.w(TAG, "[BLE] requestConnectionPriority(HIGH) failed: ${e.message}")
        }
    }

    private fun requestBalancedConnectionPriority(reason: String) {
        val gatt = currentGatt ?: return
        try {
            val ok = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
            Log.i(TAG, "[BLE] requestConnectionPriority(BALANCED) reason=$reason ok=$ok")
        } catch (e: Exception) {
            Log.w(TAG, "[BLE] requestConnectionPriority(BALANCED) failed: ${e.message}")
        }
    }

    /**
     * 与 iOS `UserDefaults` `callmate.ble_audio_codec` 默认 `opus` 对齐；后续可加 DataStore。
     */
    private fun defaultMcuAudioCodecName(): String = "opus"

    /**
     * 与 iOS `CallAudioRouter.startAudioStartRetryLoop` 对齐：**兜底**每 2s 补发直到 ACK 或通话结束。
     * 主路径优先 [onCallStateAudioStreamingReady]（MCU 上报链就绪即补发）与 JSON-RPC ACK，减少对后台不准的 delay 的依赖。
     */
    private fun startIncomingAiAudioStartRetryLoop() {
        incomingAiAudioStartRetryJob?.cancel()
        incomingAiAudioStartRetryJob = bleScope.launch {
            while (isActive && pendingIncomingAiAudioStart && !incomingAiAudioStartCommandAcked) {
                delay(2_000L)
                if (!pendingIncomingAiAudioStart || incomingAiAudioStartCommandAcked) break
                val sid = callSid
                Log.i(INCOMING_AI_CHAIN_TAG, "audio_start retry sid=$sid codec=${defaultMcuAudioCodecName()}")
                audioStart(sid, defaultMcuAudioCodecName())
            }
        }
    }

    /** 兜底：见 [startIncomingAiAudioStartRetryLoop]；主路径含 [onCallStateAudioStreamingReady] 补发。 */
    private fun startOutboundAiAudioStartRetryLoop() {
        outboundAiAudioStartRetryJob?.cancel()
        outboundAiAudioStartRetryJob = bleScope.launch {
            while (isActive && pendingOutboundAiAudioStart && !outboundAiAudioStartCommandAcked) {
                delay(2_000L)
                if (!pendingOutboundAiAudioStart || outboundAiAudioStartCommandAcked) break
                val sid = callSid
                Log.i(
                    INCOMING_AI_CHAIN_TAG,
                    "outbound audio_start retry sid=$sid codec=${defaultMcuAudioCodecName()}"
                )
                audioStart(sid, defaultMcuAudioCodecName())
            }
        }
    }

    internal fun markIncomingAiAnswerPending() {
        pendingIncomingAiAudioStart = true
        incomingAiAudioStartCommandAcked = false
        incomingAiAudioStartAcked = false
    }

    fun markHfpDisconnectCooldown(durationMs: Long = 10_000L) {
        hfpDisconnectCooldownUntilMs = System.currentTimeMillis() + durationMs
        Log.i(TAG, "[HFP] disconnect cooldown ${durationMs}ms (handoff / human takeover)")
    }

    private fun shouldSuppressHfpConnect(): Boolean =
        System.currentTimeMillis() < hfpDisconnectCooldownUntilMs

    /**
     * 待机：MCU 侧经典蓝牙 HFP 保持断开。
     * 智能（`semi`）：仅在系统通话状态为振铃/摘机时 [hfp_connect]，空闲 [hfp_disconnect]；勿在 BLE 就绪时立刻连 HFP。
     * 全接管（`full`）：保持 HFP 连接（便于监听/代接）。
     * 联系人直通不触发 AI [answerCall]/[markIncomingAiAnswerPending]，故不会走 BLE `audio_start` 代接音频链；
     * 真人接听由 [CallSessionViewModel] 发 `audio_stop`+`hfp_disconnect` 并带 [markHfpDisconnectCooldown]。
     */
    fun applyActiveModeHfpPolicy(mode: String) {
        val connected = !_connectedAddress.value.isNullOrBlank() && _isReady.value
        if (!connected) return
        when (mode) {
            "standby" -> {
                if (lastAppliedHfpPolicyMode == "standby") return
                lastAppliedHfpPolicyMode = "standby"
                lastSemiHfpCommand = null
                hfpPolicyRetryJob?.cancel()
                Log.i(TAG, "[HFP] policy: standby -> hfp_disconnect (no auto-connect cooldown)")
                sendCommand("hfp_disconnect", JSONObject(), expectAck = false)
            }
            "semi" -> {
                if (lastAppliedHfpPolicyMode != "semi") {
                    lastSemiHfpCommand = null
                }
                applySemiModeHfpPolicyWithCooldown(readTelephonyCallStateForHfp())
            }
            "full" -> {
                if (lastAppliedHfpPolicyMode == "full" && !shouldSuppressHfpConnect()) return
                if (shouldSuppressHfpConnect()) {
                    val wait = (hfpDisconnectCooldownUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
                    Log.i(TAG, "[HFP] policy: full -> hfp_connect deferred ${wait}ms (cooldown)")
                    hfpPolicyRetryJob?.cancel()
                    hfpPolicyRetryJob = bleScope.launch {
                        if (wait > 0) delay(wait)
                        lastAppliedHfpPolicyMode = null
                        applyActiveModeHfpPolicy("full")
                    }
                    return
                }
                lastAppliedHfpPolicyMode = "full"
                lastSemiHfpCommand = null
                hfpPolicyRetryJob?.cancel()
                Log.i(TAG, "[HFP] policy: full -> hfp_connect")
                sendCommand("hfp_connect", JSONObject(), expectAck = true)
            }
            else -> { }
        }
    }

    /**
     * 由 [com.vaca.callmate.core.telephony.SemiModeHfpTelephonyBridge] 在通话状态变化时调用；
     * 仅当当前为智能模式（`semi`）时生效。
     */
    fun applySemiModeHfpFromTelephony(callState: Int) {
        if (CallIncomingGateState.activeMode != "semi") return
        val connected = !_connectedAddress.value.isNullOrBlank() && _isReady.value
        if (!connected) return
        applySemiModeHfpPolicyWithCooldown(callState)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readTelephonyCallStateForHfp(): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return TelephonyManager.CALL_STATE_IDLE
        }
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return TelephonyManager.CALL_STATE_IDLE
        return tm.callState
    }

    private fun applySemiModeHfpPolicyWithCooldown(callState: Int) {
        val wantConnect =
            callState == TelephonyManager.CALL_STATE_RINGING ||
                callState == TelephonyManager.CALL_STATE_OFFHOOK
        if (wantConnect && shouldSuppressHfpConnect()) {
            val wait = (hfpDisconnectCooldownUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
            Log.i(TAG, "[HFP] policy: semi -> hfp_connect deferred ${wait}ms (cooldown)")
            hfpPolicyRetryJob?.cancel()
            hfpPolicyRetryJob = bleScope.launch {
                if (wait > 0) delay(wait)
                lastAppliedHfpPolicyMode = null
                lastSemiHfpCommand = null
                applyActiveModeHfpPolicy("semi")
            }
            return
        }
        hfpPolicyRetryJob?.cancel()
        val cmd = if (wantConnect) "connect" else "disconnect"
        if (lastAppliedHfpPolicyMode == "semi" && lastSemiHfpCommand == cmd) return
        lastAppliedHfpPolicyMode = "semi"
        lastSemiHfpCommand = cmd
        if (wantConnect) {
            Log.i(TAG, "[HFP] policy: semi (in-call) -> hfp_connect")
            sendCommand("hfp_connect", JSONObject(), expectAck = true)
        } else {
            Log.i(TAG, "[HFP] policy: semi (idle) -> hfp_disconnect")
            sendCommand("hfp_disconnect", JSONObject(), expectAck = false)
        }
    }

    /**
     * 与 MCU `call_state(audio_streaming)` 对齐：仅在 SCO + 上行链就绪后 true。
     * 勿与 `audio_start` JSON-RPC ACK 混淆——固件可能在 SCO 前即 ACK。
     */
    fun isIncomingAiAudioStartAcked(): Boolean = incomingAiAudioStartAcked

    /**
     * 与 iOS `CallMateBLEClient+Audio.sendUplinkOpus` 对齐：云端 TTS（WS 二进制帧）→ MCU → HFP 注入对端。
     */
    fun sendUplinkOpus(opusPayload: ByteArray) {
        if (opusPayload.isEmpty()) return
        audioPumpHandler.post {
            val packets = buildUplinkOpusBlePackets(opusPayload)
            audioWriter.enqueueAll(packets)
        }
    }

    /**
     * 与 iOS `handleAudioNotification` 对齐：测速 0x21 单独统计；0x01/0x11 经重组后 emit 或环回。
     */
    private fun handleGattAudioNotification(value: ByteArray) {
        if (value.size < 4) return
        val t0 = value[0].toInt() and 0xFF
        if (t0 == 0x21) {
            val lenLo = value[2].toInt() and 0xFF
            val lenHi = value[3].toInt() and 0xFF
            val declared = lenLo or (lenHi shl 8)
            val payloadLen = minOf(declared, value.size - 4).coerceAtLeast(0)
            speedTestController.recordDownlinkPayloadBytes(payloadLen)
            return
        }
        val completed = audioDownlinkReassembler.feed(value) ?: return
        val type = completed.first
        val payload = completed.second
        if (type != 0x01 && type != 0x11) return
        audioRxCount++
        audioRxBytes += payload.size
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastAudioRxLogMs > 2000) {
            lastAudioRxLogMs = nowMs
            Log.i(TAG, "[BLE] audio rx: notifs=$audioRxCount bytes=$audioRxBytes")
        }
        if (latencyTestEchoMode) {
            latencyTestLoopbackOpusObserver?.let { observer ->
                val nano = System.nanoTime()
                bleScope.launch(Dispatchers.Main.immediate) {
                    observer.invoke(payload, nano)
                }
            }
            sendUplinkOpus(payload)
            return
        }
        val ev = when (type) {
            0x01 -> CallMateBleEvent.AudioDownlinkOpus(payload)
            0x11 -> CallMateBleEvent.AudioDownlinkMsbcPayload57(payload)
            else -> return
        }
        bleAudioRelayScope.launch { _bleEvents.emit(ev) }
        if (ev is CallMateBleEvent.AudioDownlinkOpus) {
            bleRecordingRelayScope.launch { _bleEventsRecording.tryEmit(ev) }
        }
    }

    /**
     * 单次 Audio 特征写入允许的最大字节数（含 4 字节头）：`min(ATT 单次写 payload 上限, PROTO_AUDIO_MAX_LEN)`。
     * 优先读 GATT 当前 MTU（API 21+ [BluetoothGatt.getMtu]），避免仅在 [onMtuChanged] 写入 [negotiatedAttMtu] 之前发 TTS 时仍按 23 分片。
     */
    @SuppressLint("MissingPermission")
    private fun effectiveAttMtuForUplink(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val g = currentGatt ?: return negotiatedAttMtu.coerceAtLeast(23)
                val m = BluetoothGatt::class.java.getMethod("getMtu").invoke(g) as Int
                if (m > 23) return m
            } catch (_: Exception) {
            }
        }
        return negotiatedAttMtu.coerceAtLeast(23)
    }

    private fun maxAudioWritePayloadBytes(): Int {
        val mtu = effectiveAttMtuForUplink()
        val attMaxWritePayload = (mtu - 3).coerceAtLeast(20)
        return minOf(attMaxWritePayload, 512)
    }

    /**
     * 构造一条或多条带 4 字节 `proto_audio_header_t` 的 Audio 特征写入（必要时按 MTU 分片）。
     */
    private fun buildUplinkOpusBlePackets(opusPayload: ByteArray): List<ByteArray> {
        val headerSize = 4
        val protoAudioMaxLen = 512
        val maxWrite = maxAudioWritePayloadBytes()
        val maxPayloadPerPacket = maxOf(0, minOf(maxWrite - headerSize, protoAudioMaxLen - headerSize))
        if (maxPayloadPerPacket <= 0) return emptyList()
        if (opusPayload.size <= maxPayloadPerPacket) {
            return listOf(singleUplinkOpusPacket(opusPayload, flags = 0x00, totalOpusLength = opusPayload.size))
        }
        val out = ArrayList<ByteArray>()
        var offset = 0
        var isFirst = true
        while (offset < opusPayload.size) {
            val remaining = opusPayload.size - offset
            val chunkSize = minOf(remaining, maxPayloadPerPacket)
            val isLast = offset + chunkSize >= opusPayload.size
            val flags = when {
                isFirst -> {
                    isFirst = false
                    0x01
                }
                isLast -> 0x03
                else -> 0x02
            }
            val chunk = opusPayload.copyOfRange(offset, offset + chunkSize)
            out.add(singleUplinkOpusPacket(chunk, flags = flags, totalOpusLength = opusPayload.size))
            offset += chunkSize
        }
        return out
    }

    private fun singleUplinkOpusPacket(payloadChunk: ByteArray, flags: Int, totalOpusLength: Int): ByteArray {
        val packet = ByteArray(4 + payloadChunk.size)
        packet[0] = 0x02 // PROTO_AUDIO_TYPE_HFP_UP
        packet[1] = (flags and 0xFF).toByte()
        packet[2] = (totalOpusLength and 0xFF).toByte()
        packet[3] = ((totalOpusLength shr 8) and 0xFF).toByte()
        System.arraycopy(payloadChunk, 0, packet, 4, payloadChunk.size)
        return packet
    }

    override fun onIncomingCallGate(call: IncomingCall): Boolean {
        val uid = call.bleUid
        val sid = call.bleSid
        val store = AbnormalCallRecordStore.getInstance(context)
        when {
            CallIncomingGateState.activeMode == "standby" -> {
                Log.i(INCOMING_AI_CHAIN_TAG, "gate deny uid=$uid reason=standby -> ignore")
                store.append("standby")
                sendIgnoreIncomingCall(uid, sid)
                return false
            }
            OutboundDialRiskControl.isEmergencyNumber(call.number) -> {
                Log.i(INCOMING_AI_CHAIN_TAG, "gate deny uid=$uid reason=emergency number=${call.number}")
                store.append("emergency_blocked")
                sendIgnoreIncomingCall(uid, sid)
                return false
            }
            CallIncomingGateState.contactPassthroughActive -> {
                Log.i(INCOMING_AI_CHAIN_TAG, "gate deny uid=$uid reason=contactPassthroughActive -> ignore")
                sendIgnoreIncomingCall(uid, sid)
                return false
            }
            CallIncomingGateState.ignoredContactUids.contains(uid) -> {
                Log.i(INCOMING_AI_CHAIN_TAG, "gate deny uid=$uid reason=ignoredContactUids -> ignore")
                sendIgnoreIncomingCall(uid, sid)
                return false
            }
            call.isContact -> {
                Log.i(INCOMING_AI_CHAIN_TAG, "gate deny uid=$uid reason=contact_passthrough isContact=true -> ignore")
                store.append("contact_passthrough")
                CallIncomingGateState.contactPassthroughActive = true
                CallIncomingGateState.ignoredContactUids.add(uid)
                sendIgnoreIncomingCall(uid, sid)
                return false
            }
            else -> {
                Log.i(INCOMING_AI_CHAIN_TAG, "gate allow uid=$uid sid=$sid isContact=${call.isContact}")
                return true
            }
        }
    }
    // endregion

    fun setSavedAutoConnectAddress(address: String?) {
        savedAutoConnectAddress = address?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun autoConnectToSaved(savedAddress: String?) {
        setSavedAutoConnectAddress(savedAddress)
        val addr = savedAutoConnectAddress ?: return
        bleScope.launch {
            delay(200)
            if (adapter?.state != BluetoothAdapter.STATE_ON) return@launch
            if (_connectedAddress.value == addr && _isReady.value) return@launch
            if (_connectingAddress.value != null) return@launch
            val known = deviceMap[addr]
            if (known != null) {
                connect(known)
                return@launch
            }
            // 冷启动后 deviceMap 为空时仅 connectGatt，在部分 ROM 上易失败；系统「已连接蓝牙」多为经典音频链路，不等于 GATT。
            reconnectScanEnabled = true
            Log.i(
                TAG,
                "[BLE] autoConnect: no cached scan row, starting reconnect scan; " +
                    "${RECONNECT_SCAN_FALLBACK_MS}ms fallback to direct GATT addr=$addr"
            )
            startScanning()
            if (reconnectScanFallbackJob?.isActive != true) {
                reconnectScanFallbackJob =
                    bleScope.launch {
                        delay(RECONNECT_SCAN_FALLBACK_MS)
                        if (_connectedAddress.value == addr && _isReady.value) return@launch
                        if (_connectingAddress.value != null) return@launch
                        Log.i(TAG, "[BLE] autoConnect: scan did not attach, trying direct connectGatt addr=$addr")
                        reconnectScanEnabled = false
                        stopScanning()
                        delay(POST_STOP_SCAN_BEFORE_GATT_MS)
                        try {
                            val dev = adapter?.getRemoteDevice(addr) ?: return@launch
                            _connectingAddress.value = addr
                            _lastError.value = null
                            val g =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    dev.connectGatt(
                                        context,
                                        false,
                                        gattCallback,
                                        BluetoothDevice.TRANSPORT_LE
                                    )
                                } else {
                                    @Suppress("DEPRECATION")
                                    dev.connectGatt(context, false, gattCallback)
                                }
                            currentGatt = g
                        } catch (_: SecurityException) {
                            _lastError.value = "Bluetooth permission required"
                            _connectingAddress.value = null
                        }
                    }
            }
        }
    }

    private fun resetConnectionCharacteristics() {
        mtuNegotiated = false
        negotiatedAttMtu = 23
        ctrlChar = null
        audioChar = null
        otaChar = null
        preloadChar = null
        ctrlNotifyReady = false
        ctrlHasEverReceivedValue = false
        notifyPhase = NotifyPhase.IDLE
        writeQueue.clear()
        writeInProgress = false
        pumpRetryJob?.cancel()
        pumpRetryJob = null
        pumpBackoffMs = 64L
        kvChannelReadyJob?.cancel()
        kvChannelReadyJob = null
        mtuFallbackJob?.cancel()
        mtuFallbackJob = null
        awaitingMtuForNotifyPipeline = false
        notifyPipelineBeginRequested = false
        isReadyCache = false
        didPostConnectHandshake = false
        _isCtrlReady.value = false
        _isAudioReady.value = false
        _isKvReady.value = false
        _deviceANCSEnabled.value = null
        _deviceBattery.value = null
        _deviceCharging.value = null
        _deviceInfoCounter.value = null
        _deviceFirmwareVersion.value = null
        _deviceHfpState.value = null
        _deviceBleBondState.value = null
        _deviceChipName.value = null
        _deviceLEDEnabled.value = null
        _deviceLEDBrightness.value = null
        _devicePA20LevelHigh.value = null
        _flashdbUsage.value = null
        _flashdbLastResult.value = null
        _flashdbLastMessage.value = null
        _deviceDiagnostics.value = null
        _mcuCrashLogState.value = McuCrashLogState.Idle
        _currentCallSid.value = null
        _activeCallSession.value = null
        _runtimeMCUDeviceID.value = null
        _pendingDeviceStrategy.value = null
        _mcuRegDumpState.value = McuRegDumpState.Idle
        regDumpSession = null
        kvSync.resetOnDisconnect()
        otaDirectQueue.abortDueToDisconnect()
        preloadDirectQueue.abortDueToDisconnect()
        audioWriter.dropAll("disconnect")
        speedTestController.stopSpeedTest()
        callRateMonitor.stop("reset_connection_state")
        audioRxCount = 0
        audioRxBytes = 0
        audioTxPackets = 0
        audioTxBytes = 0
        lastAudioRxLogMs = 0L
        audioDownlinkReassembler.reset()
        ackTracker.clearAll()
        ctrlJsonBuffer.clear()
        emptyGattAfterDiscoveryCount = 0
        pendingIncomingAiAudioStart = false
        incomingAiAudioStartCommandAcked = false
        incomingAiAudioStartAcked = false
        incomingAiAudioStartRetryJob?.cancel()
        incomingAiAudioStartRetryJob = null
    }

    private fun resetGattState() {
        hfpPolicyRetryJob?.cancel()
        hfpPolicyRetryJob = null
        lastAppliedHfpPolicyMode = null
        lastSemiHfpCommand = null
        _connectingAddress.value = null
        _connectedAddress.value = null
        _isReady.value = false
        _sessionHadReadyCtrl.value = false
        isReadyCache = false
        currentGatt = null
        resetConnectionCharacteristics()
        _deviceANCSEnabled.value = null
    }

    private fun logBleGattCharProperties(name: String, c: BluetoothGattCharacteristic?) {
        if (c == null) {
            Log.i(TAG, "[BLE] $name: missing")
            return
        }
        val p = c.properties
        val parts = listOfNotNull(
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) "read" else null,
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) "write" else null,
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) "write_no_response" else null,
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) "notify" else null,
            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) "indicate" else null,
        )
        Log.i(TAG, "[BLE] $name properties=${parts.joinToString(",")}")
    }

    /** 与 iOS `didDiscoverCharacteristics` 中 ota 写模式日志对齐（Android 无与 CoreBluetooth 完全相同的 max 写长度 API，仅记 fast）。 */
    private fun logOtaWriteModeHint() {
        val o = otaChar ?: return
        val p = o.properties
        val canFastWrite = (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        Log.i(TAG, "[BLE] ota write mode: fast=$canFastWrite")
    }

    /** 与 iOS `didUpdateValueFor` / notify 路径共用：解析 ctrl UTF-8 行并更新就绪状态。 */
    private fun handleCtrlCharacteristicPayload(value: ByteArray?) {
        ctrlHasEverReceivedValue = true
        val v = value ?: return
        ctrlJsonBuffer.append(String(v, Charsets.UTF_8))
        while (true) {
            val s = ctrlJsonBuffer.toString()
            val nl = s.indexOf('\n')
            if (nl >= 0) {
                val line = s.substring(0, nl).trim()
                ctrlJsonBuffer.delete(0, nl + 1)
                if (line.isNotBlank()) dispatchControlLine(line)
                continue
            }
            break
        }
        val rest = ctrlJsonBuffer.toString().trim()
        if (rest.isEmpty()) {
            ctrlJsonBuffer.clear()
        } else {
            try {
                JSONObject(rest)
                dispatchControlLine(rest)
                ctrlJsonBuffer.clear()
            } catch (_: Exception) {
                if (ctrlJsonBuffer.length > 65536) {
                    Log.w(TAG, "[BLE] ctrl JSON buffer overflow, clearing")
                    ctrlJsonBuffer.clear()
                }
            }
        }
        setReadyIfPossible()
    }

    private fun beginNotifyPipeline(gatt: BluetoothGatt) {
        if (notifyPipelineBeginRequested) {
            Log.i(TAG, "[BLE] beginNotifyPipeline skipped (already started)")
            return
        }
        if (ctrlChar == null) {
            _lastError.value = "Control characteristic missing"
            _isReady.value = false
            return
        }
        notifyPipelineBeginRequested = true
        notifyPhase = NotifyPhase.CTRL
        startNotifyPhase(gatt, NotifyPhase.CTRL)
    }

    private fun startNotifyPhase(gatt: BluetoothGatt, phase: NotifyPhase) {
        notifyPhase = phase
        val char = when (phase) {
            NotifyPhase.CTRL -> ctrlChar
            NotifyPhase.AUDIO -> audioChar
            NotifyPhase.OTA -> otaChar
            else -> null
        }
        if (char == null) {
            when (phase) {
                NotifyPhase.CTRL -> {
                    _lastError.value = "Control char null"
                    _isReady.value = false
                }
                NotifyPhase.AUDIO -> startNotifyPhase(gatt, NotifyPhase.OTA)
                NotifyPhase.OTA -> notifyPhase = NotifyPhase.DONE
                else -> {}
            }
            return
        }
        Log.i(TAG, "[BLE] ${phase.name.lowercase()} requesting notify")
        gatt.setCharacteristicNotification(char, true)
        val desc = char.getDescriptor(BleGattUuids.CCCD)
        if (desc == null) {
            if (phase == NotifyPhase.CTRL) {
                _lastError.value = "Missing CCCD on ctrl"
                _isReady.value = false
            } else if (phase == NotifyPhase.AUDIO) {
                startNotifyPhase(gatt, NotifyPhase.OTA)
            } else {
                notifyPhase = NotifyPhase.DONE
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(desc)
        }
    }

    private fun advanceNotifyPipelineAfterCtrl(gatt: BluetoothGatt) {
        if (audioChar != null) {
            startNotifyPhase(gatt, NotifyPhase.AUDIO)
        } else if (otaChar != null) {
            startNotifyPhase(gatt, NotifyPhase.OTA)
        } else {
            notifyPhase = NotifyPhase.DONE
            tryPostConnectHandshakeAfterNotifyPipeline()
        }
    }

    /**
     * 仅在 ctrl/audio/ota 的 CCCD 全部写完后发 sync_time + get_info。
     * 若仅靠 [ctrlHasEverReceivedValue] 提前把 [isReady] 置 true 就握手，会与尚未结束的
     * writeDescriptor 争用 Android GATT 单队列，导致 ctrl 写失败、无 JSON 下行（与 log 中 KV/get_info 全无响应一致）。
     */
    private fun tryPostConnectHandshakeAfterNotifyPipeline() {
        if (!ctrlNotifyReady || didPostConnectHandshake) return
        postConnectHandshake()
    }

    /**
     * 远端服务表变更（onServiceChanged）后：丢弃旧 characteristic 引用与写队列，保留已展示的 device_info 等 UI 状态。
     */
    private fun prepareGattForServiceRediscovery() {
        mtuFallbackJob?.cancel()
        mtuFallbackJob = null
        awaitingMtuForNotifyPipeline = false
        notifyPipelineBeginRequested = false
        ctrlChar = null
        audioChar = null
        otaChar = null
        preloadChar = null
        ctrlNotifyReady = false
        ctrlHasEverReceivedValue = false
        notifyPhase = NotifyPhase.IDLE
        writeQueue.clear()
        writeInProgress = false
        pumpRetryJob?.cancel()
        pumpRetryJob = null
        pumpBackoffMs = 64L
        didPostConnectHandshake = false
        _isCtrlReady.value = false
        _isAudioReady.value = false
        _isKvReady.value = false
        ackTracker.clearAll()
        kvSync.onGattServiceTableChanged()
        otaDirectQueue.abortDueToDisconnect()
        preloadDirectQueue.abortDueToDisconnect()
        audioWriter.dropAll("gatt_service_change")
    }

    /** 调试：打印当前 GATT 上已缓存的 service 列表（与 [BleGattUuids.SERVICE] 对照）。 */
    private fun logGattServiceTable(gatt: BluetoothGatt, reason: String) {
        val list = try {
            gatt.services
        } catch (e: Exception) {
            Log.w(TAG, "[BLE] GATT table ($reason): gatt.services threw ${e.message}")
            return
        }
        val n = list?.size ?: 0
        Log.w(
            TAG,
            "[BLE] GATT table ($reason) addr=${gatt.device.address} " +
                "expected=${BleGattUuids.SERVICE} count=$n"
        )
        list?.forEachIndexed { i, s ->
            Log.w(
                TAG,
                "[BLE]   svc[$i] uuid=${s.uuid} chars=${s.characteristics.size} " +
                    "incls=${s.includedServices.size}"
            )
        }
    }

    /** 隐藏 API：清 GATT 缓存，便于 Service Changed 后重新 discover。 */
    private fun refreshGattCache(gatt: BluetoothGatt): Boolean {
        return try {
            val m = BluetoothGatt::class.java.getMethod("refresh")
            (m.invoke(gatt) as? Boolean) == true
        } catch (e: Throwable) {
            Log.w(TAG, "[BLE] gatt.refresh() failed: ${e.message}")
            false
        }
    }

    /**
     * 在 [onServicesDiscovered] 成功后设置 characteristic 引用并启动 notify 管线。
     * 部分机型 `onServiceChanged` 后首次 [BluetoothGatt.getService] 返回 null（缓存延迟）；
     * 若 [BluetoothGatt.getServices] 为空（count=0），需 [refreshGattCache] 再 discover。
     * 短暂延时后重试一次，与 iOS 侧无需显式处理服务缓存的行为等效。
     */
    private fun applyDiscoveredServices(gatt: BluetoothGatt) {
        val service = gatt.getService(BleGattUuids.SERVICE)
        val svcCount = gatt.services?.size ?: 0
        if (service == null && svcCount == 0 && emptyGattAfterDiscoveryCount < MAX_EMPTY_GATT_REFRESH) {
            emptyGattAfterDiscoveryCount++
            Log.w(
                TAG,
                "[BLE] onServicesDiscovered ok but services empty — refresh+rediscover " +
                    "$emptyGattAfterDiscoveryCount/$MAX_EMPTY_GATT_REFRESH"
            )
            bleScope.launch {
                delay(50)
                if (!syncGattIfSameDevice(gatt)) return@launch
                val refreshed = refreshGattCache(gatt)
                Log.i(TAG, "[BLE] gatt.refresh() after empty table=$refreshed")
                delay(200)
                if (!syncGattIfSameDevice(gatt)) return@launch
                val ok = gatt.discoverServices()
                Log.i(TAG, "[BLE] discoverServices after empty-cache refresh=$ok")
            }
            return
        }
        if (service == null) {
            logGattServiceTable(gatt, "getService null right after onServicesDiscovered")
            Log.w(TAG, "[BLE] service not found after discovery, retry in 200ms")
            bleScope.launch {
                delay(200)
                if (!syncGattIfSameDevice(gatt)) return@launch
                var svc = gatt.getService(BleGattUuids.SERVICE)
                if (svc == null) {
                    logGattServiceTable(gatt, "still null after 200ms")
                    Log.w(TAG, "[BLE] service still null after 200ms — wait for GATT cache (onServiceChanged race)")
                    delay(400)
                    if (!syncGattIfSameDevice(gatt)) return@launch
                    svc = gatt.getService(BleGattUuids.SERVICE)
                }
                if (svc == null) {
                    logGattServiceTable(gatt, "still null after 400ms (final)")
                    Log.e(TAG, "[BLE] service still not found after retry")
                    _lastError.value = "EchoCard service not found"
                    _isReady.value = false
                    return@launch
                }
                setupCharacteristicsAndBeginPipeline(gatt, svc)
            }
            return
        }
        setupCharacteristicsAndBeginPipeline(gatt, service)
    }

    private fun setupCharacteristicsAndBeginPipeline(
        gatt: BluetoothGatt,
        service: android.bluetooth.BluetoothGattService
    ) {
        emptyGattAfterDiscoveryCount = 0
        ctrlChar = service.getCharacteristic(BleGattUuids.CTRL)
        audioChar = service.getCharacteristic(BleGattUuids.AUDIO)
        otaChar = service.getCharacteristic(BleGattUuids.OTA)
        preloadChar = service.getCharacteristic(BleGattUuids.PRELOAD)
        Log.i(
            TAG,
            "GATT chars ctrl=${ctrlChar != null} audio=${audioChar != null} ota=${otaChar != null} preload=${preloadChar != null}"
        )
        logBleGattCharProperties("ctrl", ctrlChar)
        logBleGattCharProperties("audio", audioChar)
        logBleGattCharProperties("ota", otaChar)
        logBleGattCharProperties("preload", preloadChar)
        logOtaWriteModeHint()
        if (ctrlChar == null) {
            _lastError.value = "Control characteristic missing"
            _isReady.value = false
            return
        }
        if (mtuNegotiated) {
            Log.i(TAG, "[BLE] MTU already negotiated, skip requestMtu → begin notify pipeline")
            beginNotifyPipeline(gatt)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mtuFallbackJob?.cancel()
            awaitingMtuForNotifyPipeline = true
            val queued = gatt.requestMtu(247)
            if (!queued) {
                awaitingMtuForNotifyPipeline = false
                Log.w(TAG, "[BLE] MTU: requestMtu not queued, begin notify pipeline directly")
                beginNotifyPipeline(gatt)
            } else {
                mtuFallbackJob = bleScope.launch {
                    delay(800)
                    if (!syncGattIfSameDevice(gatt)) return@launch
                    if (!awaitingMtuForNotifyPipeline) return@launch
                    awaitingMtuForNotifyPipeline = false
                    Log.w(TAG, "[BLE] MTU: onMtuChanged missing; begin notify pipeline (fallback)")
                    beginNotifyPipeline(gatt)
                }
            }
        } else {
            beginNotifyPipeline(gatt)
        }
    }

    private fun setReadyIfPossible() {
        val ctrlReady = ctrlNotifyReady || ctrlHasEverReceivedValue
        val kvReady = ctrlNotifyReady || ctrlHasEverReceivedValue
        _isCtrlReady.value = ctrlNotifyReady
        _isKvReady.value = kvReady

        val transitioned = ctrlReady && !isReadyCache
        isReadyCache = ctrlReady
        _isReady.value = ctrlReady
        if (ctrlReady) {
            _sessionHadReadyCtrl.value = true
        }

        if (transitioned) {
            Log.i(TAG, "ctrl channel ready (notify or data): isReady=$ctrlReady ctrlNotify=$ctrlNotifyReady hadData=$ctrlHasEverReceivedValue")
        }
    }

    private fun postConnectHandshake() {
        if (didPostConnectHandshake) return
        didPostConnectHandshake = true
        Log.i(TAG, "notify pipeline complete → set_phone_os(android) + sync_time + get_info")
        sendCommand(
            "set_phone_os",
            JSONObject().put("os", "android"),
            expectAck = false
        )
        syncTimeToDevice()
        sendCommand("get_info", JSONObject(), expectAck = true)
        kvChannelReadyJob?.cancel()
        kvChannelReadyJob = bleScope.launch {
            // PHY / onServiceChanged 常在握手后 ~200ms 内出现；延后 KV 可减少对失效特征的写入。
            delay(500)
            kvSync.onKvChannelReadyAfterHandshake()
        }
    }

    private fun syncTimeToDevice() {
        val epochMs = System.currentTimeMillis()
        val epoch = (epochMs / 1000).toInt()
        val tzMin = java.util.TimeZone.getDefault().rawOffset / 60000
        val extra = JSONObject().apply {
            put("epoch", epoch)
            put("epoch_ms", epochMs)
            put("tz_min", tzMin)
        }
        sendCommand("sync_time", extra, expectAck = false)
    }

    private fun dispatchControlLine(line: String) {
        try {
            val raw = JSONObject(line)
            val merged = BleJsonHelpers.mergeParams(raw)
            controlDispatch.dispatchLine(merged)
        } catch (_: Exception) { }
    }

    private fun enqueueWrite(payload: ByteArray) {
        writeQueue.addLast(payload)
        pumpWrites()
    }

    private fun schedulePumpRetry() {
        pumpRetryJob?.cancel()
        pumpRetryJob = bleScope.launch {
            delay(pumpBackoffMs)
            pumpBackoffMs = minOf(pumpBackoffMs * 2, 500L)
            pumpRetryJob = null
            pumpWrites()
        }
    }

    private fun pumpWrites() {
        if (writeInProgress) return
        val gatt = currentGatt ?: return
        val c = ctrlChar ?: return
        val data = writeQueue.firstOrNull() ?: return
        writeInProgress = true
        @Suppress("DEPRECATION")
        c.value = data
        @Suppress("DEPRECATION")
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        val queued = gatt.writeCharacteristic(c)
        if (!queued) {
            writeInProgress = false
            Log.w(TAG, "ctrl writeCharacteristic not queued (GATT busy); retry in ${pumpBackoffMs}ms")
            schedulePumpRetry()
            return
        }
        pumpBackoffMs = 64L
    }

    /**
     * 与 iOS `sendCommand` 对齐：JSON-RPC + 顶层 `cmd`/参数，ACK 追踪。
     */
    fun sendCommand(
        method: String,
        params: JSONObject = JSONObject(),
        expectAck: Boolean = true
    ) {
        val id = if (expectAck) {
            val i = nextJsonRpcId++
            if (nextJsonRpcId == Int.MAX_VALUE) nextJsonRpcId = 1
            i
        } else null
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
            put("cmd", method)
            if (id != null) put("id", id)
        }
        mergeCmdIntoPayload(payload, method, params)
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        if (expectAck && id != null) {
            val sidFromParams =
                if (params.has("sid") && !params.isNull("sid")) {
                    params.optLong("sid") and 0xFFFFFFFFL
                } else null
            ackTracker.register(
                method,
                sidFromParams,
                id,
                bytes,
                BleJsonHelpers.shouldAutoRetryAck(method)
            )
        }
        enqueueWrite(bytes)
    }

    private fun mergeCmdIntoPayload(payload: JSONObject, method: String, params: JSONObject) {
        /*
         * 与 iOS `sendCommand`（commit a8c490ac）对齐：`preload_*` / `play_filler` 这批新命令
         * 只让业务字段留在 `params` 里，**不**镜像到 root，否则 `preload_begin` 的
         * `voice_id/hash/count/total_bytes/meta` 会被 MCU 处理器按 root-first 解析冲突，且
         * 会把单帧 JSON 推过 ATT_MTU（固件日志里的 silent drop 就是被 CoreBluetooth/系统栈丢掉）。
         */
        val mirrorParamsToRoot = !method.startsWith("preload_") && method != "play_filler"
        if (mirrorParamsToRoot) {
            val it = params.keys()
            while (it.hasNext()) {
                val key = it.next()
                if (key == "uid" || key == "sid") continue
                payload.put(key, params.get(key))
            }
        }
        if (params.has("uid")) payload.put("uid", params.get("uid"))
        if (params.has("sid")) payload.put("sid", params.get("sid"))
        payload.put("cmd", method)
    }

    /**
     * 与 iOS `sendKVCommand` 对齐：KV 走独立 JSON-RPC id 序列。
     */
    /**
     * 与 iOS `sendKVChunkedSet`（`CallMateBLEClient+Strategy.swift`）对齐。
     */
    private fun sendKVChunkedSet(key: String, data: ByteArray) {
        val chunkSize = 96
        val totalLen = data.size
        val chunks = maxOf(1, (totalLen + chunkSize - 1) / chunkSize)
        sendKvCommand(
            JSONObject().apply {
                put("cmd", "kv_set_begin")
                put("key", key)
                put("total_len", totalLen)
                put("chunks", chunks)
            },
            true
        )
        var offset = 0
        var index = 0
        while (offset < totalLen) {
            val n = minOf(chunkSize, totalLen - offset)
            val chunk = data.copyOfRange(offset, offset + n)
            val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
            sendKvCommand(
                JSONObject().apply {
                    put("cmd", "kv_set_chunk")
                    put("key", key)
                    put("index", index)
                    put("data_b64", b64)
                },
                false
            )
            offset += n
            index++
        }
        sendKvCommand(
            JSONObject().apply {
                put("cmd", "kv_set_end")
                put("key", key)
            },
            true
        )
    }

    fun sendKvCommand(params: JSONObject, expectResponse: Boolean = true) {
        val cmd = params.optString("cmd", "").ifEmpty { return }
        val inner = JSONObject()
        val keys = params.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k != "cmd") inner.put(k, params.get(k))
        }
        val rpcId = if (expectResponse) {
            val i = nextKvJsonRpcId++
            if (nextKvJsonRpcId == Int.MAX_VALUE) nextKvJsonRpcId = 1
            i
        } else null
        val envelope = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", cmd)
            put("params", inner)
            if (rpcId != null) put("id", rpcId)
        }
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        enqueueWrite(bytes)
    }

    fun updateBluetoothState() {
        _bluetoothState.value = adapter?.state ?: BluetoothAdapter.STATE_OFF
    }

    fun setBindingScanModeEnabled(enabled: Boolean) {
        if (enabled) {
            Log.i(
                TAG,
                "[BLE] bindingScan enable: stop current scan + clear cache " +
                    "(reconnect=$reconnectScanEnabled active=$bleScanActive)"
            )
            reconnectScanEnabled = false
            reconnectScanFallbackJob?.cancel()
            reconnectScanFallbackJob = null
            stopScanning()
            clearDiscoveredDevices()
            bindingScanModeEnabled = true
            return
        }
        Log.i(TAG, "[BLE] bindingScan disable")
        bindingScanModeEnabled = false
        stopScanning()
    }

    fun startScanning() {
        if (!bindingScanModeEnabled && !reconnectScanEnabled) return
        if (bleScanActive) {
            Log.i(TAG, "[BLE] startScan skipped: already active")
            return
        }
        val sc = leScanner ?: run {
            _lastError.value = "BLE scanner not available"
            return
        }
        if (adapter?.state != BluetoothAdapter.STATE_ON) {
            updateBluetoothState()
            return
        }
        _lastError.value = null
        resetScanDebugState()
        scanSessionId += 1
        scanSessionStartedAtMs = System.currentTimeMillis()
        val filters = emptyList<android.bluetooth.le.ScanFilter>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            Log.i(
                TAG,
                "[BLE] startScan: session=$scanSessionId ${scanEnvironmentSummary()} cache=${deviceMap.size}"
            )
            sc.startScan(filters, settings, scanCallback)
            bleScanActive = true
            startScanWatchdog(scanSessionId)
        } catch (e: SecurityException) {
            stopScanWatchdog()
            _lastError.value = "Bluetooth permission required"
            Log.w(TAG, "[BLE] startScan SecurityException ${scanEnvironmentSummary()} message=${e.message}")
        }
    }

    fun stopScanning() {
        if (!bleScanActive) return
        bleScanActive = false
        stopScanWatchdog()
        try {
            leScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
        Log.i(
            TAG,
            "[BLE] stopScan: durationMs=${System.currentTimeMillis() - scanSessionStartedAtMs} " +
                "${scanStatsSummary()} binding=$bindingScanModeEnabled reconnect=$reconnectScanEnabled " +
                "published=${_devices.value.size}"
        )
        if (bindingScanModeEnabled) {
            publishBindingDevices()
        }
    }

    fun connect(device: DiscoveredDevice) {
        if (adapter?.state != BluetoothAdapter.STATE_ON) return
        if (reconnectScanEnabled) {
            reconnectScanEnabled = false
            reconnectScanFallbackJob?.cancel()
            reconnectScanFallbackJob = null
            stopScanning()
        }
        val bluetoothDevice = adapter.getRemoteDevice(device.address)
        _connectingAddress.value = device.address
        _lastError.value = null
        try {
            val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                bluetoothDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                bluetoothDevice.connectGatt(context, false, gattCallback)
            }
            currentGatt = g
        } catch (_: SecurityException) {
            _lastError.value = "Bluetooth permission required"
            _connectingAddress.value = null
        }
    }

    fun disconnect() {
        reconnectScanFallbackJob?.cancel()
        reconnectScanFallbackJob = null
        reconnectScanEnabled = false
        if (bleScanActive && !bindingScanModeEnabled) {
            stopScanning()
        }
        currentGatt?.disconnect()
        currentGatt?.close()
        resetGattState()
    }

    fun disconnectAndClearSaved() {
        savedAutoConnectAddress = null
        disconnect()
    }

    /**
     * 与 iOS `CallMateBLEClient.ensureConnectionRecovered` 对齐：在已保存绑定地址时主动触发回连。
     */
    fun ensureConnectionRecovered(reason: String) {
        val addr = savedAutoConnectAddress ?: return
        Log.i(TAG, "[BLE] ensureConnectionRecovered reason=$reason addr=$addr")
        autoConnectToSaved(addr)
    }

    /** 与 iOS `forceReconnect` 对齐：断开后自动回连已保存地址。 */
    fun forceReconnect() {
        val addr = savedAutoConnectAddress ?: _connectedAddress.value
        if (addr.isNullOrBlank()) {
            Log.w(TAG, "forceReconnect: no saved or connected address")
            return
        }
        Log.i(TAG, "forceReconnect: addr=$addr")
        bleScope.launch {
            disconnect()
            delay(400)
            autoConnectToSaved(addr)
        }
    }

    fun clearSavedAndDisconnect(onClear: suspend (String?) -> Unit) {
        bleScope.launch {
            savedAutoConnectAddress = null
            disconnect()
            onClear(null)
        }
    }

    fun getDeviceByAddress(address: String): DiscoveredDevice? = deviceMap[address]

    /** 与 iOS `loadOTAPackets` 对齐：预构建分片写入 OTA 特征。 */
    fun loadOtaPackets(packets: List<ByteArray>) {
        otaDirectQueue.loadPackets(packets)
    }

    /** 与 iOS `resetOTADirectQueue` 对齐：开始新一轮 OTA 上传前清空队列。 */
    fun resetOtaDirectQueue() {
        otaDirectQueue.reset()
    }

    /**
     * 与 iOS `getOTAQueueDepth()` 对齐：剩余 OTA 包数，断线中止时为 -1。
     */
    fun getOtaQueueDepthOrAbort(): Int = otaDirectQueue.remainingCountOrAborted()

    /**
     * 与 iOS `otaMaxChunkPayloadBytes` 对齐：单包固件 payload 上限（不含 16 字节 `fw_chunk_t` 头）。
     */
    @SuppressLint("MissingPermission")
    fun otaMaxChunkPayloadBytes(): Int {
        otaChar ?: return 0
        val mtu = effectiveAttMtuForUplink()
        val maxPacket = (mtu - 3).coerceAtLeast(20)
        return maxOf(0, maxPacket - 16)
    }

    /** OTA 特征已发现且 GATT 存活（与 iOS `isOTAReady` 类似）。 */
    fun isOtaReady(): Boolean = currentGatt != null && otaChar != null

    /**
     * 与 iOS `CallMateBLEClient+Preload.loadPreloadPackets` 对齐：走独立的 preload
     * characteristic 把 `fw_chunk_t` 形帧发给 MCU；`buildOtaChunkPacket` 可直接复用。
     */
    fun loadPreloadPackets(packets: List<ByteArray>) {
        preloadDirectQueue.loadPackets(packets)
    }

    fun resetPreloadDirectQueue() {
        preloadDirectQueue.reset()
    }

    fun getPreloadQueueDepthOrAbort(): Int = preloadDirectQueue.remainingCountOrAborted()

    /** 与 iOS `preloadMaxChunkPayloadBytes` 对齐：单包 preload payload 上限（不含 16 字节头）。 */
    @SuppressLint("MissingPermission")
    fun preloadMaxChunkPayloadBytes(): Int {
        preloadChar ?: return 0
        val mtu = effectiveAttMtuForUplink()
        val maxPacket = (mtu - 3).coerceAtLeast(20)
        return maxOf(0, maxPacket - 16)
    }

    /** 与 iOS `isPreloadReady` 对齐：preload char 已发现且 GATT 存活。 */
    fun isPreloadReady(): Boolean = currentGatt != null && preloadChar != null

    fun startSpeedTest(payloadBytes: Int = 160, intervalMs: Int = 10) {
        speedTestController.startSpeedTest(payloadBytes, intervalMs)
    }

    fun stopSpeedTest() {
        speedTestController.stopSpeedTest()
    }

    /** 与 iOS `stopRateMonitorForLocalTeardown` 对齐（本地挂断/测试结束等）。 */
    fun stopRateMonitorForLocalTeardown(reason: String = "local_teardown") {
        callRateMonitor.stop(reason)
    }

    fun adoptPendingDeviceStrategy() {
        val json = _pendingDeviceStrategy.value ?: return
        kvSync.adoptDeviceStrategy(json)
    }

    fun clearPendingDeviceStrategy() {
        kvSync.clearPendingDeviceStrategy()
    }

    fun pushLocalStrategyToDevice() {
        kvSync.pushLocalStrategyToDevice()
    }
}
