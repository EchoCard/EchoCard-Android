package com.vaca.callmate.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.data.IncomingCall
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.repository.CallRepository
import com.vaca.callmate.data.MessageSender
import com.vaca.callmate.features.calls.CallSessionStatus
import com.vaca.callmate.features.calls.CallSessionViewModel
import com.vaca.callmate.features.calls.TranscriptMessage
import com.vaca.callmate.ui.theme.AppAccent
import com.vaca.callmate.ui.theme.AppBackground
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppRadius
import com.vaca.callmate.ui.theme.AppSpacing
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppTextTertiary
import com.vaca.callmate.ui.theme.AppTypography
import com.vaca.callmate.ui.theme.AppWarning

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private const val OUTBOUND_TASK_TITLE = "[OUTBOUND_TASK]"

/** 对标 iOS `RoundedRectangle.fill(.ultraThinMaterial)`：多层半透明白（无模糊时仍像磨砂玻璃） */
private val ultraThinMaterialGradientBrush = Brush.verticalGradient(
    colorStops = arrayOf(
        0f to Color(0xEFFFFFFF),
        0.22f to Color(0xD0FFFFFF),
        0.55f to Color(0xA8FFFFFF),
        1f to Color(0x94FFFFFF)
    )
)

/** 轨道内顶部高光（模拟玻璃上沿反光） */
private val glassSpecularHighlightBrush = Brush.verticalGradient(
    colorStops = arrayOf(
        0f to Color(0x4DFFFFFF),
        0.42f to Color(0x00FFFFFF),
        1f to Color(0x00FFFFFF)
    )
)

@Composable
fun LiveCallView(
    language: Language,
    incomingCall: IncomingCall,
    viewModel: CallSessionViewModel,
    bleManager: BleManager,
    callRepository: CallRepository? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appContext = LocalContext.current.applicationContext
    /** [CallSessionViewModel.liveFinishDeps] 由 [CallMateApp] 在 Main 态统一注入，此处仅处理页面消失 */
    val status by viewModel.status.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val durationSeconds by viewModel.durationSeconds.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val ttsStreamingText by viewModel.ttsStreamingText.collectAsState()
    val ttsStreamingLoading by viewModel.ttsStreamingLoading.collectAsState()

    /** 与 iOS `onChange(of: controller.status)` 一致：无本地库时仅变为 `ended` 时关闭；有仓库时由详情导航接走。 */
    var prevStatus by remember(incomingCall.callId) { mutableStateOf<CallSessionStatus?>(null) }
    LaunchedEffect(status, incomingCall.callId, callRepository) {
        if (status == CallSessionStatus.Ended && prevStatus != null && prevStatus != CallSessionStatus.Ended) {
            if (callRepository == null) {
                onClose()
            }
        }
        prevStatus = status
    }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.onLiveViewDisappear()
        }
    }

    LaunchedEffect(status, incomingCall.callId) {
        if (status == CallSessionStatus.Connected) {
            viewModel.startRecording(appContext, bleManager)
        }
    }

    /** 与 iOS `handleBLECallState*` 终端路径对齐；`connected` 由 WS hello → [CallSessionViewModel.onLiveWsHelloAcked] 驱动。 */
    LaunchedEffect(incomingCall.callId, incomingCall.bleSid, bleManager) {
        bleManager.callStateEvents.collect { st ->
            val s = st.lowercase()
            if (s == "ended" || s == "rejected" || s == "phone_handled") {
                viewModel.onBleCallTerminal()
            }
        }
    }
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearToast()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(AppBackgroundSecondary)) {
        Column(modifier = Modifier.fillMaxSize()) {
            header(
                language = language,
                incomingCall = incomingCall,
                status = status,
                durationSeconds = durationSeconds
            )
            transcriptList(
                messages = messages,
                language = language,
                ttsStreamingText = ttsStreamingText,
                ttsStreamingLoading = ttsStreamingLoading,
                modifier = Modifier.weight(1f)
            )
        }
        controls(
            language = language,
            viewModel = viewModel,
            status = status,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        toastMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.sm)
                    .align(Alignment.TopCenter)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(AppRadius.lg)
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(AppSpacing.md),
                        style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = AppBackground
                    )
                }
            }
        }
    }
}

@Composable
private fun header(
    language: Language,
    incomingCall: IncomingCall,
    status: CallSessionStatus,
    durationSeconds: Int
) {
    val isOutbound = incomingCall.title == OUTBOUND_TASK_TITLE
    val durationStr = "%02d:%02d".format(durationSeconds / 60, durationSeconds % 60)
    val numberText = incomingCall.number.ifEmpty { t("未知号码", "Unknown", language) }
    val trailingTint = when (status) {
        CallSessionStatus.Connected -> AppSuccess
        else -> AppTextSecondary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(52.dp)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.align(Alignment.CenterStart).width(56.dp))
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = numberText,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary,
                maxLines = 1
            )
            Text(
                text = headerHintText(status, language, isOutbound),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = AppTextSecondary,
                maxLines = 1
            )
        }
        if (status == CallSessionStatus.Connected) {
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Phone,
                    contentDescription = null,
                    tint = trailingTint,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = durationStr,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = trailingTint
                )
            }
        }
    }
}

private fun headerHintText(
    status: CallSessionStatus,
    language: Language,
    isOutbound: Boolean
): String = when (status) {
    CallSessionStatus.Connecting -> if (isOutbound) {
        t("正在拨号，准备转写…", "Dialing, preparing transcription…", language)
    } else {
        t("正在连接设备并准备转写…", "Preparing transcription…", language)
    }
    CallSessionStatus.Ringing -> if (isOutbound) {
        t("等待对方接听…", "Waiting for answer…", language)
    } else {
        t("AI 将自动接听并开始转写", "AI will answer and transcribe", language)
    }
    CallSessionStatus.Connected -> t("实时转写中，可随时转交真人接听", "Live transcribing; hand off anytime", language)
    CallSessionStatus.Ended -> t("通话已结束", "Call ended", language)
    CallSessionStatus.Idle -> t("正在连接设备并准备转写…", "Preparing transcription…", language)
}

@Composable
private fun transcriptList(
    messages: List<TranscriptMessage>,
    language: Language,
    ttsStreamingText: String,
    ttsStreamingLoading: Boolean,
    modifier: Modifier = Modifier
) {
    /** 与 iOS `LiveTranscriptListView`：`messages.isEmpty && streamingState.text.isEmpty && !streamingState.isLoading` */
    val streamVisible =
        (ttsStreamingLoading && ttsStreamingText.isEmpty()) || ttsStreamingText.isNotEmpty()
    val showEmptyPlaceholder = messages.isEmpty() && ttsStreamingText.isEmpty() && !ttsStreamingLoading

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, ttsStreamingText, ttsStreamingLoading) {
        if (showEmptyPlaceholder) return@LaunchedEffect
        val lastIndex = messages.size + if (streamVisible) 1 else 0
        if (lastIndex > 0) {
            listState.scrollToItem(lastIndex - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = AppSpacing.lg, vertical = AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        if (showEmptyPlaceholder) {
            item(key = "empty") {
                EmptyTranscriptPlaceholder(language = language)
            }
        } else {
            items(messages, key = { it.id }) { msg ->
                transcriptBubble(msg = msg)
            }
            if (streamVisible) {
                item(key = "streaming-ai") {
                    liveTtsStreamingBubble(
                        loading = ttsStreamingLoading && ttsStreamingText.isEmpty(),
                        text = ttsStreamingText
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTranscriptPlaceholder(language: Language) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.GraphicEq,
            contentDescription = null,
            tint = AppTextTertiary,
            modifier = Modifier
                .padding(top = AppSpacing.xl)
                .size(28.dp)
        )
        Text(
            text = t("转写准备中", "Getting ready", language),
            style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = AppTextPrimary,
            modifier = Modifier.padding(top = AppSpacing.sm)
        )
        Text(
            text = t("接通后将显示双方对话内容", "Transcript will appear after connection", language),
            style = AppTypography.bodyLarge,
            color = AppTextSecondary,
            modifier = Modifier
                .padding(horizontal = AppSpacing.xl)
                .padding(bottom = AppSpacing.xl)
        )
    }
}

/** 对标 iOS `LiveStreamingBubble` + `StreamingTextBubble` + `TypingDotsView`：右对齐 AI 气泡、首字 loading 三点 */
@Composable
private fun liveTtsStreamingBubble(
    loading: Boolean,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .shadow(
                    6.dp,
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 4.dp
                    ),
                    spotColor = Color.Black.copy(alpha = 0.08f),
                    ambientColor = Color.Transparent
                )
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 4.dp
                    )
                )
                .background(
                    Brush.linearGradient(
                        colors = listOf(AppPrimary, AppAccent),
                        start = Offset.Zero,
                        end = Offset(400f, 400f)
                    )
                )
                .border(
                    0.8.dp,
                    AppBackground.copy(alpha = 0.14f),
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 4.dp
                    )
                )
        ) {
            if (loading) {
                Row(
                    modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    liveTypingDots()
                }
            } else {
                Text(
                    text = text,
                    modifier = Modifier.padding(AppSpacing.md),
                    style = AppTypography.bodyLarge,
                    color = AppBackground
                )
            }
        }
    }
}

@Composable
private fun liveTypingDots() {
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
                    .background(if (active) AppBackground else AppBackground.copy(alpha = 0.45f))
            )
        }
    }
}

@Composable
private fun transcriptBubble(msg: TranscriptMessage) {
    val isCaller = msg.sender == MessageSender.Caller
    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (isCaller) 4.dp else 18.dp,
        bottomEnd = if (isCaller) 18.dp else 4.dp
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isCaller) Arrangement.Start else Arrangement.End
    ) {
        if (isCaller) {
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .shadow(1.5.dp, bubbleShape, spotColor = Color.Black.copy(alpha = 0.04f), ambientColor = Color.Transparent)
                    .shadow(5.dp, bubbleShape, spotColor = Color.Black.copy(alpha = 0.04f), ambientColor = Color.Transparent)
                    .clip(bubbleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.88f),
                                Color(0xFFF5F8FF).copy(alpha = 0.74f)
                            )
                        )
                    )
                    .border(0.5.dp, Color.White.copy(alpha = 0.7f), bubbleShape)
            ) {
                Text(
                    text = msg.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    color = AppTextPrimary
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(bubbleShape)
                    .background(Color(0xFF007AFF))
            ) {
                Text(
                    text = msg.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * 与 iOS `LiveCallView.controls` 对齐：单条「右滑切换真人接听」胶囊轨道（64pt 高、72% 宽、阈值 0.72），
 * 无独立挂断按钮（与 iOS 一致）。
 */
@Composable
private fun controls(
    language: Language,
    viewModel: CallSessionViewModel,
    status: CallSessionStatus,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val trackHeight = 52.dp
    val thumbInset = 5.dp
    val thumbSize = trackHeight - thumbInset * 2
    val thumbInsetPx = with(density) { thumbInset.toPx() }
    val thumbSizePx = with(density) { thumbSize.toPx() }
    val sweepTransition = rememberInfiniteTransition(label = "handoffSweep")
    val handoffHintSweep by sweepTransition.animateFloat(
        initialValue = -0.55f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "handoffSweepValue"
    )
    val handoffThumbGlow by sweepTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "handoffThumbGlowValue"
    )

    var rawOffsetPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val handoffSlideOffsetPx by animateFloatAsState(
        targetValue = rawOffsetPx,
        animationSpec = if (isDragging) snap() else tween(300, easing = FastOutSlowInEasing),
        label = "handoffSlide"
    )

    val vm by rememberUpdatedState(viewModel)
    val currentStatus by rememberUpdatedState(status)

    LaunchedEffect(status) {
        if (status == CallSessionStatus.Ended) {
            isDragging = true
            rawOffsetPx = 0f
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BoxWithConstraints(
                modifier = Modifier.height(trackHeight)
            ) {
                    val capsuleMaxWidth = maxWidth * 3f / 5f
                    val capsuleMaxWidthPx = with(density) { capsuleMaxWidth.toPx() }
                    val maxOffsetPx = max(0f, capsuleMaxWidthPx - thumbSizePx - thumbInsetPx * 2)
                    val movingTrackWidthPx = max(
                        thumbSizePx + thumbInsetPx * 2,
                        capsuleMaxWidthPx - handoffSlideOffsetPx
                    )
                    val progress =
                        if (maxOffsetPx > 0) (handoffSlideOffsetPx / maxOffsetPx).coerceIn(0f, 1f) else 0f
                    val isSlidToEnd = progress >= 0.72f
                    val textAlpha = (0.9f - 0.5f * progress).coerceIn(0f, 1f)

                    Box(
                        modifier = Modifier
                            .width(capsuleMaxWidth)
                            .height(trackHeight)
                            .pointerInput(maxOffsetPx, status) {
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        if (currentStatus != CallSessionStatus.Ended) isDragging = true
                                    },
                                    onHorizontalDrag = { change, dragAmount ->
                                        if (currentStatus == CallSessionStatus.Ended) {
                                            return@detectHorizontalDragGestures
                                        }
                                        rawOffsetPx =
                                            (rawOffsetPx + dragAmount).coerceIn(0f, maxOffsetPx)
                                        change.consume()
                                    },
                                    onDragEnd = {
                                        scope.launch {
                                            if (currentStatus == CallSessionStatus.Ended) {
                                                isDragging = true
                                                rawOffsetPx = 0f
                                                return@launch
                                            }
                                            val shouldTrigger = rawOffsetPx >= maxOffsetPx * 0.72f
                                            val snapTarget = if (shouldTrigger) maxOffsetPx else 0f
                                            isDragging = false
                                            rawOffsetPx = snapTarget
                                            delay(300)
                                            if (shouldTrigger) vm.handoff()
                                            delay(200)
                                            rawOffsetPx = 0f
                                        }
                                    },
                                    onDragCancel = {
                                        scope.launch {
                                            isDragging = false
                                            rawOffsetPx = 0f
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.width(capsuleMaxWidth).height(trackHeight)) {
                            val trackW = with(density) { movingTrackWidthPx.toDp() }
                            val textAreaWidth = maxOf(0.dp, trackW - thumbSize - thumbInset * 2)
                            val capsuleShape = RoundedCornerShape(trackHeight / 2)

                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(trackW)
                                    .height(trackHeight)
                                    .clip(capsuleShape)
                                    .shadow(
                                        elevation = 14.dp,
                                        shape = capsuleShape,
                                        spotColor = Color.Black.copy(alpha = 0.14f),
                                        ambientColor = Color.Transparent
                                    )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(ultraThinMaterialGradientBrush)
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.White.copy(alpha = 0.78f),
                                                    Color.White.copy(alpha = 0.64f)
                                                )
                                            )
                                        )
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .padding(1.2.dp)
                                        .clip(capsuleShape)
                                        .background(AppPrimary.copy(alpha = 0.06f))
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .fillMaxWidth()
                                        .fillMaxHeight(0.42f)
                                        .padding(horizontal = 10.dp)
                                        .padding(top = 2.dp)
                                        .background(glassSpecularHighlightBrush)
                                )
                                if (currentStatus != CallSessionStatus.Ended) {
                                    val sweepWidth = maxOf(44.dp, trackW * 0.34f)
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .offset(
                                                x = (-sweepWidth + (trackW + sweepWidth * 2) * handoffHintSweep),
                                                y = (-trackHeight * 0.06f)
                                            )
                                            .width(sweepWidth)
                                            .height(trackHeight * 1.22f)
                                            .graphicsLayer { rotationZ = 16f }
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(
                                                        Color.Transparent,
                                                        Color.White.copy(alpha = 0.04f + 0.02f * (1f - progress)),
                                                        Color.White.copy(alpha = 0.24f + 0.12f * (1f - progress)),
                                                        Color.White.copy(alpha = 0.5f + 0.1f * (1f - progress)),
                                                        Color.White.copy(alpha = 0.24f + 0.12f * (1f - progress)),
                                                        Color.White.copy(alpha = 0.04f + 0.02f * (1f - progress)),
                                                        Color.Transparent
                                                    )
                                                )
                                            )
                                    )
                                }
                                Text(
                                    text = t("右滑切换真人接听", "Slide for human", language),
                                    modifier = Modifier
                                        .width(textAreaWidth)
                                        .offset(x = thumbSize + thumbInset)
                                        .align(Alignment.CenterStart)
                                        .zIndex(1f),
                                    style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                                    color = AppTextSecondary.copy(alpha = textAlpha * 0.95f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(trackW)
                                    .height(trackHeight)
                                    .clip(capsuleShape)
                                    .border(
                                        width = 0.8.dp,
                                        color = Color.White.copy(alpha = 0.92f),
                                        shape = capsuleShape
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(trackW)
                                    .height(trackHeight)
                                    .clip(capsuleShape)
                                    .border(
                                        width = 0.6.dp,
                                        color = Color.Black.copy(alpha = 0.06f),
                                        shape = capsuleShape
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = with(density) { (thumbInsetPx + handoffSlideOffsetPx).toDp() })
                                    .shadow(
                                        elevation = if (handoffThumbGlow > 0.5f) 10.dp else 4.dp,
                                        shape = CircleShape,
                                        spotColor = AppPrimary.copy(alpha = 0.22f + 0.14f * handoffThumbGlow),
                                        ambientColor = Color.Black.copy(alpha = 0.08f)
                                    )
                                    .size(thumbSize)
                                    .clip(CircleShape)
                                    .background(
                                        if (currentStatus == CallSessionStatus.Ended) {
                                            AppTextTertiary
                                        } else {
                                            AppPrimary
                                        }
                                    )
                                    .zIndex(2f),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    Color.White.copy(alpha = 0.18f + 0.24f * handoffThumbGlow),
                                                    Color.White.copy(alpha = 0.08f),
                                                    Color.Transparent
                                                ),
                                                start = Offset.Zero,
                                                end = Offset(thumbSizePx, thumbSizePx)
                                            )
                                        )
                                )
                                Icon(
                                    imageVector = if (isSlidToEnd) Icons.Filled.Check else Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    tint = AppBackground,
                                    modifier = Modifier.size(if (isSlidToEnd) 18.dp else 22.dp)
                                )
                            }
                        }
                    }
                }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun LiveCallViewPreview() {
    com.vaca.callmate.ui.preview.PreviewUnavailablePlaceholder("LiveCallView")
}
