package com.vaca.callmate.ui.screens.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.data.Language

/**
 * 像素对齐 iOS `VoiceRecordingOverlay.swift`：底部半圆渐变 + 提示文案 + 三圆点波形。
 *
 * iOS 关键布局：
 * - `visualRadiusMultiplier = 2.08`：半圆半径 = (屏宽 / 2) × 2.08 ≈ 屏宽 × 1.04，
 *   这样半圆左右两端远远超出屏幕，屏幕只露出中段缓弧。
 * - `SemicircleShape`：只填充上半圆路径（平边在下），所以"边界"就是那条圆弧。
 *
 * Android 实现要点：
 * - 用 `Path.arcTo` 画 **只画上半圆** 的路径，再 `drawPath(path, brush)` 填充。
 *   如果改用 `drawCircle` 就会把整个圆都涂上渐变，哪怕渐变边缘是透明，也会在父容器里
 *   形成深蓝色矩形（实际是大圆盘的上端截面 + 父级 clip 边界），这就是你看到的「矩形」。
 * - Radius 取屏宽 × 1.04，和 iOS 完全一致；圆左右两端远超屏幕，靠 Canvas 的 clipToBounds 裁掉。
 */
private const val VISUAL_RADIUS_MULTIPLIER = 2.08f

@Composable
fun VoiceRecordingOverlay(
    language: Language,
    isCancelling: Boolean,
    modifier: Modifier = Modifier,
) {
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val radiusDp = maxWidth * (VISUAL_RADIUS_MULTIPLIER / 2f)

        // 半圆背景：Canvas 高度 = radius，宽度 fillMaxWidth。clipToBounds 保证 Path 不会把
        // 超出 Canvas 左右的弧渲染进别的图层，实际「可见形状」由 Path 本身决定（上半圆），
        // Canvas 的宽度只是一个视口。
        Canvas(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(radiusDp)
                .clipToBounds(),
        ) {
            val cw = size.width
            val ch = size.height
            val cx = cw / 2f
            val cy = ch
            val r = ch
            drawSemicircle(
                centerX = cx,
                baseY = cy,
                radius = r,
                brush = if (isCancelling) {
                    cancelBaseBrush(Offset(cx, cy), r)
                } else {
                    primaryBaseBrush(Offset(cx, cy), r)
                },
            )

            // Glow: 只在半圆内部发光（类似 iOS 的 `.mask(SemicircleShape)`），避免椭圆溢出成矩形
            val glowA = 0.45f + glow * 0.15f
            val glowW = cw * 0.96f
            val glowH = cw * 0.76f
            val glowOffsetY = cw * 0.18f
            val glowCenter = Offset(cx, cy + glowOffsetY)
            withSemicircleClip(centerX = cx, baseY = cy, radius = r) {
                drawOval(
                    brush = Brush.radialGradient(
                        colors = if (isCancelling) {
                            listOf(
                                Color(0xFFE03E3E).copy(alpha = glowA * 0.9f),
                                Color(0xFFEE5C5C).copy(alpha = glowA * 0.5f),
                                Color(0xFFEE5C5C).copy(alpha = 0.04f),
                                Color.Transparent,
                            )
                        } else {
                            listOf(
                                Color(0xFF007AFF).copy(alpha = glowA * 0.9f),
                                Color(0xFF3395FF).copy(alpha = glowA * 0.5f),
                                Color(0xFF3395FF).copy(alpha = 0.04f),
                                Color.Transparent,
                            )
                        },
                        center = glowCenter,
                        radius = cw * 0.46f,
                    ),
                    topLeft = Offset(cx - glowW / 2f, cy + glowOffsetY - glowH / 2f),
                    size = Size(glowW, glowH),
                )
            }
        }

        // 文案 + 三圆点：挂在半圆的上方（距底部 gesture bar 56dp，和 iOS 完全一致）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 56.dp, top = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (isCancelling) {
                    if (language == Language.Zh) "松手取消" else "Release to Cancel"
                } else {
                    if (language == Language.Zh) "松手发送，上移取消" else "Release to Send, Slide Up to Cancel"
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.95f),
                letterSpacing = 0.2.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            RecordingDotsRow()
        }
    }
}

/** 画上半圆 Path（平边在下 y=baseY），再用传入 brush 填充。 */
private fun DrawScope.drawSemicircle(
    centerX: Float,
    baseY: Float,
    radius: Float,
    brush: Brush,
) {
    val path = buildSemicirclePath(centerX, baseY, radius)
    drawPath(path = path, brush = brush)
}

/** 同一条上半圆路径，用于 glow 层裁剪。 */
private fun DrawScope.withSemicircleClip(
    centerX: Float,
    baseY: Float,
    radius: Float,
    block: DrawScope.() -> Unit,
) {
    val path = buildSemicirclePath(centerX, baseY, radius)
    clipPath(path, block = block)
}

private fun buildSemicirclePath(centerX: Float, baseY: Float, radius: Float): Path {
    return Path().apply {
        moveTo(centerX - radius, baseY)
        arcTo(
            rect = Rect(
                left = centerX - radius,
                top = baseY - radius,
                right = centerX + radius,
                bottom = baseY + radius,
            ),
            // 0° = 右，180° = 左；顺时针扫 180° 从左经上到右。
            startAngleDegrees = 180f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false,
        )
        close()
    }
}

@Composable
private fun RecordingDotsRow() {
    val pulse by rememberInfiniteTransition(label = "dots").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(720, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotsPulse"
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        val scales = listOf(0.92f, 1.02f, 1.14f)
        val opacities = listOf(0.72f, 0.84f, 1f)
        val sizes = listOf(14.dp, 14.dp, 18.dp)
        repeat(3) { index ->
            val phase = ((pulse + index * 0.12f) % 1f)
            val s = scales[index] * (0.96f + phase * 0.08f)
            val o = opacities[index]
            Box(
                modifier = Modifier
                    .size(sizes[index])
                    .scale(s)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.96f * o)),
            )
        }
    }
}

/** 与 iOS `baseGradient(radius:)` 非取消态 stops 对齐；center 必须是 Canvas 内绝对坐标 */
private fun primaryBaseBrush(center: Offset, radius: Float): Brush {
    return Brush.radialGradient(
        colorStops = arrayOf(
            0f to Color(0xFF005ECB),
            0.18f to Color(0xFF006ADF),
            0.34f to Color(0xFF007AFF).copy(alpha = 0.96f),
            0.48f to Color(0xFF1A8AFF).copy(alpha = 0.88f),
            0.60f to Color(0xFF3395FF).copy(alpha = 0.62f),
            0.68f to Color(0xFF3395FF).copy(alpha = 0.34f),
            0.74f to Color(0xFF3395FF).copy(alpha = 0.14f),
            0.79f to Color(0xFF3395FF).copy(alpha = 0.035f),
            0.84f to Color.Transparent,
            1f to Color.Transparent,
        ),
        center = center,
        radius = radius,
    )
}

/** 与 iOS `baseGradient(radius:)` 取消态 stops 对齐 */
private fun cancelBaseBrush(center: Offset, radius: Float): Brush {
    return Brush.radialGradient(
        colorStops = arrayOf(
            0f to Color(0xFFCC2D2D),
            0.18f to Color(0xFFD43333),
            0.34f to Color(0xFFE03E3E).copy(alpha = 0.96f),
            0.48f to Color(0xFFE85050).copy(alpha = 0.88f),
            0.60f to Color(0xFFEE5C5C).copy(alpha = 0.62f),
            0.68f to Color(0xFFEE5C5C).copy(alpha = 0.34f),
            0.74f to Color(0xFFEE5C5C).copy(alpha = 0.14f),
            0.79f to Color(0xFFEE5C5C).copy(alpha = 0.035f),
            0.84f to Color.Transparent,
            1f to Color.Transparent,
        ),
        center = center,
        radius = radius,
    )
}
