package com.vaca.callmate.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlinx.coroutines.launch
import com.vaca.callmate.data.CallRecord
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppBorder
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppRadius
import com.vaca.callmate.ui.theme.AppSpacing
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppTextTertiary
import com.vaca.callmate.ui.theme.AppTypography

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

internal enum class DashboardCallListFilter {
    All,
    Important
}

internal fun formatTokenCount(count: Int): String {
    if (count >= 100_000_000) return String.format("%.1fy", count / 100_000_000.0)
    if (count >= 10_000) return String.format("%.1fw", count / 10_000.0)
    if (count >= 1_000) return String.format("%.1fk", count / 1_000.0)
    return "$count"
}

internal fun formatCallDuration(seconds: Int, language: Language): String {
    if (seconds < 60) {
        return if (language == Language.Zh) "${seconds}秒" else "${seconds}s"
    }
    val m = seconds / 60
    val s = seconds % 60
    return if (language == Language.Zh) {
        if (s > 0) "${m}分${s}秒" else "${m}分"
    } else {
        if (s > 0) "${m}m ${s}s" else "${m}m"
    }
}

private enum class CallerCategory {
    PersonalContact,
    Courier,
    Rider,
    Carrier,
    Bank,
    Marketing,
    Uncategorized
}

private fun callerCategory(label: String): CallerCategory {
    if (label.isEmpty()) return CallerCategory.Uncategorized
    val lowered = label.lowercase()
    if (lowered.contains("未知") || lowered.contains("unknown") || lowered.contains("陌生号码") || lowered.contains("未识别")) {
        return CallerCategory.Uncategorized
    }
    val riderKeywords = listOf("外卖", "骑手", "美团", "饿了么")
    if (riderKeywords.any { lowered.contains(it) }) return CallerCategory.Rider
    val courierKeywords = listOf(
        "快递", "驿站", "派件", "顺丰", "圆通", "中通", "韵达", "申通",
        "极兔", "菜鸟", "courier", "express", "delivery"
    )
    if (courierKeywords.any { lowered.contains(it) }) return CallerCategory.Courier
    val carrierKeywords = listOf(
        "移动", "联通", "电信", "10086", "10010", "10000",
        "china mobile", "china unicom", "china telecom"
    )
    if (carrierKeywords.any { lowered.contains(it) }) return CallerCategory.Carrier
    val bankKeywords = listOf(
        "银行", "保险", "贷款", "理财", "信用卡", "催收",
        "bank", "insurance", "loan", "finance"
    )
    if (bankKeywords.any { lowered.contains(it) }) return CallerCategory.Bank
    val marketingKeywords = listOf("推广", "推销", "广告", "营销", "marketing")
    if (marketingKeywords.any { lowered.contains(it) }) return CallerCategory.Marketing
    val digitsOnly = label.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }
    if (digitsOnly) return CallerCategory.Uncategorized
    return CallerCategory.PersonalContact
}

private fun categoryColor(cat: CallerCategory): Color {
    return when (cat) {
        CallerCategory.PersonalContact -> Color(0xFF007AFF)
        CallerCategory.Courier -> Color(0xFF34C759)
        CallerCategory.Rider -> Color(0xFFFF9500)
        CallerCategory.Carrier -> Color(0xFF5856D6)
        CallerCategory.Bank -> Color(0xFF5AC8FA)
        CallerCategory.Marketing -> Color(0xFFA2845E)
        CallerCategory.Uncategorized -> Color(0xFF8E8E93)
    }
}

@Composable
internal fun DashboardCallStatsBar(
    language: Language,
    totalInboundCount: Int,
    importantCount: Int,
    totalTokenCount: Int,
    filter: DashboardCallListFilter,
    onFilterChange: (DashboardCallListFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, cardShape, spotColor = Color.Black.copy(alpha = 0.04f), ambientColor = Color.Transparent)
            .clip(cardShape)
            .background(AppSurface)
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(AppRadius.sm))
                .clickable { onFilterChange(DashboardCallListFilter.All) }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$totalInboundCount",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (filter == DashboardCallListFilter.All) AppPrimary else AppTextPrimary
                )
                Text(
                    text = t("累计通话数", "Total Calls", language),
                    style = AppTypography.labelMedium,
                    color = if (filter == DashboardCallListFilter.All) AppPrimary else AppTextSecondary
                )
            }
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(48.dp)
                .padding(vertical = 4.dp)
                .background(Color(0xFFE5E5EA))
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(AppRadius.sm))
                .clickable { onFilterChange(DashboardCallListFilter.Important) }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$importantCount",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (filter == DashboardCallListFilter.Important) AppPrimary else AppTextPrimary
                )
                Text(
                    text = t("重要来电数", "Important", language),
                    style = AppTypography.labelMedium,
                    color = if (filter == DashboardCallListFilter.Important) AppPrimary else AppTextSecondary
                )
            }
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(48.dp)
                .padding(vertical = 4.dp)
                .background(Color(0xFFE5E5EA))
        )
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = formatTokenCount(totalTokenCount),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = AppTextSecondary
            )
            Text(
                text = t("累计token数", "Total Tokens", language),
                style = AppTypography.labelMedium,
                color = AppTextSecondary
            )
        }
    }
}

@Composable
internal fun DashboardEmptyCalls(
    language: Language,
    filter: DashboardCallListFilter,
    onSimulationTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, cardShape, spotColor = Color.Black.copy(alpha = 0.04f), ambientColor = Color.Transparent)
            .clip(cardShape)
            .background(AppSurface)
            .padding(horizontal = AppSpacing.xl)
            .padding(vertical = AppSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Call,
            contentDescription = null,
            tint = AppTextTertiary,
            modifier = Modifier
                .padding(top = AppSpacing.xxl)
                .size(48.dp)
        )
        Spacer(modifier = Modifier.height(AppSpacing.lg))
        Text(
            text = t("暂无通话记录", "No Calls Yet", language),
            style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = AppTextPrimary
        )
        Text(
            text = if (filter == DashboardCallListFilter.Important) {
                t(
                    "AI分身会提醒你本人接听重要电话",
                    "Your AI avatar will remind you to take over important calls",
                    language
                )
            } else {
                t(
                    "AI 会自动接听并记录所有来电",
                    "AI will answer and log all calls",
                    language
                )
            },
            style = AppTypography.bodyLarge,
            color = AppTextSecondary,
            modifier = Modifier.padding(top = AppSpacing.xs)
        )
        Spacer(modifier = Modifier.height(AppSpacing.lg))
        Text(
            text = t("模拟通话测试", "Try Simulation", language),
            style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = AppPrimary,
            modifier = Modifier
                .clickable(onClick = onSimulationTest)
                .padding(bottom = AppSpacing.xxl)
        )
    }
}

private val DashboardSwipeRevealWidth = 80.dp

@Composable
internal fun DashboardCallSwipeRow(
    call: CallRecord,
    language: Language,
    repeatCallCount: Int,
    isUnread: Boolean,
    onClick: () -> Unit,
    /** 用户点击红色删除区（与 iOS 一致：滑出后点删除，再弹确认框） */
    onDeleteRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current
    val maxRevealPx = with(density) { DashboardSwipeRevealWidth.toPx() }
    /** 拖动时用 float 直接更新，不走协程，保证每个拖动事件都即时生效 */
    var offsetPx by remember { mutableFloatStateOf(0f) }
    /** 复用同一个 Animatable，避免每次弹簧动画都分配新对象 */
    val animatable = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val closeThresholdPx = with(density) { 4.dp.toPx() }

    fun animateOffsetTo(target: Float) {
        scope.launch {
            animatable.snapTo(offsetPx)
            animatable.animateTo(target, spring(dampingRatio = 0.82f, stiffness = 380f))
            offsetPx = animatable.value
        }
    }

    fun snapAfterDragEnd() {
        val target = if (abs(offsetPx) > maxRevealPx / 2f) -maxRevealPx else 0f
        animateOffsetTo(target)
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(DashboardSwipeRevealWidth)
                    .fillMaxHeight()
                    .zIndex(0f)
                    // 收起时 alpha=0，避免圆角抗锯齿/叠层在卡片边缘露出红边（未滑动时不应看到红）
                    .graphicsLayer {
                        alpha = (-offsetPx / maxRevealPx).coerceIn(0f, 1f)
                    }
                    .clip(
                        RoundedCornerShape(
                            topEnd = AppRadius.md,
                            bottomEnd = AppRadius.md
                        )
                    )
                    .background(Color(0xFFFF3B30))
                    .clickable {
                        onDeleteRequest()
                        animateOffsetTo(0f)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = t("删除", "Delete", language),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        DashboardCallRow(
            call = call,
            language = language,
            repeatCallCount = repeatCallCount,
            isUnread = isUnread,
            onClick = {},
            modifier = Modifier
                .zIndex(1f)
                .offset {
                    IntOffset(offsetPx.roundToInt(), 0)
                }
                .clickable {
                    if (abs(offsetPx) > closeThresholdPx) {
                        animateOffsetTo(0f)
                    } else {
                        onClick()
                    }
                }
                /**
                 * 自定义方向判断手势：先累积位移确认是横滑还是纵滑后再消费事件。
                 * 相比 detectHorizontalDragGestures，纵滑时立即放行给 LazyColumn，
                 * 彻底消除"High input latency"导致的列表滚动卡顿。
                 */
                .pointerInput(maxRevealPx) {
                    val touchSlop = viewConfig.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalX = 0f
                        var totalY = 0f
                        var decided = false
                        var isHoriz = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (decided && isHoriz) snapAfterDragEnd()
                                break
                            }
                            val dx = change.positionChange().x
                            val dy = change.positionChange().y

                            if (!decided) {
                                totalX += dx
                                totalY += dy
                                if (abs(totalX) > touchSlop || abs(totalY) > touchSlop) {
                                    isHoriz = abs(totalX) > abs(totalY)
                                    decided = true
                                    if (isHoriz) {
                                        change.consume()
                                        offsetPx = (offsetPx + totalX).coerceIn(-maxRevealPx, 0f)
                                    } else {
                                        // 纵向意图：立即放弃，让 LazyColumn 接管
                                        break
                                    }
                                }
                            } else {
                                change.consume()
                                offsetPx = (offsetPx + dx).coerceIn(-maxRevealPx, 0f)
                            }
                        }
                    }
                },
            disableRowClick = true
        )
    }
}

@Composable
internal fun DashboardCallRow(
    call: CallRecord,
    language: Language,
    repeatCallCount: Int,
    isUnread: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    disableRowClick: Boolean = false
) {
    val summaryLine = remember(call.summary, language) { summaryHeadlineForDashboard(call, language) }
    val aiLine = remember(call.fullSummary, language) { aiLineOrFallbackForDashboard(call, language) }
    val cat = remember(call.label) { callerCategory(call.label) }
    val color = remember(cat) { categoryColor(cat) }
    val displayTag = if (cat == CallerCategory.Uncategorized) call.phone else call.label
    val strangerRepeatTag = when {
        repeatCallCount == 2 -> t("重复", "Repeat", language)
        repeatCallCount > 2 -> t("多次", "Multiple", language)
        else -> null
    }

    val cardShape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            // graphicsLayer 硬件加速阴影，避免 shadow() 每帧触发软件绘制指令（Slow issue draw commands）
            .graphicsLayer {
                shadowElevation = 6.dp.toPx()
                shape = cardShape
                clip = true
                ambientShadowColor = Color.Transparent
                spotShadowColor = Color.Black.copy(alpha = 0.04f)
            }
            .background(AppSurface)
            .then(if (!disableRowClick) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = summaryLine,
                    fontSize = 16.sp,
                    fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isUnread) AppTextPrimary else Color(0xFF374151),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = call.time,
                        fontSize = 13.sp,
                        color = AppTextTertiary
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = AppTextTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = AppTextTertiary.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = aiLine,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = if (isUnread) AppTextSecondary else Color(0xFF6B7280),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = displayTag,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 140.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                Text(
                    text = formatCallDuration(call.duration, language),
                    fontSize = 12.sp,
                    color = AppTextTertiary
                )
                strangerRepeatTag?.let { tag ->
                    Text(
                        text = tag,
                        fontSize = 12.sp,
                        color = AppTextSecondary,
                        modifier = Modifier
                            .widthIn(max = 72.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppBackgroundSecondary)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
