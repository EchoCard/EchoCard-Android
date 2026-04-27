package com.vaca.callmate.ui.screens

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.ProcessStrategyStore
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppBorder
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSpacing
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.CallMateTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

/** 与 iOS `BoundContentView` 像素级对齐（浅色 F5F5F5 底）。 */
@Composable
fun BoundContentScreen(
    language: Language,
    bleManager: BleManager?,
    onContinue: (useDeviceStrategy: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val pageBg = Color(0xFFF5F5F5)
    val strategyFlow = remember(bleManager) {
        bleManager?.pendingDeviceStrategy ?: flowOf(null)
    }
    val pendingStrategy by strategyFlow.collectAsState(initial = null)

    var iconVisible by remember { mutableStateOf(false) }
    var contentVisible by remember { mutableStateOf(false) }
    var cardsVisible by remember { mutableStateOf(false) }
    var showStrategySheet by remember { mutableStateOf(false) }

    val hasDeviceStrategy = pendingStrategy != null

    LaunchedEffect(Unit) {
        delay(100)
        iconVisible = true
    }
    LaunchedEffect(Unit) {
        delay(300)
        contentVisible = true
    }
    LaunchedEffect(Unit) {
        delay(500)
        cardsVisible = true
    }

    LaunchedEffect(pendingStrategy, cardsVisible) {
        if (pendingStrategy != null && cardsVisible) {
            showStrategySheet = true
        }
    }

    val iconScale by animateFloatAsState(
        targetValue = if (iconVisible) 1f else 0.5f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
        label = "boundIconScale"
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (iconVisible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
        label = "boundIconAlpha"
    )

    val contentAlpha by animateFloatAsState(
        targetValue = if (contentVisible) 1f else 0f,
        animationSpec = tween(500, easing = LinearOutSlowInEasing),
        label = "boundContentAlpha"
    )
    val contentOffsetY by animateDpAsState(
        targetValue = if (contentVisible) 0.dp else 10.dp,
        animationSpec = tween(500, easing = LinearOutSlowInEasing),
        label = "boundContentOff"
    )

    val cardsAlpha by animateFloatAsState(
        targetValue = if (cardsVisible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 320f),
        label = "boundCardsAlpha"
    )
    val cardsOffsetY by animateDpAsState(
        targetValue = if (cardsVisible) 0.dp else 20.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 320f),
        label = "boundCardsOff"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pageBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 120.dp)
        ) {
            // Bot icon + badge（与 iOS 一致：88×88，圆角 26，渐变 + 阴影）
            Box(
                modifier = Modifier
                    .padding(top = 100.dp)
                    .align(Alignment.CenterHorizontally)
                    .graphicsLayer {
                        alpha = iconAlpha
                        scaleX = iconScale
                        scaleY = iconScale
                    }
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .shadow(16.dp, RoundedCornerShape(26.dp), spotColor = Color(0x4D007AFF), ambientColor = Color(0x20007AFF))
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF007AFF), Color(0xFF5856D6))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    BoundBotIconCanvas(modifier = Modifier.size(44.dp))
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 6.dp, y = 6.dp)
                        .size(32.dp)
                        .shadow(6.dp, CircleShape, spotColor = Color(0x6634C759), ambientColor = Color(0x4034C759)),
                    shape = CircleShape,
                    color = Color(0xFF34C759),
                    border = BorderStroke(3.dp, pageBg)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color.White
                        )
                    }
                }
            }

            Text(
                text = t("AI 分身已就绪", "AI Agent Ready", language),
                style = TextStyle(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary,
                    letterSpacing = (-0.5).sp
                ),
                modifier = Modifier
                    .padding(top = 16.dp)
                    .align(Alignment.CenterHorizontally)
                    .offset(y = contentOffsetY)
                    .graphicsLayer { alpha = contentAlpha }
            )

            Row(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 24.dp)
                    .align(Alignment.CenterHorizontally)
                    .offset(y = contentOffsetY)
                    .graphicsLayer { alpha = contentAlpha },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0x1934C759))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34C759))
                    )
                    Text(
                        text = t("EchoCard 已连接", "EchoCard Connected", language),
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF34C759)
                        )
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFFF3F4F6))
                        .border(1.dp, Color(0x99E5E7EB), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = t("实习期", "Intern", language),
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF4B5563)
                        )
                    )
                }
            }

            // 2×2 功能卡片（间距 10）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .offset(y = cardsOffsetY)
                    .graphicsLayer { alpha = cardsAlpha },
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BoundFeatureCard(
                        icon = Icons.Default.Phone,
                        iconColor = Color(0xFF007AFF),
                        title = t("分身代接", "Auto Answer", language),
                        desc = t("自动应答营销骚扰\n智能过滤无效来电", "Auto-answer spam calls\nSmart filtering", language),
                        modifier = Modifier.weight(1f)
                    )
                    BoundFeatureCard(
                        icon = Icons.Default.Shield,
                        iconColor = Color(0xFFFF9500),
                        title = t("重要来电", "Important Calls", language),
                        desc = t("智能识别重要电话\n第一时间通知你", "Smart detection\nInstant notification", language),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BoundFeatureCard(
                        icon = Icons.Default.School,
                        iconColor = Color(0xFFAF52DE),
                        title = t("指导学习", "Guided Learning", language),
                        desc = t("指导越多越懂你\n持续优化处理策略", "More guidance, smarter\nContinuous optimization", language),
                        modifier = Modifier.weight(1f)
                    )
                    BoundFeatureCard(
                        icon = Icons.Default.EmojiEvents,
                        iconColor = Color(0xFFFF2D55),
                        title = t("转正条件", "Graduation", language),
                        desc = t("实习满 3 个月\n满意评价后可转正", "3 months internship\nGood ratings to graduate", language),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text(
                text = t(
                    "AI 分身将通过学习你的接听习惯，逐步提升\n来电识别准确度，为你提供更智能的服务",
                    "Your AI agent learns your call habits to\nimprove accuracy and provide smarter service",
                    language
                ),
                style = TextStyle(
                    fontSize = 13.sp,
                    color = Color(0xFF9CA3AF),
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp
                ),
                modifier = Modifier
                    .padding(top = 16.dp, start = 20.dp, end = 20.dp, bottom = 120.dp)
                    .fillMaxWidth()
                    .offset(y = cardsOffsetY)
                    .graphicsLayer { alpha = cardsAlpha }
            )
        }

        // 底部渐变 + 主按钮（与 iOS 一致）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                pageBg.copy(alpha = 0.9f),
                                pageBg
                            )
                        )
                    )
            )

            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val btnScale by animateFloatAsState(
                targetValue = if (pressed) 0.95f else 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                label = "boundBtnScale"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 40.dp)
                    .align(Alignment.BottomCenter)
                    .offset(y = cardsOffsetY)
                    .graphicsLayer {
                        alpha = cardsAlpha
                        scaleX = btnScale
                        scaleY = btnScale
                    }
                    .shadow(10.dp, RoundedCornerShape(16.dp), spotColor = Color(0x40007AFF), ambientColor = Color.Transparent)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF007AFF))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        if (hasDeviceStrategy) {
                            showStrategySheet = true
                        } else {
                            onContinue(false)
                        }
                    }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = t("开始体验", "Get Started", language),
                    style = TextStyle(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                )
            }
        }
    }

    if (showStrategySheet && bleManager != null) {
        StrategyChoiceDialog(
            language = language,
            bleManager = bleManager,
            onContinue = { useDevice ->
                showStrategySheet = false
                onContinue(useDevice)
            }
        )
    }
}

@Composable
private fun BoundFeatureCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    title: String,
    desc: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.03f), ambientColor = Color.Transparent)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .borderIosFeatureCard()
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = iconColor
                )
            }
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextPrimary
                )
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = desc,
            style = TextStyle(
                fontSize = 12.sp,
                color = Color(0xFF6B7280),
                lineHeight = 16.sp
            )
        )
    }
}

private fun Modifier.borderIosFeatureCard(): Modifier {
    return this.then(
        border(
            width = 1.dp,
            color = Color(0xCCF3F4F6),
            shape = RoundedCornerShape(16.dp)
        )
    )
}

/** 与 iOS `BoundBotIconView` Canvas 路径一致（24×24 坐标系）。 */
@Composable
private fun BoundBotIconCanvas(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = size.width / 24f
        val stroke = Stroke(
            width = 1.6f * s,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        val white = Color.White

        val antenna = Path().apply {
            moveTo(12f * s, 8f * s)
            lineTo(12f * s, 4f * s)
            lineTo(8f * s, 4f * s)
        }
        drawPath(antenna, white, style = stroke)

        val body = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 4f * s,
                    top = 8f * s,
                    right = 20f * s,
                    bottom = 20f * s,
                    cornerRadius = CornerRadius(2f * s, 2f * s)
                )
            )
        }
        drawPath(body, white, style = stroke)

        val leftArm = Path().apply {
            moveTo(2f * s, 14f * s)
            lineTo(4f * s, 14f * s)
        }
        drawPath(leftArm, white, style = stroke)

        val rightArm = Path().apply {
            moveTo(20f * s, 14f * s)
            lineTo(22f * s, 14f * s)
        }
        drawPath(rightArm, white, style = stroke)

        val leftEye = Path().apply {
            moveTo(9f * s, 13f * s)
            lineTo(9f * s, 15f * s)
        }
        drawPath(leftEye, white, style = stroke)

        val rightEye = Path().apply {
            moveTo(15f * s, 13f * s)
            lineTo(15f * s, 15f * s)
        }
        drawPath(rightEye, white, style = stroke)
    }
}

@Composable
private fun StrategyChoiceDialog(
    language: Language,
    bleManager: BleManager,
    onContinue: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(420.dp)
                    .shadow(
                        18.dp,
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        spotColor = Color.Black.copy(alpha = 0.08f),
                        ambientColor = Color.Transparent
                    )
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(AppSurface)
                    .border(
                        0.5.dp,
                        Color.White.copy(alpha = 0.55f),
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    )
                    .verticalScroll(rememberScrollState())
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = AppSpacing.xs, bottom = AppSpacing.sm)
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0x4D000000))
                )
                Box(
                    modifier = Modifier
                        .padding(bottom = AppSpacing.xs)
                        .align(Alignment.CenterHorizontally)
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(AppPrimary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = AppPrimary
                    )
                }
                Text(
                    text = t("检测到已有代接策略", "Existing Strategy Found", language),
                    style = TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTextPrimary,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.lg)
                )
                Text(
                    text = t(
                        "该设备上已保存了一套代接策略，您可以直接沿用，也可以通过 AI 向导重新配置。",
                        "This device already has a saved strategy. You can keep it or set up a new one with the AI wizard.",
                        language
                    ),
                    style = TextStyle(
                        fontSize = 15.sp,
                        color = AppTextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    ),
                    modifier = Modifier
                        .padding(top = AppSpacing.xs, start = AppSpacing.lg, end = AppSpacing.lg)
                        .fillMaxWidth()
                )

                Column(
                    modifier = Modifier
                        .padding(horizontal = AppSpacing.lg)
                        .padding(top = AppSpacing.lg, bottom = AppSpacing.xl)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    val primaryInteraction = remember { MutableInteractionSource() }
                    val primaryPressed by primaryInteraction.collectIsPressedAsState()
                    val primaryScale by animateFloatAsState(
                        if (primaryPressed) 0.95f else 1f,
                        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                        label = "stratPrimary"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = primaryScale
                                scaleY = primaryScale
                            }
                            .shadow(10.dp, RoundedCornerShape(16.dp), spotColor = AppPrimary.copy(alpha = 0.3f))
                            .clip(RoundedCornerShape(16.dp))
                            .background(AppPrimary)
                            .clickable(interactionSource = primaryInteraction, indication = null) {
                                bleManager.adoptPendingDeviceStrategy()
                                onContinue(true)
                            }
                            .padding(AppSpacing.xs),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    t("使用设备已有策略", "Use Device Strategy", language),
                                    style = TextStyle(
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                )
                                Text(
                                    t("直接沿用，快速进入主界面", "Keep existing setup, go straight to home", language),
                                    style = TextStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    val secondaryInteraction = remember { MutableInteractionSource() }
                    val secondaryPressed by secondaryInteraction.collectIsPressedAsState()
                    val secondaryScale by animateFloatAsState(
                        if (secondaryPressed) 0.95f else 1f,
                        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                        label = "stratSecondary"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = secondaryScale
                                scaleY = secondaryScale
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .background(AppBackgroundSecondary)
                            .borderStrategySecondary()
                            .clickable(interactionSource = secondaryInteraction, indication = null) {
                                scope.launch {
                                    bleManager.clearPendingDeviceStrategy()
                                    ProcessStrategyStore.resetToDefault(context)
                                    onContinue(false)
                                }
                            }
                            .padding(AppSpacing.xs),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = AppTextPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    t("重新配置 AI 向导", "Configure with AI Wizard", language),
                                    style = TextStyle(
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = AppTextPrimary
                                    )
                                )
                                Text(
                                    t("放弃设备策略，从默认配置重新训练", "Discard device strategy and start from defaults", language),
                                    style = TextStyle(fontSize = 12.sp, color = AppTextSecondary)
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = AppTextSecondary,
                                modifier = Modifier.size(20.dp)
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
private fun BoundContentScreenPreview() {
    CallMateTheme {
        BoundContentScreen(
            language = Language.Zh,
            bleManager = null,
            onContinue = {}
        )
    }
}

private fun Modifier.borderStrategySecondary(): Modifier {
    return this.then(border(1.dp, AppBorder, RoundedCornerShape(16.dp)))
}
