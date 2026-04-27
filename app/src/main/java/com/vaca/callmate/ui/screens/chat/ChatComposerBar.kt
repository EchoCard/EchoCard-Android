package com.vaca.callmate.ui.screens.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.Gray400
import com.vaca.callmate.ui.theme.Gray500
import com.vaca.callmate.ui.theme.Gray700
import com.vaca.callmate.ui.theme.Gray800

private enum class ComposerInputMode { Voice, Text }

/**
 * 像素对齐 iOS `ChatComposerBar.swift` 的 `useGlassContainer` 模式（AI 分身嵌入页）。
 */
@Composable
fun ChatComposerBarGlass(
    language: Language,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hintActive: Boolean = false,
    onVoiceStart: () -> Unit,
    onVoiceSend: () -> Unit,
    onVoiceCancel: () -> Unit,
    onSendText: (String) -> Unit,
    onVoiceCancelStateChanged: (Boolean) -> Unit = {},
    onRecordingStateChange: ((Boolean) -> Unit)? = null,
) {
    var inputMode by remember { mutableStateOf(ComposerInputMode.Voice) }
    var isRecording by remember { mutableStateOf(false) }
    var willCancelVoice by remember { mutableStateOf(false) }
    var inputValue by remember { mutableStateOf("") }

    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    val ctx = LocalContext.current
    val windowW = remember(cfg.screenWidthDp, density) { cfg.screenWidthDp * density.density }
    val windowH = remember(cfg.screenHeightDp, density) { cfg.screenHeightDp * density.density }

    val safeBottomPx = remember(ctx) {
        val res = ctx.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id > 0) res.getDimensionPixelSize(id).toFloat() else 0f
    }
    val glassBottomVisualExtension = safeBottomPx + with(density) { 80.dp.toPx() }

    Column(modifier = modifier.fillMaxWidth()) {
        if (inputMode == ComposerInputMode.Text) {
            GlassKeyboardBar(
                language = language,
                enabled = enabled,
                text = inputValue,
                onTextChange = { inputValue = it },
                onSubmit = {
                    if (!enabled) return@GlassKeyboardBar
                    val s = inputValue.trim()
                    if (s.isNotEmpty()) {
                        onSendText(s)
                        inputValue = ""
                    }
                },
                onSwitchToVoice = { inputMode = ComposerInputMode.Voice },
            )
        } else {
            var voiceAreaCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
            // 手势协程内部读 latest 值，避免 layout 变化重建 LayoutCoordinates / windowW/H
            // 被当成 pointerInput key 触发 gesture 重启 —— 按下瞬间 AvatarQuickActionsRow 隐藏
            // 会改变外层 layout，造成半圆判定在错误坐标系下短暂翻转，进而红/蓝闪烁。
            val latestCoords = rememberUpdatedState(voiceAreaCoords)
            val latestWindowW = rememberUpdatedState(windowW)
            val latestWindowH = rememberUpdatedState(windowH)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(horizontal = 16.dp)
                    .then(
                        if (isRecording) {
                            Modifier
                        } else {
                            Modifier.shadow(
                                elevation = 14.dp,
                                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                                spotColor = Color(0xFF64748B).copy(alpha = 0.055f),
                                ambientColor = Color.Transparent
                            )
                        }
                    )
            ) {
                if (!isRecording) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp + with(density) { glassBottomVisualExtension.toDp() })
                            .clip(
                                RoundedCornerShape(
                                    topStart = 20.dp,
                                    topEnd = 20.dp,
                                    bottomEnd = 0.dp,
                                    bottomStart = 0.dp
                                )
                            )
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.34f),
                                        Color(0xFFEDF3FF).copy(alpha = 0.22f)
                                    )
                                )
                            )
                            .border(
                                width = 0.5.dp,
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0f to Color.White.copy(alpha = 0.44f),
                                        0.5f to Color.White.copy(alpha = 0.26f),
                                        1f to Color.Transparent
                                    )
                                ),
                                shape = RoundedCornerShape(
                                    topStart = 20.dp,
                                    topEnd = 20.dp,
                                    bottomEnd = 0.dp,
                                    bottomStart = 0.dp
                                )
                            )
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp)
                            .height(0.5.dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.9f),
                                        Color.White,
                                        Color.White.copy(alpha = 0.9f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }
                GlassVoiceInnerBar(
                    language = language,
                    enabled = enabled,
                    hintActive = hintActive,
                    isRecording = isRecording,
                    willCancelVoice = willCancelVoice,
                    onSwitchToText = { inputMode = ComposerInputMode.Text },
                    voiceAreaModifier = Modifier
                        .onGloballyPositioned { voiceAreaCoords = it }
                        .pointerInput(enabled) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (!enabled) return@awaitEachGesture
                                // consume down + 后续 move，阻止外层 ModalBottomSheet 的
                                // anchoredDraggable / nestedScroll 把手指移动当成下拉手势
                                // 拖走整个 AI 分身页面。
                                down.consume()
                                // 先于本地 isRecording 通知父级，避免一帧内父级仍为 alpha=1、子层已透明
                                // 从而把下层 VoiceRecordingOverlay 的深蓝中心「透」成矩形闪一下。
                                onRecordingStateChange?.invoke(true)
                                isRecording = true
                                willCancelVoice = false
                                onVoiceCancelStateChanged(false)
                                onVoiceStart()
                                var localCancel = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    if (event.changes.all { !it.pressed }) {
                                        for (change in event.changes) change.consume()
                                        break
                                    }
                                    val coords = latestCoords.value
                                    // coords 尚未附着时保持上次判定，不要用本地坐标配 window
                                    // 尺寸做跨坐标系比较 —— 那会让手指不动也闪红。仍然 consume
                                    // 事件，避免在此期间外层把 move delta 吃走导致页面被拖走。
                                    if (coords == null || !coords.isAttached) {
                                        for (change in event.changes) change.consume()
                                        continue
                                    }
                                    for (change in event.changes) {
                                        val pos = coords.localToWindow(change.position)
                                        val next = computeHoldToTalkCancel(
                                            finger = pos,
                                            windowWidth = latestWindowW.value,
                                            windowHeight = latestWindowH.value,
                                        )
                                        if (next != localCancel) {
                                            localCancel = next
                                            willCancelVoice = next
                                            onVoiceCancelStateChanged(next)
                                        }
                                        change.consume()
                                    }
                                }
                                val cancelled = localCancel
                                isRecording = false
                                onRecordingStateChange?.invoke(false)
                                willCancelVoice = false
                                onVoiceCancelStateChanged(false)
                                if (cancelled) onVoiceCancel() else onVoiceSend()
                            }
                        },
                )
            }
        }
    }
}

private fun computeHoldToTalkCancel(
    finger: Offset,
    windowWidth: Float,
    windowHeight: Float,
): Boolean {
    if (windowWidth <= 0f || windowHeight <= 0f) return false
    val cx = windowWidth / 2f
    val cy = windowHeight
    val r = (windowWidth / 2f) * 1.5f
    val dx = finger.x - cx
    val dy = finger.y - cy
    val insideSemicircle = (dx * dx + dy * dy <= r * r) && (dy <= 0f)
    return !insideSemicircle
}

@Composable
private fun GlassVoiceInnerBar(
    language: Language,
    enabled: Boolean,
    hintActive: Boolean,
    isRecording: Boolean,
    willCancelVoice: Boolean,
    onSwitchToText: () -> Unit,
    voiceAreaModifier: Modifier = Modifier,
) {
    val t: (String, String) -> String = { zh, en -> if (language == Language.Zh) zh else en }
    val hintPulse by rememberInfiniteTransition(label = "voiceHintPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voiceHintPulseValue"
    )
    val hintBorderAlpha = if (hintActive && enabled && !isRecording) 0.15f + (0.75f * hintPulse) else 0.82f
    val hintScale = if (hintActive && enabled && !isRecording) 1f + (0.022f * hintPulse) else 1f
    val foregroundColor = when {
        isRecording -> Color.Transparent
        enabled -> Gray800
        else -> Gray800.copy(alpha = 0.45f)
    }
    val iconTint = when {
        isRecording -> Color.Transparent
        enabled -> Gray700
        else -> Gray700.copy(alpha = 0.45f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(50.dp)
                .graphicsLayer {
                    scaleX = hintScale
                    scaleY = hintScale
                }
                .clip(RoundedCornerShape(16.dp))
                .border(
                    width = 0.5.dp,
                    color = if (isRecording) Color.Transparent else Color.White.copy(alpha = hintBorderAlpha),
                    shape = RoundedCornerShape(16.dp)
                )
                .background(Color.Transparent)
        ) {
            when {
                isRecording && willCancelVoice -> Unit
                isRecording -> Unit
                else ->
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.68f),
                                        Color.White.copy(alpha = 0.42f)
                                    )
                                )
                            )
                    )
            }
            if (!isRecording) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(0.5.dp)
                        .background(
                            Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.28f to Color.White.copy(alpha = 0.9f),
                                    0.5f to Color.White,
                                    0.72f to Color.White.copy(alpha = 0.9f),
                                    1f to Color.Transparent
                                )
                            )
                        )
                )
            }
            Row(
                modifier = voiceAreaModifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = when {
                        isRecording && willCancelVoice -> t("松手取消", "Release to Cancel")
                        isRecording -> t("松开发送", "Release to Send")
                        else -> t("按住说话", "Hold to Talk")
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = foregroundColor
                )
            }
        }
        IconButton(
            onClick = {
                if (enabled && !isRecording) onSwitchToText()
            },
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Keyboard,
                contentDescription = null,
                tint = when {
                    isRecording -> Color.Transparent
                    enabled -> Gray500
                    else -> Gray500.copy(alpha = 0.45f)
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun GlassKeyboardBar(
    language: Language,
    enabled: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSwitchToVoice: () -> Unit,
) {
    val t: (String, String) -> String = { zh, en -> if (language == Language.Zh) zh else en }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 6.dp)
            .padding(top = 6.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.55f),
                        Color(0xFFF5F5F7).copy(alpha = 0.82f)
                    )
                )
            )
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = text,
            onValueChange = { if (enabled) onTextChange(it) },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            placeholder = {
                Text(
                    t("请输入文字", "Enter text"),
                    color = Gray400,
                    fontSize = 15.sp
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedTextColor = AppTextPrimary,
                unfocusedTextColor = AppTextPrimary,
                cursorColor = AppPrimary
            ),
            singleLine = false,
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (enabled) onSubmit() }),
            textStyle = TextStyle(fontSize = 15.sp)
        )
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            IconButton(
                onClick = { if (enabled) onSubmit() },
                modifier = Modifier.size(36.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(AppPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        } else {
            IconButton(onClick = { if (enabled) onSwitchToVoice() }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (enabled) Gray500 else Gray500.copy(alpha = 0.45f),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
