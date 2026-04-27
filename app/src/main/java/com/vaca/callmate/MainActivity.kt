package com.vaca.callmate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vaca.callmate.core.AppFeatureFlags
import com.vaca.callmate.core.telephony.IncomingCallDebugLogger
import com.vaca.callmate.core.telephony.SemiModeHfpTelephonyBridge
import com.vaca.callmate.features.calls.CallForegroundController
import com.vaca.callmate.features.calls.CallIncomingGateState
import com.vaca.callmate.features.calls.CallSessionStatus
import com.vaca.callmate.features.calls.CallSessionViewModel
import com.vaca.callmate.features.calls.IncomingCallSessionCoordinator
import com.vaca.callmate.features.calls.OutboundLiveSessionCoordinator
import com.vaca.callmate.features.calls.LiveFinishDeps
import com.vaca.callmate.features.calls.LiveTranscriptDeepLink
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.AppState
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.screens.BindingFlow
import com.vaca.callmate.ui.screens.LegalConsentOverlay
import com.vaca.callmate.ui.screens.LegalDocumentType
import com.vaca.callmate.ui.screens.LegalDocumentViewerDialog
import com.vaca.callmate.ui.screens.MainTabView
import com.vaca.callmate.ui.screens.OnboardingView
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.CallMateTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val activityIntentState = mutableStateOf<Intent?>(null)

    override fun onDestroy() {
        IncomingCallDebugLogger.stop(applicationContext)
        SemiModeHfpTelephonyBridge.stop(applicationContext)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityIntentState.value = intent
        enableEdgeToEdge()
        setContent {
            CallMateTheme {
                CallMateApp(activityIntentState = activityIntentState)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activityIntentState.value = intent
    }
}

@Composable
private fun CallMateApp(activityIntentState: MutableState<Intent?>) {
    val intent by activityIntentState
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context.applicationContext) }
    val scope = rememberCoroutineScope()

    /** null = 尚未从 DataStore 恢复；勿默认 Landing，否则已进主流程用户会闪一下绑定页 */
    var appState by remember { mutableStateOf<AppState?>(null) }
    var language by remember { mutableStateOf(Language.Zh) }
    val app = context.applicationContext as CallMateApplication
    val bleManager = app.bleManager
    val sessionViewModel: CallSessionViewModel = viewModel()
    val callRepository = app.callRepository

    LaunchedEffect(intent?.data) {
        val uri = intent?.data ?: return@LaunchedEffect
        if (uri.scheme == "callmate" && uri.host == "livecall") {
            LiveTranscriptDeepLink.markFromNotification()
        }
    }

    /** 勿用 collectAsState(initial = false)：会在 DataStore 首帧前误判为未同意，导致隐私弹窗闪一下。 */
    val legalAccepted by produceState<Boolean?>(initialValue = null) {
        preferences.legalAccepted.collect { value = it }
    }
    var legalDocumentOpen by remember { mutableStateOf<LegalDocumentType?>(null) }

    val mainBlePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            scope.launch {
                val addr = preferences.boundDeviceId.first()
                bleManager.setSavedAutoConnectAddress(addr)
                bleManager.autoConnectToSaved(addr)
            }
        }
    }

    /** 智能模式 HFP + 可选来电调试日志共用 `READ_PHONE_STATE`，只弹一次授权框。 */
    val readPhoneStatePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.READ_PHONE_STATE] == true) {
            SemiModeHfpTelephonyBridge.start(context.applicationContext, bleManager)
            if (AppFeatureFlags.incomingCallDebugLoggingEnabled) {
                IncomingCallDebugLogger.start(context.applicationContext)
            }
        }
    }

    LaunchedEffect(Unit) {
        language = preferences.languageFlow.first()
        appState = if (preferences.onboardingDone.first()) AppState.Main else AppState.Landing
    }

    LaunchedEffect(language) {
        app.syncOutboundQueueLanguage(language)
    }

    /** 与 iOS `ControlChannelService.shared.activate` 一致：推送控制通道（无长连接 WS）；MCU device-id / JWT 就绪后上报注册 */
    LaunchedEffect(appState, bleManager) {
        if (appState != AppState.Main) {
            app.controlChannelService.deactivate()
            return@LaunchedEffect
        }
        snapshotFlow { bleManager.runtimeMCUDeviceID.value }
            .collect {
                app.controlChannelService.activate()
            }
    }

    /**
     * 来电 AI 链路的 BLE 挂断 / Room 落库依赖 [CallSessionViewModel.liveFinishDeps]。
     * 在 Main 时始终注入；离开 Main 时仅在会话已 Idle/Ended 时清空，避免会话未结束却因路由切换丢失 hangup/落库依赖。
     */
    LaunchedEffect(language, callRepository, bleManager, preferences, sessionViewModel) {
        combine(snapshotFlow { appState }, sessionViewModel.status) { state, sessStatus ->
            state to sessStatus
        }.collect { (state, sessStatus) ->
            if (state == AppState.Main) {
                sessionViewModel.liveFinishDeps = LiveFinishDeps(
                    appContext = context.applicationContext,
                    bleManager = bleManager,
                    preferences = preferences,
                    callRepository = callRepository,
                    language = language
                )
            } else if (
                sessStatus == CallSessionStatus.Idle ||
                sessStatus == CallSessionStatus.Ended
            ) {
                sessionViewModel.liveFinishDeps = null
            }
        }
    }

    /** 前台服务：通话态 **或**（预留）控制通道需抬优先级时启；当前控制通道为推送，无 WS 建连 */
    LaunchedEffect(sessionViewModel, app.controlChannelService) {
        val appCtx = context.applicationContext
        combine(
            sessionViewModel.status,
            app.controlChannelService.foregroundBoostActive
        ) { status, controlBoost ->
            status to controlBoost
        }.collect { (status, controlBoost) ->
            CallForegroundController.sync(appCtx, status, controlBoost)
        }
    }

    /** 与 iOS `UserDefaults` 中 `callmate_active_mode` 同步到门禁（来电到达前即生效） */
    LaunchedEffect(Unit) {
        preferences.activeModeFlow.collect { CallIncomingGateState.activeMode = it }
    }

    LaunchedEffect(appState) {
        if (appState != AppState.Main) return@LaunchedEffect
        IncomingCallSessionCoordinator.start(
            context.applicationContext,
            bleManager,
            preferences,
            sessionViewModel
        )
        OutboundLiveSessionCoordinator.start(
            context.applicationContext,
            bleManager,
            preferences,
            sessionViewModel
        )
        val addr = preferences.boundDeviceId.first()
        bleManager.setSavedAutoConnectAddress(addr)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needScan =
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) !=
                    PackageManager.PERMISSION_GRANTED
            val needConnect =
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
            if (needScan || needConnect) {
                mainBlePermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                )
                return@LaunchedEffect
            }
        }
        bleManager.autoConnectToSaved(addr)
    }

    LaunchedEffect(appState, legalAccepted) {
        if (appState != AppState.Main || legalAccepted != true) {
            SemiModeHfpTelephonyBridge.stop(context.applicationContext)
            return@LaunchedEffect
        }
        val needPhone =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
                PackageManager.PERMISSION_GRANTED
        if (needPhone) {
            readPhoneStatePermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_PHONE_STATE)
            )
        } else {
            SemiModeHfpTelephonyBridge.start(context.applicationContext, bleManager)
            if (AppFeatureFlags.incomingCallDebugLoggingEnabled) {
                IncomingCallDebugLogger.start(context.applicationContext)
            }
        }
    }

    fun setLanguageAndPersist(lang: Language) {
        language = lang
        scope.launch { preferences.setLanguage(lang) }
    }

    // 透明系统栏下，根层先铺页面底色，避免状态栏区域露默认窗体色或与渐变顶色不一致
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackgroundSecondary)
    ) {
        when (val route = appState) {
            null -> Box(
                Modifier
                    .fillMaxSize()
                    .background(AppBackgroundSecondary)
            )
            AppState.Landing,
            AppState.Scanning,
            AppState.Bound -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    BindingFlow(
                        state = route,
                        onStateChange = { appState = it },
                        language = language,
                        bleManager = bleManager,
                        onBound = { address ->
                            scope.launch {
                                preferences.setBoundDeviceId(address)
                                bleManager.setSavedAutoConnectAddress(address)
                            }
                        },
                        onAdoptDeviceStrategyToMain = {
                            scope.launch { preferences.setOnboardingDone(true) }
                        }
                    )
                }
            }
            AppState.Onboarding -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    OnboardingView(
                        onComplete = {
                            appState = AppState.Main
                            scope.launch { preferences.setOnboardingDone(true) }
                        },
                        language = language,
                        bleManager = bleManager,
                        preferences = preferences,
                        outboundRepository = app.outboundRepository
                    )
                }
            }
            AppState.Main -> {
                MainTabView(
                    language = language,
                    setLanguage = ::setLanguageAndPersist,
                    onUnbind = {
                        scope.launch { preferences.setBoundDeviceId(null) }
                        bleManager.disconnectAndClearSaved()
                        appState = AppState.Landing
                    },
                    bleManager = bleManager,
                    sessionViewModel = sessionViewModel,
                    callRepository = callRepository,
                    preferences = preferences,
                )
            }
        }

        if (legalAccepted == false) {
            val activity = LocalContext.current as? android.app.Activity
            LegalConsentOverlay(
                language = language,
                onConfirm = {
                    scope.launch {
                        preferences.setLegalAccepted(true)
                    }
                },
                onExit = { activity?.finish() },
                onOpenUserAgreement = { legalDocumentOpen = LegalDocumentType.UserAgreement },
                onOpenPrivacyPolicy = { legalDocumentOpen = LegalDocumentType.PrivacyPolicy },
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            )
        }

        legalDocumentOpen?.let { doc ->
            LegalDocumentViewerDialog(
                document = doc,
                language = language,
                onDismiss = { legalDocumentOpen = null }
            )
        }
    }
}
