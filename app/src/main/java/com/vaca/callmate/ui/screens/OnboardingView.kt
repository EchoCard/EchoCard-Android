package com.vaca.callmate.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import com.vaca.callmate.CallMateApplication
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.network.BackendAuthManager
import com.vaca.callmate.core.network.SettingsVoiceRepository
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.repository.OutboundRepository
import com.vaca.callmate.features.outbound.ManualWsScene
import com.vaca.callmate.features.outbound.OutboundChatConnectionState
import com.vaca.callmate.features.outbound.OutboundChatController
import com.vaca.callmate.ui.theme.AiAvatarChatBackground
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.CallMateTheme
import com.vaca.callmate.ui.theme.Gray500
import com.vaca.callmate.ui.screens.onboarding.PostSetupReminderBottomSheet
import com.vaca.callmate.ui.screens.onboarding.VoiceCloneReaderBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private const val ONBOARDING_LOG = "OnboardingView"

/**
 * 对标 iOS `OnboardingView` + `CallSessionController(scene: .initConfig)`：
 * WebSocket `scene=init_config`，内嵌 [FeedbackChatModal]（与 [AISecView] 同栈），非脚本假数据。
 */
@Composable
fun OnboardingView(
    onComplete: () -> Unit,
    language: Language,
    bleManager: BleManager,
    preferences: AppPreferences,
    outboundRepository: OutboundRepository,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as? CallMateApplication }
    if (app == null) {
        Box(modifier.fillMaxSize()) {
            AiAvatarChatBackground(Modifier.fillMaxSize())
            Text(
                text = t("预览需完整应用上下文", "Preview requires full app context", language),
                modifier = Modifier.align(Alignment.Center),
                color = AppTextSecondary
            )
        }
        return
    }

    val sendInitConfigPrompt by preferences.initConfigSendPromptEnabledFlow.collectAsState(initial = false)

    val chatController = remember(language, sendInitConfigPrompt, app) {
        val seed =
            if (sendInitConfigPrompt) {
                null
            } else {
                listOf(
                    "user" to t("你好", "Hello", language),
                    "assistant" to t(
                        "你好！我是你的实习AI分身，可以帮你处理来电。在开始前，我需要你的授权。",
                        "Hi! I'm your trainee AI assistant. I can help the owner handle incoming calls. Before we begin, I need your authorization.",
                        language
                    ),
                )
            }
        OutboundChatController(
            context = app,
            bleManager = bleManager,
            preferences = preferences,
            outboundRepository = outboundRepository,
            queueService = app.outboundTaskQueueService,
            language = language,
            wsScene = ManualWsScene.INIT_CONFIG,
            initConfigMessagesSeed = seed,
            autoPlayIntro = true,
        )
    }

    val mcuId by bleManager.runtimeMCUDeviceID.collectAsState(initial = null)
    val conn by chatController.connectionState.collectAsState()

    var hasEverConnected by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var connectTimedOut by remember { mutableStateOf(false) }
    var showPostSetupReminder by remember { mutableStateOf(false) }

    LaunchedEffect(conn) {
        if (conn == OutboundChatConnectionState.Connected) {
            hasEverConnected = true
        }
    }

    LaunchedEffect(conn, hasEverConnected) {
        if (hasEverConnected &&
            conn != OutboundChatConnectionState.Connected &&
            conn != OutboundChatConnectionState.Connecting
        ) {
            finished = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { chatController.stop() }
    }

    LaunchedEffect(conn) {
        if (conn != OutboundChatConnectionState.Connecting) return@LaunchedEffect
        delay(10_000)
        if (chatController.connectionState.value == OutboundChatConnectionState.Connecting && !hasEverConnected) {
            connectTimedOut = true
            chatController.stop()
        }
    }

    val voiceCloneGuideCallId by chatController.voiceCloneGuideCallId.collectAsState()
    var voiceCloneSpeakerId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mcuId, preferences) {
        val id = mcuId?.trim().orEmpty()
        if (id.isEmpty()) return@LaunchedEffect
        val token = BackendAuthManager.ensureToken(preferences) ?: return@LaunchedEffect
        if (!BackendAuthManager.looksLikeJWT(token)) return@LaunchedEffect
        val data = withContext(Dispatchers.IO) {
            SettingsVoiceRepository.checkVoiceClonePurchase(id, token)
        }
        voiceCloneSpeakerId = data?.speakerId?.takeIf { it.isNotBlank() }
    }

    /** 单一流合并 + distinct，避免多 key 重组时同一帧内两次 LaunchedEffect → 双 start（与 iOS 单次 connect 对齐） */
    LaunchedEffect(bleManager, chatController) {
        combine(
            bleManager.connectedAddress,
            bleManager.runtimeMCUDeviceID,
            bleManager.isReady,
            bleManager.isCtrlReady,
            snapshotFlow { finished }
        ) { addr, mcu, rdy, ctrlRdy, fin ->
            listOf(addr, mcu, rdy, ctrlRdy, fin)
        }
            .distinctUntilChanged()
            .collect { tuple ->
                val addr = tuple[0] as String?
                val mcu = tuple[1] as String?
                val bleReady = tuple[2] as Boolean
                val ctrlReady = tuple[3] as Boolean
                val fin = tuple[4] as Boolean
                if (fin) return@collect
                val ready = !addr.isNullOrBlank() && !mcu.isNullOrBlank()
                if (!ready) return@collect
                val st = chatController.connectionState.value
                if (st == OutboundChatConnectionState.Connecting ||
                    st == OutboundChatConnectionState.Connected
                ) {
                    return@collect
                }
                Log.i(
                    ONBOARDING_LOG,
                    "[diag] gate → start() st=$st finished=$fin " +
                        "addr=${addr?.take(12)}… mcuLen=${mcu?.length ?: 0} bleReady=$bleReady ctrlReady=$ctrlReady"
                )
                chatController.start()
            }
    }

    val showConnectionFailed =
        (conn == OutboundChatConnectionState.Error && !hasEverConnected) || connectTimedOut

    fun retryConnection() {
        connectTimedOut = false
        hasEverConnected = false
        finished = false
        chatController.stop()
        chatController.start()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AiAvatarChatBackground(Modifier.fillMaxSize())

        VoiceCloneReaderBottomSheet(
            visible = !voiceCloneGuideCallId.isNullOrBlank(),
            language = language,
            speakerId = voiceCloneSpeakerId,
            deviceId = mcuId.orEmpty(),
            bluetoothId = chatController.reportBluetoothId,
            preferences = preferences,
            onDismiss = { chatController.clearVoiceCloneGuideCallId() },
            onCloneSuccess = {
                val id = voiceCloneGuideCallId
                if (!id.isNullOrBlank()) {
                    chatController.respondToGuideCard(id, true)
                }
            },
        )

        PostSetupReminderBottomSheet(
            visible = showPostSetupReminder,
            language = language,
            onStartNow = {
                showPostSetupReminder = false
                onComplete()
            },
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = t("AI 配置向导", "AI Setup Wizard", language),
                            style = TextStyle(
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTextPrimary
                            )
                        )
                        Text(
                            text = t("内容由 AI 生成", "Content generated by AI", language),
                            style = TextStyle(
                                fontSize = 11.sp,
                                color = Color(0xFF9CA3AF)
                            )
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(44.dp)
                            .clickable { onComplete() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Gray500
                        )
                    }
                }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    showConnectionFailed ->
                        ConnectionFailedView(language = language, onRetry = { retryConnection() })
                    else -> {
                        /** 与 iOS `OnboardingView` 一致：会话层不因「连接中→已连接」切换子树而销毁，避免 [FeedbackChatModal] dispose 时误调 [OutboundChatController.stop]。 */
                        Box(modifier = Modifier.fillMaxSize()) {
                            FeedbackChatModal(
                                onClose = {},
                                feedbackType = "onboarding",
                                language = language,
                                modifier = Modifier.fillMaxSize(),
                                isEmbedded = true,
                                showCloseButton = false,
                                showInnerHeaderRow = false,
                                showMessageAvatars = false,
                                outboundChat = chatController,
                                deferOutboundAutoStart = true,
                                onOnboardingAuthReject = { finished = true },
                                glassFooterReplacement =
                                    if (finished && !showPostSetupReminder) {
                                        {
                                            OnboardingFinishedFooter(
                                                language = language,
                                                onStartNow = { showPostSetupReminder = true },
                                            )
                                        }
                                    } else {
                                        null
                                    },
                            )
                            if (conn == OutboundChatConnectionState.Connecting &&
                                !hasEverConnected &&
                                !connectTimedOut
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.White.copy(alpha = 0.92f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    ConnectingView(language = language, overlay = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectingView(language: Language, overlay: Boolean = false) {
    Column(
        modifier = if (overlay) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                .padding(top = 120.dp)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (overlay) Arrangement.Center else Arrangement.spacedBy(24.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = AppPrimary,
            strokeWidth = 3.dp
        )
        if (overlay) {
            Spacer(modifier = Modifier.height(24.dp))
        }
        Text(
            text = t("正在连接 AI...", "Connecting to AI...", language),
            style = TextStyle(fontSize = 15.sp, color = AppTextSecondary)
        )
    }
}

@Composable
private fun ConnectionFailedView(language: Language, onRetry: () -> Unit) {
    val retryInteraction = remember { MutableInteractionSource() }
    val retryPressed by retryInteraction.collectIsPressedAsState()
    val retryScale by animateFloatAsState(
        targetValue = if (retryPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "onboardingRetryScale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 100.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Icon(
            imageVector = Icons.Default.WifiOff,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = AppTextSecondary.copy(alpha = 0.5f)
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = t("连接失败", "Connection Failed", language),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppTextPrimary)
            )
            Text(
                text = t("无法连接到 AI，请检查网络后重试", "Could not connect to AI. Please check your network and try again.", language),
                style = TextStyle(fontSize = 12.sp, color = AppTextSecondary, textAlign = TextAlign.Center)
            )
        }
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = retryScale
                    scaleY = retryScale
                }
                .shadow(
                    10.dp,
                    RoundedCornerShape(16.dp),
                    spotColor = AppPrimary.copy(alpha = 0.25f),
                    ambientColor = Color.Transparent
                )
                .clip(RoundedCornerShape(16.dp))
                .background(AppPrimary)
                .clickable(
                    interactionSource = retryInteraction,
                    indication = null,
                    onClick = onRetry
                )
                .padding(horizontal = 32.dp, vertical = 16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(
                    text = t("重试", "Retry", language),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                )
            }
        }
    }
}

/** 对标 iOS `OnboardingBottomControls` `finishedContent` */
@Composable
private fun OnboardingFinishedFooter(language: Language, onStartNow: () -> Unit) {
    val cardShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(16.dp, cardShape, spotColor = Color(0xFF64748B).copy(alpha = 0.055f), ambientColor = Color.Transparent)
            .shadow(8.dp, cardShape, spotColor = Color(0xFF64748B).copy(alpha = 0.04f), ambientColor = Color.Transparent)
            .clip(cardShape)
            .background(Color.White.copy(alpha = 0.78f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            var appeared by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { appeared = true }

            val iconScale by animateFloatAsState(
                targetValue = if (appeared) 1f else 0.5f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
                label = "finishIconScale"
            )
            val iconAlpha by animateFloatAsState(
                targetValue = if (appeared) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
                label = "finishIconAlpha"
            )
            val contentAlpha by animateFloatAsState(
                targetValue = if (appeared) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
                label = "finishContentAlpha"
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .graphicsLayer { scaleX = iconScale; scaleY = iconScale; alpha = iconAlpha }
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AppSuccess.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = AppSuccess, modifier = Modifier.size(24.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = t("配置已完成", "Setup Complete", language),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTextPrimary),
                    modifier = Modifier.graphicsLayer { alpha = contentAlpha }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = t("AI 已准备好为您接听电话", "AI is ready to take calls", language),
                    style = TextStyle(fontSize = 13.sp, color = AppTextSecondary, textAlign = TextAlign.Center),
                    modifier = Modifier.graphicsLayer { alpha = contentAlpha }
                )

                Spacer(modifier = Modifier.height(16.dp))

                val btnInteraction = remember { MutableInteractionSource() }
                val pressed by btnInteraction.collectIsPressedAsState()
                val btnScale by animateFloatAsState(
                    targetValue = if (pressed) 0.95f else 1f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                    label = "finishBtnScale"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = btnScale
                            scaleY = btnScale
                            alpha = contentAlpha
                        }
                        .shadow(10.dp, RoundedCornerShape(16.dp), spotColor = Color(0x40007AFF))
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF007AFF))
                        .clickable(interactionSource = btnInteraction, indication = null) { onStartNow() }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = t("立即体验", "Start Now", language),
                            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun OnboardingViewPreview() {
    CallMateTheme {
        androidx.compose.material3.Text("OnboardingView 预览需运行应用", modifier = Modifier.padding(16.dp))
    }
}
