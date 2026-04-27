package com.vaca.callmate.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.data.CallRecord
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppBorder
import com.vaca.callmate.ui.theme.AppError
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
import com.vaca.callmate.ui.theme.CallMateTheme
import com.vaca.callmate.ui.preview.PreviewSamples
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private fun labelAccent(label: String): Color {
    return when {
        label.contains("未知") || label.contains("Unknown") -> AppError
        label.contains("快递") || label.contains("Delivery") -> AppWarning
        else -> AppSuccess
    }
}

@Composable
fun AllCallsScreen(
    onBack: () -> Unit,
    onCallClick: (CallRecord) -> Unit,
    language: Language,
    /** Room 真实数据；由上层按 iOS `AllCallsView` 规则过滤后传入 */
    calls: List<CallRecord> = emptyList(),
    /** 与 iOS `AllCallsView` 的 `simulationOnly` 一致，仅展示 `isSimulation == true` 的记录 */
    simulationOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    var search by remember { mutableStateOf("") }
    var dateFilter by remember { mutableStateOf("") }
    var isFilterOpen by remember { mutableStateOf(false) }
    /** 与 iOS `AllCallsView.searchDateTime` 格式一致 */
    val searchDateFormatter = remember(language) {
        val pattern =
            if (language == Language.Zh) "yyyy-MM-dd M月d日 HH:mm" else "yyyy-MM-dd MMM d, HH:mm"
        val locale = if (language == Language.Zh) Locale.CHINA else Locale.US
        SimpleDateFormat(pattern, locale)
    }
    val filteredByMode = remember(calls, simulationOnly) {
        if (simulationOnly) {
            calls.filter { it.isSimulation }
        } else {
            calls.filter { call ->
                !call.isSimulation && !call.isOutbound
            }
        }
    }
    val supportsFilter = !simulationOnly
    val repeatCountByPhone = remember(filteredByMode) {
        filteredByMode.groupingBy { it.phone }.eachCount()
    }
    val filtered = remember(filteredByMode, search, dateFilter, language, searchDateFormatter) {
        filteredByMode.filter { call ->
            val summaryMatch = search.isEmpty() ||
                call.phone.contains(search, ignoreCase = true) ||
                call.label.contains(search, ignoreCase = true) ||
                call.summary.contains(search, ignoreCase = true)
            val dateMatch = if (dateFilter.isEmpty()) {
                true
            } else {
                val target = "${call.time} ${searchDateFormatter.format(Date(call.startedAtMillis))}"
                target.contains(dateFilter, ignoreCase = true)
            }
            summaryMatch && dateMatch
        }
    }
    val filterFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = AppSurface,
        unfocusedContainerColor = AppSurface,
        disabledContainerColor = AppSurface,
        focusedBorderColor = AppBorder,
        unfocusedBorderColor = AppBorder,
        disabledBorderColor = AppBorder,
        cursorColor = AppPrimary,
        focusedTextColor = AppTextPrimary,
        unfocusedTextColor = AppTextPrimary,
        focusedPlaceholderColor = AppTextTertiary,
        unfocusedPlaceholderColor = AppTextTertiary,
        focusedLeadingIconColor = AppTextTertiary,
        unfocusedLeadingIconColor = AppTextTertiary,
        focusedTrailingIconColor = AppTextTertiary,
        unfocusedTrailingIconColor = AppTextTertiary
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackgroundSecondary)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBackIos,
                    contentDescription = null,
                    tint = AppPrimary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = if (simulationOnly) {
                        t("模拟测试通话", "Simulation Calls", language)
                    } else {
                        t("全部通话", "All Calls", language)
                    },
                    fontSize = 17.sp,
                    color = AppTextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (supportsFilter) {
                IconButton(
                    onClick = { isFilterOpen = !isFilterOpen },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.Filled.FilterList,
                        contentDescription = null,
                        tint = if (isFilterOpen) AppPrimary else AppTextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                Box(modifier = Modifier.size(44.dp))
            }
        }

        AnimatedVisibility(
            visible = supportsFilter && isFilterOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppBackgroundSecondary)
                    .padding(AppSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            t("搜索手机号、标签或摘要", "Search phone, label or summary", language),
                            style = AppTypography.bodyLarge
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = AppTextTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            IconButton(onClick = { search = "" }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = null,
                                    tint = AppTextTertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(AppRadius.md),
                    colors = filterFieldColors,
                    singleLine = true
                )
                OutlinedTextField(
                    value = dateFilter,
                    onValueChange = { dateFilter = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            t("日期 (例如: 2月3日)", "Date (e.g., Feb 3)", language),
                            style = AppTypography.bodyLarge
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.CalendarToday,
                            contentDescription = null,
                            tint = AppTextTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (dateFilter.isNotEmpty()) {
                            IconButton(onClick = { dateFilter = "" }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = null,
                                    tint = AppTextTertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(AppRadius.md),
                    colors = filterFieldColors,
                    singleLine = true
                )
            }
        }

        if (filtered.isEmpty()) {
            val hasActiveFilter = search.isNotEmpty() || dateFilter.isNotEmpty()
            val title = when {
                !hasActiveFilter && simulationOnly ->
                    t("暂无模拟测试通话", "No simulation calls yet", language)
                !hasActiveFilter -> t("暂无通话记录", "No calls yet", language)
                else -> t("未找到相关通话记录", "No records found", language)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(AppSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.lg)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PhoneCallback,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = AppTextTertiary.copy(alpha = 0.5f)
                )
                Text(
                    text = title,
                    style = AppTypography.bodyLarge,
                    color = AppTextSecondary
                )
                if (hasActiveFilter) {
                    TextButton(onClick = {
                        search = ""
                        dateFilter = ""
                    }) {
                        Text(
                            text = t("清除筛选", "Clear Filters", language),
                            style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = AppPrimary
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(AppSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                items(filtered, key = { it.roomLogId ?: it.id.toString() }) { call ->
                    AllCallsRowCard(
                        call = call,
                        language = language,
                        repeatCount = repeatCountByPhone[call.phone] ?: 0,
                        onClick = { onCallClick(call) },
                        showsRepeatTag = !simulationOnly,
                    )
                }
            }
        }
    }
}

@Composable
private fun AllCallsRowCard(
    call: CallRecord,
    language: Language,
    repeatCount: Int,
    onClick: () -> Unit,
    /** 与 iOS `CallRowCard.showsRepeatTag`（仅 regular 模式展示重复标记） */
    showsRepeatTag: Boolean = true,
) {
    val accent = labelAccent(call.label)
    val summaryText = call.summary.ifBlank { t("（无摘要）", "(No summary)", language) }
    val aiSummaryText = call.fullSummary.ifBlank { t("通话内容识别失败", "Call content not recognized", language) }
    val displayTag = when {
        call.label.isBlank() -> call.phone
        call.label.contains("未知") || call.label.contains("Unknown") -> call.phone
        else -> call.label
    }
    val repeatTag: String? = if (!showsRepeatTag) {
        null
    } else when {
        repeatCount == 2 -> t("重复", "Repeat", language)
        repeatCount > 2 -> t("多次", "Multiple", language)
        else -> null
    }
    val repeatColor = if (repeatCount > 2) AppError else AppPrimary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(20.dp), spotColor = Color.Black.copy(alpha = 0.04f), ambientColor = Color.Transparent)
            .clip(RoundedCornerShape(20.dp))
            .background(AppSurface)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = summaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFF374151),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = call.time,
                        style = AppTypography.labelSmall,
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
                    text = aiSummaryText,
                    style = AppTypography.labelMedium,
                    color = AppTextSecondary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayTag,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                Text(
                    text = formatCallDuration(call.duration, language),
                    fontSize = 12.sp,
                    color = AppTextTertiary
                )
                repeatTag?.let { tag ->
                    Text(
                        text = tag,
                        fontSize = 12.sp,
                        color = AppTextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppBackgroundSecondary)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun AllCallsScreenPreview() {
    CallMateTheme {
        AllCallsScreen(
            onBack = {},
            onCallClick = {},
            language = Language.Zh,
            calls = listOf(PreviewSamples.callRecord)
        )
    }
}
