package com.vaca.callmate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.util.Log
import com.vaca.callmate.data.ChatMessage
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.MessageSender
import com.vaca.callmate.features.outbound.ManualWsScene
import com.vaca.callmate.features.outbound.OUTBOUND_CONFIRM_SENTINEL_PREFIX
import com.vaca.callmate.features.outbound.OutboundChatConnectionState
import com.vaca.callmate.features.outbound.OutboundChatController
import com.vaca.callmate.features.outbound.OutboundConfirmCardState
import com.vaca.callmate.features.outbound.RuleChangeRequest
import com.vaca.callmate.ui.screens.onboarding.AuthorizationRequestCardRow
import com.vaca.callmate.ui.screens.onboarding.CloneAuthorizationCardRow
import com.vaca.callmate.ui.screens.onboarding.GuideImageCardRow
import com.vaca.callmate.ui.screens.onboarding.OnboardingRuleChangeCardRow
import com.vaca.callmate.ui.screens.onboarding.StrategyCardRow
import com.vaca.callmate.ui.screens.onboarding.parseStrategyCardJson
import com.vaca.callmate.ui.screens.outbound.InlineOutboundConfirmationCard
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppBorder
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppTextTertiary
import com.vaca.callmate.ui.screens.chat.ChatComposerBarGlass
import com.vaca.callmate.ui.screens.chat.VoiceRecordingOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private const val FEEDBACK_CHAT_LOG = "FeedbackChatModal"

/**
 * 把 [FeedbackChatModalContent] 的 Chrome / flag 类参数收进 holder，避免原来 27+ 参数触发
 * D8/Compose compiler 在 `changed` bitmask 寄存器合流时生成字节码被 ART verifier 拒绝（`[0xBA5] type
 * Conflict unexpected as arg to if-eqz/if-nez`）。
 */
@androidx.compose.runtime.Immutable
private data class FeedbackChatUi(
    val language: Language,
    val feedbackType: String,
    val showCloseButton: Boolean,
    val showInnerHeaderRow: Boolean,
    val showMessageAvatars: Boolean,
    val useGlassComposer: Boolean,
    val voiceOverlayInParent: Boolean,
)

/** 输入栏 + 录音回调集合。仅普通 lambda，可以放进 data class，不会把 `@Composable` 类型带进来。 */
private data class FeedbackChatInput(
    val inputValue: String,
    val onInputChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onSendText: (String) -> Unit,
    val onRecordingStateChange: ((Boolean) -> Unit)?,
    val onGlassVoiceCancelStateChange: ((Boolean) -> Unit)?,
)

/** outbound WS 分支的所有外部状态，非 WS 模式传一个空集合占位即可。 */
private data class FeedbackChatOutboundData(
    val outboundChat: OutboundChatController?,
    val connectionBanner: String?,
    val placeholderOutbound: Boolean,
    val ttsStreamingText: String,
    val ttsStreamingLoading: Boolean,
    val ruleChangesByCallId: Map<String, RuleChangeRequest>,
    val guideImageCaptions: Map<String, String?>,
    val pendingRuleConfirm: RuleChangeRequest?,
    val outboundConfirmCards: Map<String, OutboundConfirmCardState>,
    val onOnboardingAuthReject: (() -> Unit)?,
)

/**
 * 与 iOS `FeedbackChatModalView.AvatarQuickAction` 对齐：AI 分身 / 通话评价聊天
 * 页面里，按住说话按钮上方的一排小入口。点击把 prompt 以用户消息形式发给 AI。
 */
private data class AvatarQuickAction(
    val id: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val prompt: String,
)

private fun avatarQuickActionsFor(language: Language): List<AvatarQuickAction> = listOf(
    AvatarQuickAction(
        id = "outbound_call",
        title = t("AI打电话", "AI Calling", language),
        icon = Icons.Default.Call,
        prompt = t("我想让你帮我打一通电话", "I want you to help me place a call", language),
    ),
    AvatarQuickAction(
        id = "restaurant_booking",
        title = t("餐厅订位", "Restaurant Booking", language),
        icon = Icons.Default.Restaurant,
        prompt = t("帮我预订一家餐厅", "Help me book a restaurant", language),
    ),
    AvatarQuickAction(
        id = "call_rules",
        title = t("接听规则调整", "Update Call Rules", language),
        icon = Icons.Default.Tune,
        prompt = t("帮我调整接听规则", "Help me adjust the call answering rules", language),
    ),
)

/**
 * 对标 iOS `FeedbackChatModalView`：`outbound_ai` + [OutboundChatController] 时走真实 WebSocket。
 */
@Composable
fun FeedbackChatModal(
    onClose: () -> Unit,
    feedbackType: String,
    language: Language,
    modifier: Modifier = Modifier,
    isEmbedded: Boolean = false,
    showCloseButton: Boolean = true,
    showInnerHeaderRow: Boolean = true,
    /** 对标 iOS AI 分身页：气泡旁不显示圆形头像；反馈弹窗等仍为 true */
    showMessageAvatars: Boolean = true,
    onRecordingStateChange: ((Boolean) -> Unit)? = null,
    /** 父级全屏 [VoiceRecordingOverlay] 时同步「上滑取消」状态 */
    onGlassVoiceCancelStateChange: ((Boolean) -> Unit)? = null,
    /** true：不在本组件内绘制录音遮罩，由父级铺满全屏（对标 iOS CallDetail 全屏 overlay） */
    voiceOverlayInParent: Boolean = false,
    outboundChat: OutboundChatController? = null,
    /**
     * 为 true 时不在这里 [OutboundChatController.start]（避免 Tab 刚显示时 BLE / device-id 未就绪就报错）；
     * 由外层在就绪后启动，仍会在 dispose 时 [OutboundChatController.stop]。
     */
    deferOutboundAutoStart: Boolean = false,
    /** 对标 iOS `OnboardingBottomControls` 完成态：非 null 时替换底部 [ChatComposerBarGlass]（如「立即体验」） */
    glassFooterReplacement: (@Composable () -> Unit)? = null,
    /** 对标 iOS `AuthorizationRequestCard` 拒绝 → `enterFinishedStateIfNeeded` */
    onOnboardingAuthReject: (() -> Unit)? = null,
) {
    val useOutboundWs = outboundChat != null

    val initialMsg = when (feedbackType) {
        "good" -> t("谢谢鼓励！我会继续努力的 💪。还有什么特别满意的地方吗？", "Thanks! I will keep it up 💪. Anything specific you liked?", language)
        "bad" -> t("非常抱歉这次表现不好 😔。请告诉我具体哪里做错了？", "Sorry about that 😔. What went wrong?", language)
        "average" -> t("收到你的评价。你可以按住说话补充更多细节。", "Got it. Hold to talk and share more details.", language)
        "outbound_ai" -> t(
            "你好，我是 AI 外呼助手。我可以帮你创建话术模板，或给某个号码发起 AI 外呼。你想做什么？",
            "Hi, I'm your AI outbound assistant. I can help create call templates or initiate an AI call. What would you like to do?",
            language
        )
        "none" -> t(
            "你好，我是你的专属AI分身。你可以直接让我帮你调整接听策略，也可以让我帮你打电话、订位或做预约。",
            "Hi, I'm your AI personal secretary. I can help adjust call rules, place AI calls, book restaurants, or handle reservations.",
            language
        )
        else -> t("您好，我是您的专属AI助理。您可以直接告诉我需要查询的数据，或者想调整的接听策略。", "Hi, I am your AI Assistant. Ask me anything or adjust settings.", language)
    }
    val stubMessages = remember { mutableStateListOf(ChatMessage(1, MessageSender.Ai, initialMsg, isAudio = true, duration = 3)) }
    var inputValue by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    if (useOutboundWs) {
        val oc = outboundChat!!
        DisposableEffect(oc, deferOutboundAutoStart, feedbackType, isEmbedded) {
            Log.i(
                FEEDBACK_CHAT_LOG,
                "[outbound-ui] enter: deferAutoStart=$deferOutboundAutoStart " +
                    "feedbackType=$feedbackType embedded=$isEmbedded " +
                    "conn=${oc.connectionState.value}"
            )
            if (!deferOutboundAutoStart) {
                Log.i(FEEDBACK_CHAT_LOG, "[outbound-ui] DisposableEffect -> oc.start()")
                oc.start()
            } else {
                Log.i(FEEDBACK_CHAT_LOG, "[outbound-ui] deferAutoStart: skip oc.start() here")
            }
            onDispose {
                Log.i(
                    FEEDBACK_CHAT_LOG,
                    "[outbound-ui] dispose -> oc.stop() deferWas=$deferOutboundAutoStart type=$feedbackType"
                )
                oc.stop()
            }
        }
        val messages by oc.messages.collectAsState()
        val conn by oc.connectionState.collectAsState()
        val outboundConfirmCards by oc.outboundConfirmCards.collectAsState()
        val ttsStreamingText by oc.ttsStreamingText.collectAsState()
        val ttsStreamingLoading by oc.ttsStreamingLoading.collectAsState()
        val ruleChangesByCallId by oc.ruleChangesByCallId.collectAsState()
        val guideImageCaptions by oc.guideImageCaptionByImageId.collectAsState()
        val pendingRuleConfirm by oc.pendingRuleChangeForConfirm.collectAsState()

        val connHint = when (conn) {
            OutboundChatConnectionState.Disconnected -> null
            OutboundChatConnectionState.Connecting ->
                t("正在连接服务器…", "Connecting…", language)
            OutboundChatConnectionState.Connected -> null
            OutboundChatConnectionState.Error ->
                t("连接异常，可尝试返回后重进。", "Connection error. Try again.", language)
        }

        val onSend: () -> Unit = {
            if (inputValue.isNotBlank()) {
                oc.sendUserText(inputValue)
                inputValue = ""
            }
        }
        val onSendText: (String) -> Unit = { text ->
            val s = text.trim()
            if (s.isNotEmpty()) oc.sendUserText(s)
        }
        val useGlassComposer = isEmbedded && (
            feedbackType == "none" || feedbackType == "good" || feedbackType == "bad" || feedbackType == "average" ||
                feedbackType == "onboarding"
            )

        val surfaceModifier = modifier
            .fillMaxWidth()
            .then(if (isEmbedded) Modifier.fillMaxSize() else Modifier.height(600.dp))
            .then(if (isEmbedded) Modifier else Modifier.clip(RoundedCornerShape(24.dp)))
            .background(if (isEmbedded) Color.Transparent else AppSurface)

        val content: @Composable () -> Unit = {
            FeedbackChatModalContent(
                surfaceModifier = surfaceModifier,
                onClose = onClose,
                messages = messages,
                ui = FeedbackChatUi(
                    language = language,
                    feedbackType = feedbackType,
                    showCloseButton = showCloseButton,
                    showInnerHeaderRow = showInnerHeaderRow,
                    showMessageAvatars = showMessageAvatars,
                    useGlassComposer = useGlassComposer,
                    voiceOverlayInParent = voiceOverlayInParent,
                ),
                input = FeedbackChatInput(
                    inputValue = inputValue,
                    onInputChange = { inputValue = it },
                    onSend = onSend,
                    onSendText = onSendText,
                    onRecordingStateChange = onRecordingStateChange,
                    onGlassVoiceCancelStateChange = onGlassVoiceCancelStateChange,
                ),
                outbound = FeedbackChatOutboundData(
                    outboundChat = oc,
                    connectionBanner = connHint,
                    placeholderOutbound = true,
                    ttsStreamingText = ttsStreamingText,
                    ttsStreamingLoading = ttsStreamingLoading,
                    ruleChangesByCallId = ruleChangesByCallId,
                    guideImageCaptions = guideImageCaptions,
                    pendingRuleConfirm = pendingRuleConfirm,
                    outboundConfirmCards = outboundConfirmCards,
                    onOnboardingAuthReject = onOnboardingAuthReject,
                ),
                glassFooterReplacement = glassFooterReplacement,
            )
        }

        if (isEmbedded) {
            content()
        } else {
            Dialog(
                onDismissRequest = onClose,
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                content()
            }
        }
    } else {
        val surfaceModifier = modifier
            .fillMaxWidth()
            .then(if (isEmbedded) Modifier.fillMaxSize() else Modifier.height(600.dp))
            .then(if (isEmbedded) Modifier else Modifier.clip(RoundedCornerShape(24.dp)))
            .background(if (isEmbedded) Color.Transparent else AppSurface)

        val onSend: () -> Unit = {
            if (inputValue.isNotBlank()) {
                stubMessages.add(ChatMessage(System.currentTimeMillis(), MessageSender.User, inputValue))
                inputValue = ""
                scope.launch {
                    delay(1500)
                    stubMessages.add(
                        ChatMessage(
                            System.currentTimeMillis() + 1,
                            MessageSender.Ai,
                            t("好的，已经根据您的要求调整了相关设置。还有其他需要我帮忙的吗？", "Okay, settings updated. Anything else?", language),
                            isAudio = true,
                            duration = 4
                        )
                    )
                }
            }
        }
        val onSendText: (String) -> Unit = { text ->
            if (text.isNotBlank()) {
                stubMessages.add(ChatMessage(System.currentTimeMillis(), MessageSender.User, text))
                scope.launch {
                    delay(1500)
                    stubMessages.add(
                        ChatMessage(
                            System.currentTimeMillis() + 1,
                            MessageSender.Ai,
                            t("好的，已经根据您的要求调整了相关设置。还有其他需要我帮忙的吗？", "Okay, settings updated. Anything else?", language),
                            isAudio = true,
                            duration = 4
                        )
                    )
                }
            }
        }
        val useGlassComposer = isEmbedded && (
            feedbackType == "none" || feedbackType == "good" || feedbackType == "bad" || feedbackType == "average"
            )

        val stubUi = FeedbackChatUi(
            language = language,
            feedbackType = feedbackType,
            showCloseButton = showCloseButton,
            showInnerHeaderRow = showInnerHeaderRow,
            showMessageAvatars = showMessageAvatars,
            useGlassComposer = useGlassComposer,
            voiceOverlayInParent = voiceOverlayInParent,
        )
        val stubInput = FeedbackChatInput(
            inputValue = inputValue,
            onInputChange = { inputValue = it },
            onSend = onSend,
            onSendText = onSendText,
            onRecordingStateChange = onRecordingStateChange,
            onGlassVoiceCancelStateChange = onGlassVoiceCancelStateChange,
        )
        val stubOutbound = FeedbackChatOutboundData(
            outboundChat = null,
            connectionBanner = null,
            placeholderOutbound = false,
            ttsStreamingText = "",
            ttsStreamingLoading = false,
            ruleChangesByCallId = emptyMap(),
            guideImageCaptions = emptyMap(),
            pendingRuleConfirm = null,
            outboundConfirmCards = emptyMap(),
            onOnboardingAuthReject = null,
        )

        if (isEmbedded) {
            FeedbackChatModalContent(
                surfaceModifier = surfaceModifier,
                onClose = onClose,
                messages = stubMessages,
                ui = stubUi,
                input = stubInput,
                outbound = stubOutbound,
                glassFooterReplacement = null,
            )
        } else {
            Dialog(
                onDismissRequest = onClose,
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                FeedbackChatModalContent(
                    surfaceModifier = surfaceModifier,
                    onClose = onClose,
                    messages = stubMessages,
                    ui = stubUi,
                    input = stubInput,
                    outbound = stubOutbound,
                    glassFooterReplacement = null,
                )
            }
        }
    }
}

@Composable
private fun FeedbackChatModalContent(
    surfaceModifier: Modifier,
    onClose: () -> Unit,
    messages: List<ChatMessage>,
    ui: FeedbackChatUi,
    input: FeedbackChatInput,
    outbound: FeedbackChatOutboundData,
    glassFooterReplacement: (@Composable () -> Unit)?,
) {
    val language = ui.language
    val showCloseButton = ui.showCloseButton
    val showInnerHeaderRow = ui.showInnerHeaderRow
    val showMessageAvatars = ui.showMessageAvatars
    val useGlassComposer = ui.useGlassComposer
    val voiceOverlayInParent = ui.voiceOverlayInParent

    val inputValue = input.inputValue
    val onInputChange = input.onInputChange
    val onSend = input.onSend
    val onSendText = input.onSendText
    val onRecordingStateChange = input.onRecordingStateChange
    val onGlassVoiceCancelStateChange = input.onGlassVoiceCancelStateChange

    val outboundChat = outbound.outboundChat
    val connectionBanner = outbound.connectionBanner
    val placeholderOutbound = outbound.placeholderOutbound
    val ttsStreamingText = outbound.ttsStreamingText
    val ttsStreamingLoading = outbound.ttsStreamingLoading
    val ruleChangesByCallId = outbound.ruleChangesByCallId
    val guideImageCaptions = outbound.guideImageCaptions
    val pendingRuleConfirm = outbound.pendingRuleConfirm
    val outboundConfirmCards = outbound.outboundConfirmCards
    val onOnboardingAuthReject = outbound.onOnboardingAuthReject

    var isMicRecording by remember { mutableStateOf(false) }
    var glassVoiceRecording by remember { mutableStateOf(false) }
    var glassVoiceCancelling by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val useAvatarChatStyle = useGlassComposer && showMessageAvatars
    val streamVisible =
        placeholderOutbound && ((ttsStreamingLoading && ttsStreamingText.isEmpty()) || ttsStreamingText.isNotEmpty())
    LaunchedEffect(messages.size, ttsStreamingText, ttsStreamingLoading) {
        if (!placeholderOutbound) return@LaunchedEffect
        if (!streamVisible && messages.isEmpty()) return@LaunchedEffect
        val last = messages.size + if (streamVisible) 1 else 0
        if (last > 0) {
            listState.scrollToItem((last - 1).coerceAtLeast(0))
        }
    }
    Box(modifier = surfaceModifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showInnerHeaderRow) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(52.dp)
                        .padding(horizontal = 20.dp)
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(
                            text = t("AI分身", "AI Avatar", language),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTextPrimary
                        )
                        Text(
                            text = t("内容由 AI 生成", "Content generated by AI", language),
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            color = AppTextSecondary
                        )
                    }
                    if (showCloseButton) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(40.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = AppTextSecondary)
                        }
                    }
                }
            }
            connectionBanner?.let { hint ->
                Text(
                    text = hint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    color = AppPrimary,
                    textAlign = TextAlign.Center
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(
                        if (useGlassComposer && placeholderOutbound) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(onTap = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                })
                            }
                        } else {
                            Modifier
                        }
                    )
                    .background(if (useAvatarChatStyle) Color.Transparent else AppBackgroundSecondary.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(
                    start = if (useAvatarChatStyle) 20.dp else 16.dp,
                    top = if (useAvatarChatStyle) 16.dp else 8.dp,
                    end = if (useAvatarChatStyle) 20.dp else 16.dp,
                    bottom = if (useAvatarChatStyle) 24.dp else 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    when (msg.sender) {
                        MessageSender.System -> {
                            val oc = outboundChat
                            val text = msg.text
                            when {
                                oc != null && text == "__auth_request__" ->
                                    AuthorizationRequestCardRow(
                                        language = language,
                                        onAccept = { oc.sendAuthorizationAcceptedText() },
                                        onReject = { onOnboardingAuthReject?.invoke() },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                oc != null && text.startsWith("__guide_image__:") -> {
                                    val imageId = text.removePrefix("__guide_image__:")
                                    GuideImageCardRow(
                                        imageId = imageId,
                                        caption = guideImageCaptions[imageId],
                                        language = language,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                oc != null && text.startsWith("__guide_card__:") -> {
                                    val rest = text.removePrefix("__guide_card__:")
                                    val idx = rest.indexOf(':')
                                    if (idx > 0) {
                                        val cardId = rest.substring(0, idx)
                                        val callId = rest.substring(idx + 1)
                                        if (cardId == "clone_authorization") {
                                            CloneAuthorizationCardRow(
                                                language = language,
                                                onAccept = { oc.respondToGuideCard(callId, true) },
                                                onReject = { oc.respondToGuideCard(callId, false) },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        } else {
                                            Spacer(Modifier.height(0.dp))
                                        }
                                    } else {
                                        Spacer(Modifier.height(0.dp))
                                    }
                                }
                                oc != null && text.startsWith("__rule_change__:") -> {
                                    val callId = text.removePrefix("__rule_change__:")
                                    val change = ruleChangesByCallId[callId]
                                    if (change != null) {
                                        val isInit = oc.manualWsScene == ManualWsScene.INIT_CONFIG
                                        val showButtons =
                                            !isInit && pendingRuleConfirm?.id == callId
                                        OnboardingRuleChangeCardRow(
                                            change = change,
                                            language = language,
                                            isInitConfigAutoApplied = isInit,
                                            onConfirm = if (showButtons) {
                                                { oc.sendRuleChangeResponse(callId, "confirm") }
                                            } else null,
                                            onCancel = if (showButtons) {
                                                { oc.sendRuleChangeResponse(callId, "cancel") }
                                            } else null,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        Text(
                                            text = text,
                                            modifier = Modifier.fillMaxWidth(),
                                            fontSize = 12.sp,
                                            color = AppTextTertiary,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                                oc != null && text.startsWith(OUTBOUND_CONFIRM_SENTINEL_PREFIX) -> {
                                    val callId = text.removePrefix(OUTBOUND_CONFIRM_SENTINEL_PREFIX)
                                    val card = outboundConfirmCards[callId]
                                    if (card != null) {
                                        InlineOutboundConfirmationCard(
                                            data = card.data,
                                            status = card.status,
                                            failureMessage = card.failureMessage,
                                            language = language,
                                            onConfirm = { oc.resolveOutboundConfirmCard(callId, true) },
                                            onCancel = { oc.resolveOutboundConfirmCard(callId, false) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        Spacer(Modifier.height(0.dp))
                                    }
                                }
                                oc != null -> {
                                    val strategy = parseStrategyCardJson(text)
                                    if (strategy != null) {
                                        StrategyCardRow(
                                            trigger = strategy.first,
                                            action = strategy.second,
                                            language = language,
                                            isApplied = false,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        Text(
                                            text = text,
                                            modifier = Modifier.fillMaxWidth(),
                                            fontSize = 12.sp,
                                            color = AppTextTertiary,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                                else -> {
                                    Text(
                                        text = text,
                                        modifier = Modifier.fillMaxWidth(),
                                        fontSize = 12.sp,
                                        color = AppTextTertiary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        else -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (msg.sender == MessageSender.User) Arrangement.End else Arrangement.Start
                            ) {
                                val isUser = msg.sender == MessageSender.User
                                val bubbleShape = RoundedCornerShape(
                                    topStart = 18.dp, topEnd = 18.dp,
                                    bottomStart = if (isUser) 18.dp else 4.dp,
                                    bottomEnd = if (isUser) 4.dp else 18.dp
                                )
                                val isGlassAiBubble = !isUser && useAvatarChatStyle
                                Box(
                                    modifier = Modifier
                                        .then(
                                            if (isGlassAiBubble) {
                                                Modifier.shadow(
                                                    elevation = 6.dp,
                                                    shape = bubbleShape,
                                                    spotColor = Color.Black.copy(alpha = 0.05f),
                                                    ambientColor = Color.Transparent
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .then(
                                            if (!isUser) {
                                                Modifier.border(
                                                    0.5.dp,
                                                    if (isGlassAiBubble) Color.White.copy(alpha = 0.7f) else AppBorder,
                                                    bubbleShape
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .clip(bubbleShape)
                                        .then(
                                            when {
                                                isUser -> Modifier.background(AppPrimary)
                                                isGlassAiBubble -> Modifier.background(
                                                    Brush.verticalGradient(
                                                        colors = listOf(
                                                            Color.White.copy(alpha = 0.82f),
                                                            Color(0xFFF5F8FF).copy(alpha = 0.68f)
                                                        )
                                                    )
                                                )
                                                else -> Modifier.background(
                                                    Brush.verticalGradient(
                                                        colors = listOf(AppSurface, AppSurface)
                                                    )
                                                )
                                            }
                                        )
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = msg.text,
                                        fontSize = 17.sp,
                                        lineHeight = 23.sp,
                                        color = if (isUser) Color.White else AppTextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
                if (streamVisible) {
                    item(key = "tts_streaming_bubble") {
                        TtsStreamingBubbleRow(
                            showMessageAvatars = showMessageAvatars,
                            loading = ttsStreamingLoading && ttsStreamingText.isEmpty(),
                            text = ttsStreamingText,
                        )
                    }
                }
            }
            if (useGlassComposer) {
                if (glassFooterReplacement != null) {
                    glassFooterReplacement()
                } else {
                    // 与 iOS `avatarQuickActionsRow` 对齐：AI 分身 / 评价聊天里，按住说话按钮上方
                    // 一排小按钮（AI 打电话 / 餐厅订位 / 接听规则调整），点击把 prompt 当作用户
                    // 消息发出去。录音中隐藏，避免挡视线。
                    if (!glassVoiceRecording) {
                        AvatarQuickActionsRow(
                            language = language,
                            onPromptSelected = onSendText,
                        )
                    }
                    ChatComposerBarGlass(
                        language = language,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .alpha(if (glassVoiceRecording) 0f else 1f),
                        onVoiceStart = { outboundChat?.beginManualListen() },
                        onVoiceSend = { outboundChat?.endManualListen() },
                        onVoiceCancel = { outboundChat?.cancelManualListen() },
                        onSendText = onSendText,
                        onVoiceCancelStateChanged = {
                            glassVoiceCancelling = it
                            onGlassVoiceCancelStateChange?.invoke(it)
                        },
                        onRecordingStateChange = { recording ->
                            glassVoiceRecording = recording
                            onRecordingStateChange?.invoke(recording)
                        },
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(
                        onClick = {
                            isMicRecording = !isMicRecording
                            onRecordingStateChange?.invoke(isMicRecording)
                        }
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (isMicRecording) AppPrimary else AppTextSecondary
                        )
                    }
                    OutlinedTextField(
                        value = inputValue,
                        onValueChange = onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (placeholderOutbound) {
                                    t("输入消息…", "Type a message…", language)
                                } else {
                                    t("输入您的建议...", "Type your suggestion...", language)
                                },
                                fontSize = 14.sp,
                                color = AppTextTertiary
                            )
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppTextPrimary,
                            unfocusedTextColor = AppTextPrimary,
                            focusedBorderColor = AppBorder,
                            unfocusedBorderColor = AppBorder,
                            cursorColor = AppPrimary,
                            focusedPlaceholderColor = AppTextTertiary,
                            unfocusedPlaceholderColor = AppTextTertiary
                        )
                    )
                    IconButton(
                        onClick = onSend
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = null,
                            tint = if (inputValue.isNotBlank()) AppPrimary else AppTextSecondary
                        )
                    }
                }
            }
        }
        if (useGlassComposer && glassVoiceRecording && !voiceOverlayInParent) {
            VoiceRecordingOverlay(
                language = language,
                isCancelling = glassVoiceCancelling,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 对标 iOS `StreamingTextBubble` + `TypingDotsView`：loading 三点 / 流式文字 */
@Composable
private fun TtsStreamingBubbleRow(
    showMessageAvatars: Boolean,
    loading: Boolean,
    text: String,
) {
    val aiBubbleShape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = 4.dp, bottomEnd = 18.dp
    )
    val useGlassBubble = showMessageAvatars
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (useGlassBubble) {
                        Modifier.shadow(
                            elevation = 6.dp,
                            shape = aiBubbleShape,
                            spotColor = Color.Black.copy(alpha = 0.05f),
                            ambientColor = Color.Transparent
                        )
                    } else {
                        Modifier
                    }
                )
                .border(
                    0.5.dp,
                    if (useGlassBubble) Color.White.copy(alpha = 0.7f) else AppBorder,
                    aiBubbleShape
                )
                .clip(aiBubbleShape)
                .background(
                    if (useGlassBubble) {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.82f),
                                Color(0xFFF5F8FF).copy(alpha = 0.68f)
                            )
                        )
                    } else {
                        Brush.verticalGradient(colors = listOf(AppSurface, AppSurface))
                    }
                )
        ) {
            if (loading) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TtsStreamingTypingDots()
                }
            } else {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                    color = AppTextPrimary
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun FeedbackChatModalPreview() {
    com.vaca.callmate.ui.preview.PreviewUnavailablePlaceholder("FeedbackChatModal")
}

@Composable
private fun TtsStreamingTypingDots() {
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(380)
            phase = (phase + 1) % 3
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0..2) {
            val active = phase == i
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(if (active) 1.2f else 1f)
                    .clip(CircleShape)
                    .background(if (active) AppTextPrimary else AppTextTertiary)
            )
        }
    }
}

/**
 * 与 iOS `avatarQuickActionsRow` / `avatarQuickActionChip` 对齐：横向滚动的胶囊按钮条。
 */
@Composable
private fun AvatarQuickActionsRow(
    language: Language,
    onPromptSelected: (String) -> Unit,
) {
    val actions = remember(language) { avatarQuickActionsFor(language) }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(actions, key = { it.id }) { action ->
            AvatarQuickActionChip(action = action, onClick = { onPromptSelected(action.prompt) })
        }
    }
}

@Composable
private fun AvatarQuickActionChip(
    action: AvatarQuickAction,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.82f),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.82f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = AppTextPrimary,
            )
            Text(
                text = action.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = AppTextPrimary,
                maxLines = 1,
            )
        }
    }
}
