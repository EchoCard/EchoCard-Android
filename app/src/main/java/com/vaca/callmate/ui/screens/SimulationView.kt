package com.vaca.callmate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.CallRecord
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.repository.CallRepository
import com.vaca.callmate.features.calls.SimulationCallController
import com.vaca.callmate.features.calls.SimulationUiPhase
import com.vaca.callmate.features.calls.SimDialogMessage
import com.vaca.callmate.ui.theme.AppBackground
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppBorder
import com.vaca.callmate.ui.theme.AppError
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppRadius
import com.vaca.callmate.ui.theme.AppSeparator
import com.vaca.callmate.ui.theme.AppSpacing
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppTextTertiary
import com.vaca.callmate.ui.theme.AppTypography

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private fun formatSimDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * 与 iOS `SimulationView` 一致：真实 WebSocket `scene=call`、麦克风+回声消除上行、TTS、流式气泡、挂断后持久化。
 */
@Composable
fun SimulationView(
    onEnd: (CallRecord) -> Unit,
    language: Language,
    callRepository: CallRepository,
    bleManager: BleManager,
    preferences: AppPreferences,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val isPreview = LocalInspectionMode.current
    val controller = remember {
        SimulationCallController(
            context = context,
            bleManager = bleManager,
            preferences = preferences,
            language = language,
            callRepository = callRepository,
            onSessionFinished = { rec ->
                rec?.let { onEnd(it) }
            }
        )
    }
    DisposableEffect(Unit) {
        onDispose { controller.dispose() }
    }
    LaunchedEffect(Unit) {
        if (isPreview) return@LaunchedEffect
        controller.start()
    }

    val phase by controller.phase.collectAsState()
    val durationSeconds by controller.durationSeconds.collectAsState()
    val messages by controller.dialogMessages.collectAsState()
    val streaming by controller.ttsStreamingText.collectAsState()
    val err by controller.errorText.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, streaming) {
        val last = messages.size + if (streaming.isNotEmpty()) 1 else 0
        if (last > 0) {
            listState.scrollToItem(last - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackgroundSecondary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            simulationHeader(
                phase = phase,
                language = language,
                errorText = err,
                durationSeconds = durationSeconds
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 16.dp,
                    bottom = 86.dp
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                items(messages, key = { it.id }) { msg ->
                    simulationMessageRow(msg = msg)
                }
                if (streaming.isNotEmpty()) {
                    item(key = "stream") {
                        simulationStreamingRow(streaming = streaming)
                    }
                }
            }
            simulationBottomBar(
                enabled = phase != SimulationUiPhase.EndedUser &&
                    phase != SimulationUiPhase.EndedAi &&
                    phase != SimulationUiPhase.Error,
                language = language,
                onHangUp = { controller.userHangUp() }
            )
        }

        if (phase == SimulationUiPhase.Connecting || phase == SimulationUiPhase.PickingUp) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                processingIndicator()
            }
        }
    }
}

@Composable
private fun simulationHeader(
    phase: SimulationUiPhase,
    language: Language,
    errorText: String?,
    durationSeconds: Int
) {
    val statusLabel = when (phase) {
        SimulationUiPhase.Connecting -> t("正在连接…", "Connecting…", language)
        SimulationUiPhase.PickingUp -> t("AI 正在接听…", "AI answering…", language)
        SimulationUiPhase.InCall -> t("模拟通话中，可随时挂断", "Simulating; hang up anytime", language)
        SimulationUiPhase.EndedUser -> t("通话已结束", "Call ended", language)
        SimulationUiPhase.EndedAi -> t("AI 已挂断", "AI Hung Up", language)
        SimulationUiPhase.Error -> errorText ?: t("连接失败", "Error", language)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(52.dp)
            .padding(horizontal = 20.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = t("模拟来电", "Test Call", language),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = statusLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = AppTextSecondary
            )
        }
        if (phase == SimulationUiPhase.InCall) {
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = AppSuccess
                )
                Text(
                    text = formatSimDuration(durationSeconds),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppSuccess,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun simulationStreamingRow(streaming: String) {
    val aiBubbleShape = SimBubbleShape(isUser = false)
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = screenWidth * 0.8f)
                .shadow(1.5.dp, aiBubbleShape, spotColor = Color.Black.copy(alpha = 0.04f), ambientColor = Color.Transparent)
                .shadow(6.dp, aiBubbleShape, spotColor = Color.Black.copy(alpha = 0.05f), ambientColor = Color.Transparent)
                .clip(aiBubbleShape)
                .background(Color.White.copy(alpha = 0.82f))
                .border(0.5.dp, Color.White.copy(alpha = 0.7f), aiBubbleShape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = streaming,
                fontSize = 17.sp,
                lineHeight = 23.sp,
                color = AppTextPrimary
            )
        }
    }
}

@Composable
private fun simulationMessageRow(msg: SimDialogMessage) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isAi) Arrangement.Start else Arrangement.End
    ) {
        if (msg.isAi) {
            val aiBubbleShape = SimBubbleShape(isUser = false)
            Box(
                modifier = Modifier
                    .widthIn(max = screenWidth * 0.8f)
                    .shadow(1.5.dp, aiBubbleShape, spotColor = Color.Black.copy(alpha = 0.04f), ambientColor = Color.Transparent)
                    .shadow(5.dp, aiBubbleShape, spotColor = Color.Black.copy(alpha = 0.04f), ambientColor = Color.Transparent)
                    .clip(aiBubbleShape)
                    .background(Color.White.copy(alpha = 0.82f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.7f), aiBubbleShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = msg.text,
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                    color = AppTextPrimary
                )
            }
        } else {
            val userBubbleShape = SimBubbleShape(isUser = true)
            Box(
                modifier = Modifier
                    .widthIn(max = screenWidth * 0.75f)
                    .clip(userBubbleShape)
                    .background(Color(0xFF007AFF))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = msg.text,
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                    color = Color.White
                )
            }
        }
    }
}

private class SimBubbleShape(private val isUser: Boolean) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        val r18 = with(density) { 18.dp.toPx() }
        val r4 = with(density) { 4.dp.toPx() }
        val path = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 0f, top = 0f, right = size.width, bottom = size.height,
                    topLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(r18),
                    topRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(r18),
                    bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(if (isUser) r18 else r4),
                    bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(if (isUser) r4 else r18)
                )
            )
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

@Composable
private fun processingIndicator() {
    val bubbleShape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .shadow(1.5.dp, bubbleShape, spotColor = Color.Black.copy(alpha = 0.04f), ambientColor = Color.Transparent)
            .clip(bubbleShape)
            .background(Color.White.copy(alpha = 0.82f))
            .border(0.5.dp, Color.White.copy(alpha = 0.7f), bubbleShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(AppTextTertiary)
            )
        }
    }
}

@Composable
private fun simulationBottomBar(
    enabled: Boolean,
    language: Language,
    onHangUp: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .size(64.dp)
                .shadow(12.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.10f), ambientColor = Color.Transparent)
                .shadow(3.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.05f), ambientColor = Color.Transparent)
                .clip(CircleShape)
                .background(AppError)
                .clickable(enabled = enabled, onClick = onHangUp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.CallEnd,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = t("挂断", "Hang Up", language),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SimulationViewPreview() {
    com.vaca.callmate.ui.preview.PreviewUnavailablePlaceholder("SimulationView")
}
