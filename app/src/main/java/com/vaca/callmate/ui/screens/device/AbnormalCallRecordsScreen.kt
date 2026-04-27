package com.vaca.callmate.ui.screens.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.data.AbnormalCallRecord
import com.vaca.callmate.data.AbnormalCallRecordStore
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private fun Modifier.abnormalRecordCard(shape: RoundedCornerShape = RoundedCornerShape(20.dp)): Modifier =
    this
        .shadow(6.dp, shape, spotColor = Color.Black.copy(alpha = 0.04f), ambientColor = Color.Transparent)
        .clip(shape)
        .background(AppSurface)
        .border(0.5.dp, Color.White.copy(alpha = 0.55f), shape)

@Composable
fun AbnormalCallRecordsScreen(
    language: Language,
    store: AbnormalCallRecordStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val records by store.records.collectAsState()
    var clearConfirm by remember { mutableStateOf(false) }
    val locale = if (language == Language.Zh) Locale.CHINA else Locale.US
    val df = remember(locale) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale)
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text(t("清空异常通话记录？", "Clear abnormal call records?", language)) },
            text = { Text(t("仅清除本机列表，不影响设备。", "Only clears local list.", language)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.clear()
                        clearConfirm = false
                    }
                ) {
                    Text(t("清空", "Clear", language))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) {
                    Text(t("取消", "Cancel", language))
                }
            }
        )
    }

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
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBackIos,
                    contentDescription = null,
                    tint = com.vaca.callmate.ui.theme.AppPrimary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    t("异常通话记录", "Abnormal Call Records", language),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextPrimary
                )
            }
            Spacer(Modifier.size(44.dp))
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (records.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .abnormalRecordCard()
                        .padding(vertical = 24.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        t("暂无异常通话记录", "No abnormal call records", language),
                        fontSize = 14.sp,
                        color = AppTextSecondary
                    )
                }
            } else {
                Text(
                    t("时间 · 原因", "Time · Reason", language),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextSecondary
                )
                records.forEach { r ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .abnormalRecordCard()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(df.format(Date(r.dateEpochMs)), fontSize = 12.sp, color = AppTextSecondary)
                        Text(
                            reasonText(language, r),
                            fontSize = 14.sp,
                            color = AppTextPrimary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .abnormalRecordCard()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    TextButton(
                        onClick = { clearConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = t("清空记录", "Clear Records", language),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFF3B30)
                        )
                    }
                }
            }
        }
    }
}

private fun reasonText(lang: Language, record: AbnormalCallRecord): String {
    val reason = when (record.reasonCode) {
        "contact_passthrough" -> t("通讯录放行", "Contact passthrough", lang)
        "emergency_blocked" -> t("紧急放行", "Emergency passthrough", lang)
        "standby" -> t("待机模式未代接", "Standby mode, AI skipped", lang)
        "device_id_not_synced" -> t("device-id 未同步", "Device-ID not synced", lang)
        "answer_failed" -> t("应答失败", "Answer failed", lang)
        "network_unavailable" -> t("网络不可用", "Network unavailable", lang)
        "websocket_connect_failed" -> t("WebSocket 连不上", "WebSocket connect failed", lang)
        else -> record.reasonCode
    }
    val d = record.detail
    return if (!d.isNullOrEmpty()) "$reason ($d)" else reason
}
