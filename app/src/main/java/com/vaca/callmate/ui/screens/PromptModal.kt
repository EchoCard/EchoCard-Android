package com.vaca.callmate.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vaca.callmate.CallMateApplication
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.ProcessStrategyRule
import com.vaca.callmate.data.ProcessStrategyStore
import com.vaca.callmate.ui.theme.AppAccent
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppBorder
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppRadius
import com.vaca.callmate.ui.theme.AppSpacing
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppTypography
import com.vaca.callmate.ui.preview.PreviewUnavailablePlaceholder
import com.vaca.callmate.ui.theme.AppWarning
private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private data class ParsedRuleSections(
    val goal: String? = null,
    val points: String? = null,
    val examples: String? = null
)

private fun parseRuleSections(rule: String): ParsedRuleSections {
    var goal: String? = null
    var points: String? = null
    var examples: String? = null
    val sectionPatterns: List<Pair<String, (String) -> Unit>> = listOf(
        "处理目标" to { g -> goal = g },
        "处理要点" to { p ->
            points = (points ?: "") + (if (p.isEmpty()) "" else p + "\n")
        },
        "处理原则" to { p ->
            points = (points ?: "") + (if (p.isEmpty()) "" else p + "\n")
        },
        "处理策略" to { p ->
            points = (points ?: "") + (if (p.isEmpty()) "" else p + "\n")
        },
        "处理步骤" to { p ->
            points = (points ?: "") + (if (p.isEmpty()) "" else p + "\n")
        },
        "示例" to { e -> examples = e }
    )
    val remaining = rule.trim()
    for ((title, setter) in sectionPatterns) {
        val marker = "$title："
        val idx = remaining.indexOf(marker)
        if (idx >= 0) {
            val start = idx + marker.length
            var end = remaining.length
            for ((otherTitle, _) in sectionPatterns) {
                if (otherTitle == title) continue
                val otherMarker = "$otherTitle："
                val oi = remaining.indexOf(otherMarker, startIndex = start)
                if (oi >= 0 && oi < end) end = oi
            }
            val content = remaining.substring(start, end).trim()
            setter(content)
        }
    }
    return ParsedRuleSections(goal = goal, points = points?.trim(), examples = examples)
}

private data class RuleCategoryConfig(
    val displayTitle: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color
)

private val CategoryBank = Color(0xFF5AC8FA)
private val CategoryMarketing = Color(0xFFA2845E)
private val CategoryFallback = Color(0xFF8E8E93)

private val categoryConfigs: List<Pair<String, RuleCategoryConfig>> = listOf(
    "快递" to RuleCategoryConfig(
        displayTitle = "快递服务",
        subtitle = "快递/驿站/派件/取件",
        icon = Icons.Default.LocalShipping,
        color = AppSuccess
    ),
    "外卖" to RuleCategoryConfig(
        displayTitle = "外卖骑手",
        subtitle = "外卖/骑手",
        icon = Icons.Default.DirectionsBike,
        color = AppWarning
    ),
    "运营商" to RuleCategoryConfig(
        displayTitle = "运营商",
        subtitle = "移动/联通/电信",
        icon = Icons.Default.Wifi,
        color = AppAccent
    ),
    "银行" to RuleCategoryConfig(
        displayTitle = "银行保险",
        subtitle = "银行/保险/贷款/理财",
        icon = Icons.Default.AccountBalance,
        color = CategoryBank
    ),
    "营销" to RuleCategoryConfig(
        displayTitle = "营销广告",
        subtitle = "推销/房产/课程/广告",
        icon = Icons.Default.Campaign,
        color = CategoryMarketing
    ),
    "熟人" to RuleCategoryConfig(
        displayTitle = "熟人来电",
        subtitle = "熟人/朋友",
        icon = Icons.Default.Group,
        color = AppPrimary
    ),
    "未归类" to RuleCategoryConfig(
        displayTitle = "未归类来电",
        subtitle = "未分类/兜底",
        icon = Icons.Default.HelpOutline,
        color = CategoryFallback
    )
)

private fun configForRule(rule: ProcessStrategyRule): RuleCategoryConfig {
    val type = rule.type
    for ((key, config) in categoryConfigs) {
        if (type.contains(key)) return config
    }
    return RuleCategoryConfig(
        displayTitle = type,
        subtitle = "",
        icon = Icons.Default.HelpOutline,
        color = CategoryFallback
    )
}

private fun sortedRules(rules: List<ProcessStrategyRule>): List<ProcessStrategyRule> {
    val displayOrder = listOf("快递", "外卖", "运营商", "银行", "营销", "熟人", "未归类")
    return rules.sortedWith { a, b ->
        val ai = displayOrder.indexOfFirst { a.type.contains(it) }.takeIf { it >= 0 } ?: displayOrder.size
        val bi = displayOrder.indexOfFirst { b.type.contains(it) }.takeIf { it >= 0 } ?: displayOrder.size
        ai.compareTo(bi)
    }
}

private fun pointLines(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }

private val ExampleAmberTitle = Color(0xFFB8860B)
private val ExampleAmberBody = Color(0xFF6B5B3E)
private val ExampleAmberBg = Color(0xFFFFB340)
private val TitleDark = Color(0xFF1D1D1F)
private val BodyMuted = Color(0xFF3A3A3C)
private val SectionLabel = Color(0xFF86868B)

@Composable
fun PromptModal(
    onClose: () -> Unit,
    language: Language,
    modifier: Modifier = Modifier
) {
    if (LocalInspectionMode.current) {
        PreviewUnavailablePlaceholder("PromptModal")
        return
    }
    val context = LocalContext.current
    val preferences = remember(context) { AppPreferences(context.applicationContext) }
    val outboundRepository = remember(context) {
        (context.applicationContext as CallMateApplication).outboundRepository
    }
    val bleManager = remember(context) {
        (context.applicationContext as? CallMateApplication)?.bleManager
            ?: BleManager(context.applicationContext)
    }
    val bleReady by bleManager.isReady.collectAsState(initial = false)
    val connectedAddr by bleManager.connectedAddress.collectAsState(initial = null)
    var rules by remember { mutableStateOf<List<ProcessStrategyRule>>(emptyList()) }
    var showOnboarding by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        rules = ProcessStrategyStore.loadRules(context)
    }
    LaunchedEffect(showOnboarding) {
        if (!showOnboarding) {
            rules = ProcessStrategyStore.loadRules(context)
        }
    }
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            kotlinx.coroutines.delay(3000)
            toastMessage = null
        }
    }

    Box(modifier = modifier) {
        // 对标 iOS `PromptModalView`：overlay 全屏 + 浅灰底，非居中圆角卡片
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackgroundSecondary)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(52.dp)
                        .padding(horizontal = 12.dp)
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(40.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBackIos,
                            contentDescription = null,
                            tint = AppPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .clickable {
                                val isConnected = bleReady && !connectedAddr.isNullOrBlank()
                                if (isConnected) {
                                    showOnboarding = true
                                } else {
                                    toastMessage = t("请先连接 EchoCard", "Please connect EchoCard first", language)
                                }
                            },
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            tint = AppPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.xxs))
                        Text(
                            text = t("配置", "Configure", language),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppPrimary
                        )
                    }
                    Text(
                        text = t("完整规则", "Rules", language),
                        modifier = Modifier.align(Alignment.Center),
                        style = AppTypography.bodyLarge.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = AppTextPrimary
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AppSpacing.md)
                        .navigationBarsPadding()
                ) {
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    SecurityBanner(language = language)
                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    val ordered = sortedRules(rules)
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                        ordered.forEach { rule ->
                            RuleExpandedCard(rule = rule, language = language)
                        }
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.xxxl))
                }
            }
        }

        if (showOnboarding) {
            Dialog(
                onDismissRequest = { showOnboarding = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AppBackgroundSecondary)
                ) {
                    OnboardingView(
                        onComplete = { showOnboarding = false },
                        language = language,
                        bleManager = bleManager,
                        preferences = preferences,
                        outboundRepository = outboundRepository
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !toastMessage.isNullOrEmpty(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                text = toastMessage.orEmpty(),
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(AppRadius.lg))
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(AppSpacing.md),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SecurityBanner(language: Language) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(AppSuccess.copy(alpha = 0.06f))
            .border(0.5.dp, AppSuccess.copy(alpha = 0.12f), RoundedCornerShape(AppRadius.sm))
            .padding(horizontal = AppSpacing.md, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            AppSuccess.copy(alpha = 0.15f),
                            AppSuccess.copy(alpha = 0.08f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.VerifiedUser,
                contentDescription = null,
                tint = AppSuccess,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = t(
                "当前策略同步存储在 EchoCard 设备上，且仅限此手机（主机）可读取",
                "Strategy synced to EchoCard; only this phone (host) can read it.",
                language
            ),
            fontSize = 13.sp,
            color = BodyMuted,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun RuleExpandedCard(rule: ProcessStrategyRule, language: Language) {
    val config = configForRule(rule)
    val sections = remember(rule.rule) { parseRuleSections(rule.rule) }
    val subtitleTags = config.subtitle
        .split("/")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val outerShape = RoundedCornerShape(AppRadius.lg)
    val cardShadow = Modifier.shadow(
        elevation = 12.dp,
        shape = outerShape,
        spotColor = Color.Black.copy(alpha = 0.05f),
        ambientColor = Color.Black.copy(alpha = 0.04f)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(cardShadow)
            .clip(outerShape)
            .background(AppSurface)
            .border(0.5.dp, Color.White.copy(alpha = 0.5f), outerShape)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFE6F0FF).copy(alpha = 0.78f),
                            AppSurface.copy(alpha = 0.68f),
                            AppSurface.copy(alpha = 0.58f)
                        )
                    )
                )
        )
        // Top decorative hairline
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(0.5.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.White, Color.White, Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = AppRadius.lg,
                        topEnd = AppRadius.lg,
                        bottomEnd = 0.dp,
                        bottomStart = 0.dp
                    )
                )
                .background(
                    Brush.verticalGradient(
                        colors = listOf(config.color.copy(alpha = 0.06f), Color.Transparent)
                    )
                )
        )
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .shadow(
                            3.dp,
                            RoundedCornerShape(15.dp),
                            spotColor = config.color.copy(alpha = 0.094f),
                            ambientColor = Color.White.copy(alpha = 0.7f)
                        )
                        .clip(RoundedCornerShape(15.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    config.color.copy(alpha = 0.18f),
                                    config.color.copy(alpha = 0.12f)
                                )
                            )
                        )
                        .border(0.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        config.icon,
                        contentDescription = null,
                        tint = config.color,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = config.displayTitle,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TitleDark,
                        letterSpacing = (-0.3).sp
                    )
                    if (subtitleTags.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            subtitleTags.forEach { tag ->
                                Text(
                                    text = tag,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = config.color,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(config.color.copy(alpha = 0.06f))
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            GoalAndPointsBlock(sections = sections, language = language)
            ExamplesBlock(sections = sections, language = language)
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PromptModalPreview() {
    PromptModal(onClose = {}, language = Language.Zh)
}

@Composable
private fun GoalAndPointsBlock(
    sections: ParsedRuleSections,
    language: Language
) {
    val goal = sections.goal?.trim().orEmpty()
    val points = sections.points?.trim().orEmpty()
    val hasGoal = goal.isNotEmpty()
    val hasPoints = points.isNotEmpty()
    if (!hasGoal && !hasPoints) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(AppSurface.copy(alpha = 0.42f))
            .border(0.5.dp, Color.White.copy(alpha = 0.48f), RoundedCornerShape(AppRadius.sm))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (hasGoal) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = t("处理目标", "Goal", language),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SectionLabel,
                    letterSpacing = 0.3.sp
                )
                Text(
                    text = goal,
                    fontSize = 14.sp,
                    color = BodyMuted,
                    lineHeight = 20.sp
                )
            }
        }
        if (hasGoal && hasPoints) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp)
                    .height(0.5.dp)
                    .background(Color.Black.copy(alpha = 0.05f))
            )
        }
        if (hasPoints) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = t("处理要点", "Key Points", language),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SectionLabel,
                    letterSpacing = 0.3.sp
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    pointLines(points).forEach { line ->
                        Text(
                            text = line,
                            fontSize = 14.sp,
                            color = BodyMuted,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExamplesBlock(sections: ParsedRuleSections, language: Language) {
    val examples = sections.examples?.trim().orEmpty()
    if (examples.isEmpty()) return

    Column(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(ExampleAmberBg.copy(alpha = 0.08f))
            .border(0.5.dp, ExampleAmberBg.copy(alpha = 0.18f), RoundedCornerShape(AppRadius.sm))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = t("示例", "Examples", language),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = ExampleAmberTitle,
            letterSpacing = 0.3.sp
        )
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            pointLines(examples).forEach { line ->
                Text(
                    text = line,
                    fontSize = 13.sp,
                    color = ExampleAmberBody,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
