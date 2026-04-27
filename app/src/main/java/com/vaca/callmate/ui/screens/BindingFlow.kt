package com.vaca.callmate.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.data.AppState
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.Gray400
import com.vaca.callmate.ui.theme.Gray500

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

@Composable
fun BindingFlow(
    state: AppState,
    onStateChange: (AppState) -> Unit,
    language: Language,
    bleManager: BleManager? = null,
    onBound: (String) -> Unit = {},
    /** 与 iOS 一致：沿用设备策略进主界面时视为完成引导，避免下次冷启动回到 Landing。 */
    onAdoptDeviceStrategyToMain: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    when (state) {
        AppState.Landing -> LandingScreen(
            onBindClick = { onStateChange(AppState.Scanning) },
            onSkip = { onStateChange(AppState.Main) },
            language = language,
            modifier = modifier
        )
        AppState.Scanning -> ScanningBindingContent(
            language = language,
            bleManager = bleManager,
            onBound = { address -> if (address != null) onBound(address); onStateChange(AppState.Bound) },
            modifier = modifier
        )
        AppState.Bound -> BoundContentScreen(
            language = language,
            bleManager = bleManager,
            onContinue = { useDeviceStrategy ->
                if (useDeviceStrategy) {
                    onAdoptDeviceStrategyToMain()
                    onStateChange(AppState.Main)
                } else {
                    onStateChange(AppState.Onboarding)
                }
            },
            modifier = modifier
        )
        else -> {}
    }
}

// ---------------------------------------------------------------------------
// Landing screen — pixel-for-pixel port of iOS `LandingContentView`
// ---------------------------------------------------------------------------

@Composable
private fun LandingScreen(
    onBindClick: () -> Unit,
    onSkip: () -> Unit,
    language: Language,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "float")
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatOffset"
    )
    val floatOffset2 by infiniteTransition.animateFloat(
        initialValue = 10f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatOffset2"
    )
    val floatOffsetLightning by infiniteTransition.animateFloat(
        initialValue = -12f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatLightning"
    )
    val floatOffsetShield by infiniteTransition.animateFloat(
        initialValue = 8f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatShield"
    )

    var appeared by remember { mutableStateOf(false) }
    val appearAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(600),
        label = "appearAlpha"
    )
    val appearOffsetY by animateFloatAsState(
        targetValue = if (appeared) 0f else 20f,
        animationSpec = tween(600),
        label = "appearY"
    )

    LaunchedEffect(Unit) { appeared = true }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFF4F7FC), Color.White)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Skip button — top right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = t("跳过", "Skip", language),
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Gray500
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.68f))
                        .clickable(onClick = onSkip)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            // Center content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Illustration
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .graphicsLayer {
                            alpha = appearAlpha
                            translationY = appearOffsetY
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Outer circle
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF4F8FF))
                    )
                    // Inner circle
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE5EFFF))
                    )
                    // Floating small circle top-left
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .offset(x = (-64).dp, y = (-49).dp + floatOffset.dp)
                            .shadow(12.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.08f))
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                    // Floating small circle bottom-right
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .offset(x = 56.dp, y = 51.dp + floatOffset2.dp)
                            .shadow(12.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.08f))
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                    // Phone device icon — center
                    Canvas(
                        modifier = Modifier.size(48.dp, 84.dp)
                    ) {
                        val s = size.width / 48f
                        val bodyPath = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    left = 3f * s, top = 3f * s,
                                    right = 45f * s, bottom = 81f * s,
                                    cornerRadius = CornerRadius(10f * s)
                                )
                            )
                        }
                        drawPath(bodyPath, color = Color(0xFFA8B4CB))
                        drawPath(
                            bodyPath,
                            color = Color.Black,
                            style = Stroke(width = 4.5f * s, join = StrokeJoin.Round)
                        )
                        val notchPath = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    left = 18f * s, top = 12f * s,
                                    right = 30f * s, bottom = 16.5f * s,
                                    cornerRadius = CornerRadius(2.25f * s)
                                )
                            )
                        }
                        drawPath(notchPath, color = Color.Black)
                        val barPath = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    left = 16.5f * s, top = 67.5f * s,
                                    right = 31.5f * s, bottom = 70.5f * s,
                                    cornerRadius = CornerRadius(1.5f * s)
                                )
                            )
                        }
                        drawPath(barPath, color = Color.Black)
                    }
                    // Lightning bolt icon — top left
                    Canvas(
                        modifier = Modifier
                            .size(18.dp, 27.dp)
                            .offset(x = (-99).dp, y = (-70).dp + floatOffsetLightning.dp)
                    ) {
                        val sx = size.width / 24f
                        val sy = size.height / 36f
                        val path = Path().apply {
                            moveTo(12.5f * sx, 0f)
                            lineTo(0f, 20f * sy)
                            lineTo(10.5f * sx, 20f * sy)
                            lineTo(8.5f * sx, 36f * sy)
                            lineTo(24f * sx, 14f * sy)
                            lineTo(12.5f * sx, 14f * sy)
                            close()
                        }
                        drawPath(path, color = Color(0xFF3B82F6))
                    }
                    // Shield check icon — bottom right
                    Canvas(
                        modifier = Modifier
                            .size(24.dp, 28.dp)
                            .offset(x = 96.dp, y = 82.dp + floatOffsetShield.dp)
                    ) {
                        val sx = size.width / 32f
                        val sy = size.height / 38f
                        val shield = Path().apply {
                            moveTo(16f * sx, 0f)
                            lineTo(32f * sx, 7.5f * sy)
                            lineTo(32f * sx, 16.5f * sy)
                            cubicTo(32f * sx, 26.5f * sy, 25.5f * sx, 35f * sy, 16f * sx, 38f * sy)
                            cubicTo(6.5f * sx, 35f * sy, 0f, 26.5f * sy, 0f, 16.5f * sy)
                            lineTo(0f, 7.5f * sy)
                            close()
                        }
                        drawPath(shield, color = Color(0xFF4ADE80))
                        val check = Path().apply {
                            moveTo(9f * sx, 19f * sy)
                            lineTo(14f * sx, 24f * sy)
                            lineTo(23f * sx, 14f * sy)
                        }
                        drawPath(
                            check,
                            color = Color.White,
                            style = Stroke(
                                width = 3.5f * sx,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))

                // Title
                Text(
                    text = t("EchoCard · AI代接卡", "EchoCard · AI Call Agent", language),
                    style = TextStyle(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        color = AppTextPrimary
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = appearAlpha
                            translationY = appearOffsetY * 0.5f
                        }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Subtitle
                Text(
                    text = t(
                        "陌生来电我处理，重要电话提醒你",
                        "Strangers handled by AI, important calls reach you",
                        language
                    ),
                    style = TextStyle(
                        fontSize = 15.sp,
                        color = Color(0xFF8E8E93),
                        lineHeight = 22.sp
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .graphicsLayer {
                            alpha = appearAlpha
                            translationY = appearOffsetY * 0.5f
                        }
                )
            }

            // Bottom section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 48.dp)
                    .graphicsLayer {
                        alpha = appearAlpha
                        translationY = appearOffsetY
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = onBindClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 10.dp,
                            shape = RoundedCornerShape(18.dp),
                            spotColor = AppPrimary.copy(alpha = 0.25f),
                            ambientColor = Color.Transparent
                        ),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)),
                    shape = RoundedCornerShape(18.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Text(
                        text = t("绑定设备", "Bind Device", language),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = t("请确保设备已开机并开启蓝牙", "Ensure device is on & Bluetooth active", language),
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = Color(0xFF9CA3AF)
                    ),
                    modifier = Modifier.graphicsLayer { alpha = appearAlpha }
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun BindingFlowLandingPreview() {
    com.vaca.callmate.ui.preview.PreviewUnavailablePlaceholder("BindingFlow")
}
