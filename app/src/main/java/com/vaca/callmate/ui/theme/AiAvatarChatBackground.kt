package com.vaca.callmate.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 与 iOS `AISecView` / `OnboardingView` 的 `onboardingBackground`（LinearGradient + 三层 RadialGradient）一致。
 */
@Composable
fun AiAvatarChatBackground(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFF6F8FF),
                                Color(0xFFF3F5FF),
                                Color(0xFFF5F4FF),
                                Color(0xFFF4F7FF)
                            )
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0x80DCE8FF), Color.Transparent),
                            center = Offset(wPx * 0.2f, hPx * 0.1f),
                            radius = with(density) { 400.dp.toPx() }
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0x59E6E1FA), Color.Transparent),
                            center = Offset(wPx * 0.85f, hPx * 0.6f),
                            radius = with(density) { 350.dp.toPx() }
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0x4DD7E6FF), Color.Transparent),
                            center = Offset(wPx * 0.4f, hPx * 0.9f),
                            radius = with(density) { 300.dp.toPx() }
                        )
                    )
            )
        }
    }
}
