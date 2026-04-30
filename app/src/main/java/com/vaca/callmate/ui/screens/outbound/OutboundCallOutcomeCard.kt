package com.vaca.callmate.ui.screens.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.outbound.OutboundCallSummaryPayload
import com.vaca.callmate.ui.theme.AppAccent
import com.vaca.callmate.ui.theme.AppBorder
import com.vaca.callmate.ui.theme.AppError
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppWarning

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

/** plan §5.3 outcome → 强调色 */
fun outboundOutcomeAccent(outcome: String): Color = when (outcome.lowercase()) {
    "success" -> AppSuccess
    "partial" -> AppWarning
    "pending" -> AppPrimary
    "failed", "not_connected" -> AppError.copy(alpha = 0.75f)
    else -> AppTextSecondary
}

private fun outcomeHeadline(payload: OutboundCallSummaryPayload, language: Language): String {
    val o = payload.outcome.lowercase()
    return when (o) {
        "success" -> t("任务完成", "Task completed", language)
        "partial" -> t("部分达成", "Partially achieved", language)
        "failed" -> t("未达成", "Not achieved", language)
        "pending" -> t("待跟进", "Follow-up needed", language)
        "not_connected" -> t("未接通", "Not connected", language)
        else -> payload.outcome
    }
}

@Composable
fun OutboundCallOutcomeCard(
    payload: OutboundCallSummaryPayload,
    language: Language,
    modifier: Modifier = Modifier,
) {
    val accent = outboundOutcomeAccent(payload.outcome)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.1f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = t("外呼结果", "Outbound result", language),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppAccent,
        )
        Text(
            text = outcomeHeadline(payload, language),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        if (payload.title.isNotEmpty()) {
            Text(
                text = payload.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = AppTextPrimary,
            )
        }
        if (payload.result.isNotEmpty()) {
            Text(
                text = payload.result,
                fontSize = 14.sp,
                color = AppTextSecondary,
            )
        }
        if (payload.actionRequired.isNotEmpty()) {
            Text(
                text = t("需机主跟进：", "Action: ", language) + payload.actionRequired,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppPrimary,
            )
        }
        if (payload.keyInfoLines.isNotEmpty()) {
            HorizontalDivider(color = AppBorder.copy(alpha = 0.4f))
            payload.keyInfoLines.forEach { line ->
                Text(text = "• $line", fontSize = 13.sp, color = AppTextSecondary)
            }
        }
        if (payload.summary.isNotEmpty()) {
            HorizontalDivider(color = AppBorder.copy(alpha = 0.4f))
            Text(
                text = payload.summary,
                fontSize = 14.sp,
                color = AppTextPrimary,
            )
        }
    }
}
