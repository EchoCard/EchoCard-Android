package com.vaca.callmate.ui.screens.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhoneForwarded
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.data.Language
import com.vaca.callmate.features.outbound.OutboundConfirmationData
import com.vaca.callmate.features.outbound.ProposalCardStatus

/**
 * 对标 iOS `InlineOutboundConfirmationCard`（`CallMate/Features/Settings/FeedbackChatModalView.swift:2330-2558`）：
 * 聊天流内嵌的外呼确认卡，三段式信息 + 状态 badge + 渐变确认按钮。
 *
 * 设计要点：
 * - 立即外呼：绿色 icon + 绿色渐变确认按钮
 * - 定时外呼：橙色 icon + 橙色渐变 + 多一行「拨打时间」
 * - 非 pending 状态隐藏操作按钮，改为 status badge（已确认/已取消/处理失败/已超时）
 * - 失败时在 badge 下方再显示一行红色失败原因文案
 * - 整卡最大宽度由父容器 `Modifier.fillMaxWidth()` + `widthIn` 控制，本身只撑高
 */
@Composable
fun InlineOutboundConfirmationCard(
    data: OutboundConfirmationData,
    status: ProposalCardStatus,
    failureMessage: String?,
    language: Language,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isScheduled = data.scheduledAtMillis != null
    val accent = if (isScheduled) Color(0xFFFF9500) else Color(0xFF34C759)
    val accentSecondary = if (isScheduled) Color(0xFFFFB340) else Color(0xFF30D158)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF6F7FB))
            .border(0.6.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(18.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header: icon + phone + contact name
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isScheduled) Icons.Filled.Schedule else Icons.Filled.PhoneForwarded,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = data.phone,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF111111),
                    )
                    Text(
                        text = (data.contactName?.takeIf { it.isNotBlank() } ?: data.templateName),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF6E6E73),
                    )
                }
            }

            // Sections card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.9f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConfirmationSection(title = t("本次电话目标", "Call Goal", language)) {
                    Text(
                        text = data.goal?.takeIf { it.isNotBlank() } ?: data.templateName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1C1C1E),
                    )
                }
                HorizontalDivider(color = Color(0xFFE5E5EA))
                ConfirmationSection(title = t("处理要点", "Key Points", language)) {
                    val lines = splitKeyPointLines(data.keyPoints)
                    if (lines.isEmpty()) {
                        Text(
                            text = data.templateName,
                            fontSize = 15.sp,
                            color = Color(0xFF3A3A3C),
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (line in lines) {
                                Text(
                                    text = line,
                                    fontSize = 15.sp,
                                    color = Color(0xFF3A3A3C),
                                )
                            }
                        }
                    }
                }
                val scheduledText = scheduledTimeLabel(data, language)
                if (scheduledText != null) {
                    HorizontalDivider(color = Color(0xFFE5E5EA))
                    ConfirmationSection(title = t("拨打时间", "Time", language)) {
                        Text(
                            text = scheduledText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1C1C1E),
                        )
                    }
                }
            }

            val statusText = statusLabel(status, isScheduled, language)
            if (statusText != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = if (status == ProposalCardStatus.Applied) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                        contentDescription = null,
                        tint = if (status == ProposalCardStatus.Applied) Color(0xFF34C759) else Color(0xFF8E8E93),
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = statusText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF6E6E73),
                    )
                }
            }

            if (status == ProposalCardStatus.Failed && !failureMessage.isNullOrEmpty()) {
                Text(
                    text = failureMessage,
                    fontSize = 13.sp,
                    color = Color(0xFFC62828),
                )
            }

            if (status == ProposalCardStatus.Pending) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Cancel
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.9f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .clickable(onClick = onCancel),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = t("取消", "Cancel", language),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF3A3A3C),
                        )
                    }
                    // Confirm
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(accent, accentSecondary),
                                ),
                            )
                            .clickable(onClick = onConfirm),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (isScheduled) t("确认定时", "Confirm", language) else t("确认拨打", "Call", language),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmationSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF8E8E93),
        )
        content()
    }
}

/**
 * 与 iOS `InlineOutboundConfirmationCard.keyPointLines` 对齐：按换行 + 全角分号切分，去掉前导符号/编号。
 */
private fun splitKeyPointLines(raw: String?): List<String> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    val prefixChars = setOf('-', '•', '.', '、', ' ') + ('0'..'9').toSet()
    return text
        .split('\n')
        .map { it.trim() }
        .flatMap { line -> if (line.contains('；')) line.split('；').map { it.trim() } else listOf(line) }
        .map { it.trimStart { c -> c in prefixChars } }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

private fun scheduledTimeLabel(data: OutboundConfirmationData, language: Language): String? {
    val millis = data.scheduledAtMillis ?: return null
    val desc = data.timeDescription?.trim()
    if (!desc.isNullOrEmpty()) return desc
    return formatAbsoluteDateTime(millis, language)
}

private fun statusLabel(
    status: ProposalCardStatus,
    isScheduled: Boolean,
    language: Language,
): String? = when (status) {
    ProposalCardStatus.Applied -> if (isScheduled) t("已安排", "Scheduled", language) else t("已确认", "Confirmed", language)
    ProposalCardStatus.Cancelled -> t("已取消", "Cancelled", language)
    ProposalCardStatus.Expired -> t("已超时", "Expired", language)
    ProposalCardStatus.Failed -> t("处理失败", "Failed", language)
    ProposalCardStatus.Pending -> null
}

private fun t(zh: String, en: String, lang: Language): String = if (lang == Language.Zh) zh else en
