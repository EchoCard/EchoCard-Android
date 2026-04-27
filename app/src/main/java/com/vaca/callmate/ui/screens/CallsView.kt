package com.vaca.callmate.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine as flowCombine
import kotlinx.coroutines.flow.distinctUntilChanged
import com.vaca.callmate.ui.screens.device.rememberBleConnectionSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.features.calls.CallSessionStatus
import com.vaca.callmate.features.calls.LiveTranscriptDeepLink
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.CallRecord
import com.vaca.callmate.data.IncomingCall
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppBorder
import com.vaca.callmate.ui.theme.AppError
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppTextTertiary
import com.vaca.callmate.ui.screens.device.DeviceManagementHost
import com.vaca.callmate.ui.screens.device.DeviceNavPage
import com.vaca.callmate.ui.screens.device.EchoCardPermissionsCard
import com.vaca.callmate.ui.screens.device.echoCardConnectionColor
import com.vaca.callmate.ui.screens.device.echoCardConnectionLabel
import com.vaca.callmate.ui.screens.device.echoCardUiShowsConnected
import com.vaca.callmate.ui.screens.calls.TakeoverModeSelector
import com.vaca.callmate.ui.theme.AppSpacing
import com.vaca.callmate.ui.theme.AppTypography

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

sealed class CallsSubview {
    data object Dashboard : CallsSubview()
    data object Settings : CallsSubview()
    data object DeviceManagement : CallsSubview()
    data class Detail(
        val call: CallRecord,
        val isTest: Boolean = false,
        /** 与 iOS `repeatCallCount`：同号码非外呼非模拟的重复次数 */
        val repeatInboundCount: Int = 0,
    ) : CallsSubview()
    data object SimulationCalls : CallsSubview()
    data object VoiceTone : CallsSubview()
    data class LiveCall(
        val incomingCall: com.vaca.callmate.data.IncomingCall,
    ) : CallsSubview()
}

@Composable
fun CallsView(
    onUnbind: () -> Unit,
    language: Language,
    setLanguage: (Language) -> Unit,
    sessionViewModel: com.vaca.callmate.features.calls.CallSessionViewModel? = null,
    callRepository: com.vaca.callmate.data.repository.CallRepository? = null,
    /** AI 分身 Tab 点某条记录：切到本 Tab 并打开详情（与 iOS 同源 CallLog 列表一致） */
    pendingOpenCallDetail: CallRecord? = null,
    onConsumedPendingOpenCallDetail: () -> Unit = {},
    bleManager: BleManager,
    preferences: AppPreferences,
    onFullScreenSubviewChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var currentView by remember { mutableStateOf<CallsSubview>(CallsSubview.Dashboard) }
    /**
     * 与 iOS `prepareIncomingCall` vs `activatePendingCallIfNeeded` / `liveCallRequest` 对齐：
     * `incoming_call` 只更新上下文；**实时转写全屏**在 `call_state(active)` 且 sid 匹配后再进（见 [CallSessionController+BLEEvents]）。
     */
    var pendingIncomingForLive by remember { mutableStateOf<IncomingCall?>(null) }
    var selectedCall by remember { mutableStateOf<CallRecord?>(null) }
    /** 与 iOS `showSimulationView` + `selectedTestReport` 叠层一致 */
    var showSimulationOverlay by remember { mutableStateOf(false) }
    var selectedTestReport by remember { mutableStateOf<CallRecord?>(null) }
    var deviceNavPage by remember { mutableStateOf(DeviceNavPage.Main) }
    var deviceReturnFrom by remember { mutableStateOf<CallsSubview>(CallsSubview.Dashboard) }

    LaunchedEffect(currentView, showSimulationOverlay, selectedTestReport) {
        onFullScreenSubviewChange?.invoke(
            currentView is CallsSubview.LiveCall ||
                currentView is CallsSubview.DeviceManagement ||
                showSimulationOverlay ||
                selectedTestReport != null
        )
    }
    var showAiChat by remember { mutableStateOf(false) }
    /** 与 iOS 删除通话记录确认框一致：左滑点删除后再确认 */
    var callToDelete by remember { mutableStateOf<CallRecord?>(null) }
    val ble = rememberBleConnectionSnapshot(bleManager)
    val echoConnectionLabel = echoCardConnectionLabel(
        ble.btState,
        ble.bleReady,
        ble.connectedAddr,
        ble.connectingAddr,
        ble.otaUpdating,
        ble.sessionHadReadyCtrl,
        language
    )
    val echoConnectionColor = echoCardConnectionColor(
        ble.btState,
        ble.bleReady,
        ble.connectedAddr,
        ble.connectingAddr,
        ble.sessionHadReadyCtrl
    )
    val toastState = remember { mutableStateOf("") }
    val toastMessage = toastState.value
    val prefsPair by remember(preferences) {
        flowCombine(
            preferences.activeModeFlow,
            preferences.boundDeviceId,
        ) { mode, id -> mode to id }
            .distinctUntilChanged()
    }.collectAsState(initial = "semi" to null)
    val activeMode = prefsPair.first
    val boundDeviceId = prefsPair.second
    val scope = rememberCoroutineScope()
    var isConnectingEchoCard by remember { mutableStateOf(false) }
    var echoCardConnectJob by remember { mutableStateOf<Job?>(null) }
    val hasSavedBinding = !boundDeviceId.isNullOrBlank()
    val isDeviceConnected = echoCardUiShowsConnected(
        ble.btState,
        ble.bleReady,
        ble.connectedAddr,
        ble.connectingAddr,
        ble.sessionHadReadyCtrl
    )
    val shouldShowModeConnectBlocker =
        hasSavedBinding && !isDeviceConnected && !isConnectingEchoCard

    /** 待机断开 HFP；智能模式按通话态拉 HFP；全接管保持 HFP（MCU）；Echo 就绪后补发 */
    LaunchedEffect(activeMode, isDeviceConnected) {
        if (!isDeviceConnected) return@LaunchedEffect
        bleManager.applyActiveModeHfpPolicy(activeMode)
    }

    val emptyCallsFlow = remember { flowOf(emptyList<CallRecord>()) }
    val recentFlow = remember(callRepository, language) {
        callRepository?.getRecentFlow(limit = 200, language) ?: emptyCallsFlow
    }
    val allWithTranscriptFlow = remember(callRepository, language) {
        callRepository?.getAllWithTranscriptFlow(language) ?: emptyCallsFlow
    }
    /** 避免 `collectAsState(initial = emptyList())` 在 Room 首次发射前被当成「无记录」而闪一下空状态 */
    var roomCallsRecent by remember { mutableStateOf(emptyList<CallRecord>()) }
    var recentInboundListReady by remember { mutableStateOf(false) }
    LaunchedEffect(recentFlow) {
        recentInboundListReady = false
        roomCallsRecent = emptyList()
        recentFlow.collect { list ->
            roomCallsRecent = list
            recentInboundListReady = true
        }
    }
    val roomCallsAll by allWithTranscriptFlow.collectAsState(initial = emptyList())
    val inboundRecent = remember(roomCallsRecent) {
        roomCallsRecent.filter { !it.isSimulation && !it.isOutbound }
    }
    val importantInbound = remember(inboundRecent) {
        inboundRecent.filter { it.isImportant == true }
    }
    val repeatCallCountByPhone = remember(inboundRecent) {
        inboundRecent.groupingBy { it.phone }.eachCount()
    }
    val totalTokenCount = remember(inboundRecent) {
        inboundRecent.sumOf { it.tokenCount ?: 0 }
    }
    val simulationCallsOnly = remember(roomCallsAll) {
        roomCallsAll.filter { it.isSimulation }
    }
    var callListFilter by remember { mutableStateOf(DashboardCallListFilter.All) }
    val filteredCallList = remember(callListFilter, inboundRecent, importantInbound) {
        when (callListFilter) {
            DashboardCallListFilter.All -> inboundRecent
            DashboardCallListFilter.Important -> importantInbound
        }
    }
    val recentCallsPageSize = 10
    var recentCallsVisibleCount by remember { mutableIntStateOf(10) }
    var isLoadingMoreCalls by remember { mutableStateOf(false) }
    val appSessionStartMs = remember { System.currentTimeMillis() }
    var viewedRoomIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** Live 通话落库完成后跳转详情：必须在此顶层订阅，勿放在 `LiveCall` 分支内（否则先退到首页会收不到 emission，与模拟通话叠层不同） */
    val liveFinishedRecordFlow = remember(sessionViewModel) {
        sessionViewModel?.finishedCallRecord ?: flowOf<CallRecord?>(null)
    }
    val liveFinishedCallRecord by liveFinishedRecordFlow.collectAsState(initial = null)
    LaunchedEffect(liveFinishedCallRecord) {
        val rec = liveFinishedCallRecord ?: return@LaunchedEffect
        val vm = sessionViewModel ?: return@LaunchedEffect
        vm.consumeFinishedCallRecord()
        selectedCall = rec
        currentView = CallsSubview.Detail(rec)
    }

    LaunchedEffect(callListFilter) {
        recentCallsVisibleCount = minOf(recentCallsPageSize, filteredCallList.size)
        isLoadingMoreCalls = false
    }
    LaunchedEffect(filteredCallList.size) {
        when {
            filteredCallList.size <= recentCallsPageSize ->
                recentCallsVisibleCount = filteredCallList.size
            recentCallsVisibleCount > filteredCallList.size ->
                recentCallsVisibleCount = filteredCallList.size
            /** Room/Flow 晚于首次组合：先空列表把 [recentCallsVisibleCount] 置 0，数据到齐后需补满第一页 */
            recentCallsVisibleCount < recentCallsPageSize &&
                recentCallsVisibleCount < filteredCallList.size ->
                recentCallsVisibleCount = minOf(recentCallsPageSize, filteredCallList.size)
        }
    }

    val visibleCalls = remember(filteredCallList, recentCallsVisibleCount) {
        filteredCallList.take(recentCallsVisibleCount)
    }

    fun isNewCallInSession(call: CallRecord): Boolean {
        val rid = call.roomLogId ?: return false
        return call.startedAtMillis >= appSessionStartMs && rid !in viewedRoomIds
    }

    /**
     * 主动外呼时用户常在「打电话」Tab：[MainTabView] 不组合 [CallsView]，会漏掉 `call_state` SharedFlow。
     * [BleManager.outboundLivePresentation] 在 MCU 上报 active/outgoing_answered/audio_streaming 时置位，回到「接电话」Tab 后由此补进 Live。
     */
    LaunchedEffect(ble.outboundLivePresentation, sessionViewModel, bleManager) {
        if (sessionViewModel == null) return@LaunchedEffect
        if (!ble.outboundLivePresentation) return@LaunchedEffect
        if (currentView is CallsSubview.LiveCall) return@LaunchedEffect
        var p = pendingIncomingForLive
        if (p == null) {
            p = bleManager.syntheticOutboundIncomingCallOrNull()
            if (p != null) pendingIncomingForLive = p
        }
        if (p == null) return@LaunchedEffect
        val sid = bleManager.currentCallSid.value
        val expect = p.bleSid
        val isOutboundTask = p.title == "[OUTBOUND_TASK]"
        val match = isOutboundTask || (expect != null && sid != null && sid == expect)
        if (match) {
            currentView = CallsSubview.LiveCall(p)
        }
    }

    LaunchedEffect(sessionViewModel, bleManager) {
        if (sessionViewModel == null) return@LaunchedEffect
        coroutineScope {
            launch {
                bleManager.incomingCallEvents.collect { call ->
                    pendingIncomingForLive = call
                    if (currentView is CallsSubview.LiveCall) {
                        currentView = CallsSubview.LiveCall(call)
                    }
                }
            }
            launch {
                bleManager.callStateEvents.collect { st ->
                    val s = st.lowercase()
                    if (s == "active") {
                        var p = pendingIncomingForLive
                        if (p == null) {
                            p = bleManager.syntheticOutboundIncomingCallOrNull()
                            if (p != null) pendingIncomingForLive = p
                        }
                        if (p == null) return@collect
                        val sid = bleManager.currentCallSid.value
                        val expect = p.bleSid
                        val isOutboundTask = p.title == "[OUTBOUND_TASK]"
                        val match = isOutboundTask || (expect != null && sid != null && sid == expect)
                        if (match && currentView !is CallsSubview.LiveCall) {
                            currentView = CallsSubview.LiveCall(p)
                        }
                    }
                    if (s == "ended" || s == "rejected" || s == "phone_handled") {
                        pendingIncomingForLive = null
                    }
                }
            }
        }
    }

    val currentViewForDismiss = rememberUpdatedState(currentView)
    LaunchedEffect(bleManager) {
        bleManager.dismissIncomingCallUi.collect {
            pendingIncomingForLive = null
            if (currentViewForDismiss.value is CallsSubview.LiveCall) {
                currentView = CallsSubview.Dashboard
            }
        }
    }

    /** 通知点击深链：拉回 Live 转写（与 iOS 点击通知一致） */
    val pendingLiveTranscript by LiveTranscriptDeepLink.pendingOpen.collectAsState(initial = false)
    LaunchedEffect(pendingLiveTranscript, sessionViewModel) {
        if (!pendingLiveTranscript) return@LaunchedEffect
        val vm = sessionViewModel ?: run {
            LiveTranscriptDeepLink.consumed()
            return@LaunchedEffect
        }
        val ic = vm.incomingCall.value ?: pendingIncomingForLive
        val st = vm.status.value
        // 通知可能在首帧进 Live 之前点击：允许凭 pending 打开（与 iOS pendingShowLiveCall 一致）
        if (ic != null && (st != CallSessionStatus.Idle || pendingIncomingForLive != null)) {
            currentView = CallsSubview.LiveCall(ic)
        }
        LiveTranscriptDeepLink.consumed()
    }

    val atDashboardRoot = currentView is CallsSubview.Dashboard &&
        !showSimulationOverlay &&
        selectedTestReport == null
    val overlayBlockingBack = showAiChat
    BackHandler(enabled = overlayBlockingBack || !atDashboardRoot) {
        if (showAiChat) {
            showAiChat = false
            return@BackHandler
        }
        if (selectedTestReport != null) {
            selectedTestReport = null
            return@BackHandler
        }
        if (showSimulationOverlay) {
            showSimulationOverlay = false
            return@BackHandler
        }
        when (currentView) {
            is CallsSubview.DeviceManagement -> {
                when (deviceNavPage) {
                    DeviceNavPage.Diagnostics,
                    DeviceNavPage.Light -> deviceNavPage = DeviceNavPage.Advanced
                    DeviceNavPage.Advanced -> deviceNavPage = DeviceNavPage.Main
                    DeviceNavPage.Main -> {
                        currentView = deviceReturnFrom
                        deviceNavPage = DeviceNavPage.Main
                    }
                }
            }
            is CallsSubview.LiveCall -> {
                currentView = CallsSubview.Dashboard
            }
            is CallsSubview.Detail -> {
                val d = currentView as CallsSubview.Detail
                selectedCall = null
                currentView = if (d.isTest) CallsSubview.SimulationCalls else CallsSubview.Dashboard
            }
            is CallsSubview.VoiceTone -> currentView = CallsSubview.Settings
            is CallsSubview.Settings -> currentView = CallsSubview.Dashboard
            is CallsSubview.SimulationCalls -> currentView = CallsSubview.Settings
            else -> { }
        }
    }

    fun handleCallClick(call: CallRecord, isTest: Boolean = false) {
        call.roomLogId?.let { viewedRoomIds = viewedRoomIds + it }
        selectedCall = call
        val repeatInbound = if (!call.isSimulation && !call.isOutbound) {
            repeatCallCountByPhone[call.phone] ?: 0
        } else {
            0
        }
        currentView = CallsSubview.Detail(call, isTest = isTest, repeatInboundCount = repeatInbound)
    }

    LaunchedEffect(pendingOpenCallDetail) {
        val p = pendingOpenCallDetail ?: return@LaunchedEffect
        handleCallClick(p)
        onConsumedPendingOpenCallDetail()
    }

    fun deleteDashboardCall(call: CallRecord) {
        val id = call.roomLogId ?: return
        scope.launch {
            callRepository?.deleteByRoomId(id)
        }
    }

    fun loadMoreCalls() {
        if (isLoadingMoreCalls) return
        val total = filteredCallList.size
        if (recentCallsVisibleCount >= total) return
        isLoadingMoreCalls = true
        scope.launch {
            delay(700)
            recentCallsVisibleCount = minOf(recentCallsVisibleCount + recentCallsPageSize, total)
            delay(150)
            isLoadingMoreCalls = false
        }
    }

    fun stopEchoCardConnectAttempt(shouldDisconnect: Boolean) {
        echoCardConnectJob?.cancel()
        echoCardConnectJob = null
        isConnectingEchoCard = false
        if (shouldDisconnect) {
            bleManager.stopScanning()
            val connecting = bleManager.connectingAddress.value
            val connected = bleManager.connectedAddress.value
            val ready = bleManager.isReady.value
            if (connecting != null || (!ready && connected != null)) {
                bleManager.disconnect()
            }
        }
    }

    fun startEchoCardConnectAttempt() {
        if (isConnectingEchoCard) return
        echoCardConnectJob?.cancel()
        echoCardConnectJob = scope.launch {
            isConnectingEchoCard = true
            val addr = preferences.boundDeviceId.first() ?: run {
                isConnectingEchoCard = false
                return@launch
            }
            bleManager.setSavedAutoConnectAddress(addr)
            bleManager.autoConnectToSaved(addr)
            bleManager.ensureConnectionRecovered("calls_mode_connect_button")
            val deadline = System.currentTimeMillis() + 60_000
            var loopCount = 0
            while (isActive) {
                val b = bleManager.bluetoothState.value
                val r = bleManager.isReady.value
                val c = bleManager.connectedAddress.value
                if (echoCardUiShowsConnected(b, r, c, bleManager.connectingAddress.value, bleManager.sessionHadReadyCtrl.value)) {
                    stopEchoCardConnectAttempt(false)
                    return@launch
                }
                if (System.currentTimeMillis() >= deadline) {
                    stopEchoCardConnectAttempt(true)
                    return@launch
                }
                delay(3000)
                loopCount++
                if (!isActive) return@launch
                if (loopCount == 5) {
                    bleManager.forceReconnect()
                } else {
                    bleManager.ensureConnectionRecovered("calls_mode_connect_retry")
                }
            }
        }
    }

    LaunchedEffect(
        ble.bleReady,
        ble.connectedAddr,
        ble.connectingAddr,
        ble.btState,
        ble.sessionHadReadyCtrl,
        isConnectingEchoCard
    ) {
        if (!isConnectingEchoCard) return@LaunchedEffect
        if (echoCardUiShowsConnected(
                ble.btState,
                ble.bleReady,
                ble.connectedAddr,
                ble.connectingAddr,
                ble.sessionHadReadyCtrl
            )
        ) {
            stopEchoCardConnectAttempt(false)
        }
    }

    LaunchedEffect(currentView) {
        if (currentView != CallsSubview.Dashboard) {
            stopEchoCardConnectAttempt(false)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (currentView) {
        is CallsSubview.LiveCall -> {
            val live = currentView as CallsSubview.LiveCall
            val vm = sessionViewModel
            if (vm != null) {
                // 仅用 bleUid+callId：避免 PBAP/展示字段更新导致 data class LiveCall 变化、重复 startFromIncomingCall 把状态打回 Connecting（对齐 iOS 不因名字刷新重置会话）
                LaunchedEffect(live.incomingCall.bleUid, live.incomingCall.callId) {
                    vm.startFromIncomingCall(live.incomingCall)
                }
                LiveCallView(
                    language = language,
                    incomingCall = live.incomingCall,
                    viewModel = vm,
                    bleManager = bleManager,
                    callRepository = callRepository,
                    onClose = {
                        currentView = CallsSubview.Dashboard
                    }
                )
            }
        }
        is CallsSubview.Detail -> {
            val d = currentView as CallsSubview.Detail
            CallDetailScreen(
                call = d.call,
                onBack = {
                    selectedCall = null
                    currentView = when {
                        d.isTest -> CallsSubview.SimulationCalls
                        else -> CallsSubview.Dashboard
                    }
                },
                isTest = d.isTest,
                language = language,
                callRepository = callRepository,
                repeatInboundCount = d.repeatInboundCount,
                bleManager = bleManager,
                preferences = preferences,
            )
        }
        is CallsSubview.VoiceTone -> {
            VoiceToneSettingsScreen(
                language = language,
                preferences = preferences,
                bleManager = bleManager,
                onBack = { currentView = CallsSubview.Settings }
            )
        }
        is CallsSubview.DeviceManagement -> {
            DeviceManagementHost(
                language = language,
                bleManager = bleManager,
                preferences = preferences,
                page = deviceNavPage,
                onPageChange = { deviceNavPage = it },
                onExitRoot = {
                    currentView = deviceReturnFrom
                    deviceNavPage = DeviceNavPage.Main
                },
                onUnbindDevice = onUnbind,
                onRebind = onUnbind
            )
        }
        is CallsSubview.Settings -> {
            SettingsScreen(
                onBack = { currentView = CallsSubview.Dashboard },
                onTest = {
                    if (callRepository != null) {
                        showSimulationOverlay = true
                    } else {
                        currentView = CallsSubview.Settings
                    }
                },
                onSimulationCalls = { currentView = CallsSubview.SimulationCalls },
                onOpenDeviceModal = {
                    deviceReturnFrom = CallsSubview.Settings
                    deviceNavPage = DeviceNavPage.Main
                    currentView = CallsSubview.DeviceManagement
                },
                onOpenVoiceTone = { currentView = CallsSubview.VoiceTone },
                language = language,
                setLanguage = setLanguage,
                onOpenAiChat = { showAiChat = true },
                preferences = preferences,
                bleManager = bleManager
            )
        }
        is CallsSubview.SimulationCalls -> {
            AllCallsScreen(
                onBack = { currentView = CallsSubview.Settings },
                onCallClick = { handleCallClick(it, isTest = true) },
                language = language,
                calls = simulationCallsOnly,
                simulationOnly = true
            )
        }
        else -> {
    // Dashboard
    // 与 iOS `dashboardView` → `AppColors.backgroundSecondary`（F2F2F7 页面底）
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackgroundSecondary)
            .statusBarsPadding()
    ) {
        // 与 iOS `dashboardView`：`ScrollView` 包一整页，不单列表区域滚动
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("calls_dashboard_lazy"),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 16.dp,
                bottom = 116.dp
            )
        ) {
            items(1, key = { "calls_dash_top" }) { _ ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // 与 iOS `CallsView.header`（标题 + EchoCard 连接胶囊）
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "EchoCard",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTextPrimary,
                                letterSpacing = (-0.5).sp,
                                modifier = Modifier.height(38.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable { currentView = CallsSubview.Settings },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.Settings,
                                    contentDescription = null,
                                    tint = Color(0xFF6B7280),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Surface(
                            onClick = {
                                deviceReturnFrom = CallsSubview.Dashboard
                                deviceNavPage = DeviceNavPage.Main
                                currentView = CallsSubview.DeviceManagement
                            },
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                            shape = RoundedCornerShape(999.dp),
                            color = echoConnectionColor.copy(alpha = 0.08f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(echoConnectionColor)
                                )
                                Text(
                                    text = echoConnectionLabel,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = echoConnectionColor,
                                    letterSpacing = 0.sp
                                )
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = echoConnectionColor.copy(alpha = 0.65f)
                                )
                            }
                        }
                    }
                    /** 与 iOS `EchoCardPermissionsCard` 整块卡片一致 */
                    EchoCardPermissionsCard(
                        language = language,
                        bleManager = bleManager,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TakeoverModeSelector(
                        language = language,
                        activeMode = activeMode,
                        onSelectMode = { mode ->
                            scope.launch { preferences.setActiveMode(mode) }
                        },
                        onFullModeBlocked = {
                            toastState.value = t(
                                "转正后才能开启此模式，请多多使用AI分身接电话",
                                "Full mode unlocks after graduation. Keep using your AI assistant!",
                                language
                            )
                        },
                        deviceConnected = isDeviceConnected,
                        showConnectBlocker = shouldShowModeConnectBlocker,
                        onConnectNow = { startEchoCardConnectAttempt() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            items(1, key = { "calls_dash_stats" }) { _ ->
                // 与原先第二段 `Column` 的 `.padding(top = 20.dp)` 一致：接管模式与统计条间距
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                ) {
                    DashboardCallStatsBar(
                        language = language,
                        totalInboundCount = inboundRecent.size,
                        importantCount = importantInbound.size,
                        totalTokenCount = totalTokenCount,
                        filter = callListFilter,
                        onFilterChange = { callListFilter = it }
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
            if (!recentInboundListReady) {
                items(1, key = { "calls_dash_recent_loading" }) { _ ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.scale(0.85f),
                            color = AppPrimary,
                            strokeWidth = 2.dp
                        )
                    }
                }
            } else if (filteredCallList.isEmpty()) {
                items(1, key = { "calls_dash_empty" }) { _ ->
                    DashboardEmptyCalls(
                        language = language,
                        filter = callListFilter,
                        onSimulationTest = {
                            if (callRepository != null) {
                                showSimulationOverlay = true
                            } else {
                                currentView = CallsSubview.Settings
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                itemsIndexed(
                    visibleCalls,
                    key = { _, c -> c.roomLogId ?: c.id.toString() }
                ) { index, call ->
                    val showLoadMore = index == visibleCalls.lastIndex &&
                        visibleCalls.size < filteredCallList.size
                    Column(
                        modifier = Modifier.padding(
                            bottom = if (index == visibleCalls.lastIndex && !isLoadingMoreCalls) {
                                0.dp
                            } else {
                                AppSpacing.sm
                            }
                        )
                    ) {
                        DashboardCallSwipeRow(
                            call = call,
                            language = language,
                            repeatCallCount = repeatCallCountByPhone[call.phone] ?: 0,
                            isUnread = isNewCallInSession(call),
                            onClick = { handleCallClick(call) },
                            onDeleteRequest = { callToDelete = call }
                        )
                        if (showLoadMore) {
                            LaunchedEffect(recentCallsVisibleCount, call.roomLogId) {
                                loadMoreCalls()
                            }
                        }
                    }
                }
                if (isLoadingMoreCalls) {
                    items(1, key = { "calls_dash_load_more" }) { _ ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = AppSpacing.md),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.scale(0.8f),
                                color = AppPrimary,
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = t("加载更多…", "Loading more…", language),
                                style = AppTypography.labelMedium,
                                color = AppTextSecondary,
                                modifier = Modifier.padding(start = AppSpacing.sm)
                            )
                        }
                    }
                }
            }
        }

        if (isConnectingEchoCard) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(20f)
                    .background(AppBackgroundSecondary.copy(alpha = 0.95f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.scale(1.15f),
                        color = AppPrimary,
                        strokeWidth = 3.dp
                    )
                    Text(
                        text = t("正在连接 EchoCard", "Connecting EchoCard", language),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTextPrimary
                    )
                    Surface(
                        onClick = { stopEchoCardConnectAttempt(shouldDisconnect = true) },
                        shape = RoundedCornerShape(999.dp),
                        color = AppSurface,
                    ) {
                        Text(
                            text = t("取消", "Cancel", language),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTextSecondary
                        )
                    }
                }
            }
        }

        if (showAiChat) {
            FeedbackChatModal(
                onClose = { showAiChat = false },
                feedbackType = "none",
                language = language
            )
        }
        callToDelete?.let { td ->
            AlertDialog(
                onDismissRequest = { callToDelete = null },
                title = { Text(t("删除通话记录", "Delete Call Record", language)) },
                text = {
                    Text(
                        t(
                            "确定要删除这条通话记录吗？",
                            "Are you sure you want to delete this call record?",
                            language
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteDashboardCall(td)
                        callToDelete = null
                    }) {
                        Text(t("删除", "Delete", language))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { callToDelete = null }) {
                        Text(t("取消", "Cancel", language))
                    }
                }
            )
        }
        AnimatedVisibility(
            visible = toastMessage.isNotEmpty(),
            enter = fadeIn(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Card(
                modifier = Modifier.padding(32.dp),
                colors = CardDefaults.cardColors(containerColor = AppTextPrimary.copy(alpha = 0.9f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = toastMessage,
                    modifier = Modifier.padding(24.dp),
                    color = AppSurface,
                    fontSize = 14.sp
                )
            }
        }
        }
        }
    }

        AnimatedVisibility(
            visible = showSimulationOverlay,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(30f)
        ) {
            val repo = callRepository
            if (repo != null) {
                SimulationView(
                    onEnd = { call ->
                        showSimulationOverlay = false
                        selectedTestReport = call
                    },
                    language = language,
                    callRepository = repo,
                    bleManager = bleManager,
                    preferences = preferences
                )
            }
        }
        AnimatedVisibility(
            visible = selectedTestReport != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(40f)
        ) {
            selectedTestReport?.let { call ->
                CallDetailScreen(
                    call = call,
                    onBack = { selectedTestReport = null },
                    isTest = true,
                    language = language,
                    modifier = Modifier.fillMaxSize(),
                    callRepository = callRepository,
                    repeatInboundCount = 0,
                    bleManager = bleManager,
                    preferences = preferences,
                )
            }
        }
    }

    LaunchedEffect(toastMessage) {
        if (toastMessage.isNotEmpty()) {
            delay(3000)
            toastState.value = ""
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun CallsViewPreview() {
    com.vaca.callmate.ui.preview.PreviewUnavailablePlaceholder("CallsView")
}
