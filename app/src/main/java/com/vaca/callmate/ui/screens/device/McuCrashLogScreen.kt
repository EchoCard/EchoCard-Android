package com.vaca.callmate.ui.screens.device

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.McuCrashLog
import com.vaca.callmate.core.ble.McuCrashLogState
import com.vaca.callmate.core.ble.clearMcuCrashLog
import com.vaca.callmate.core.ble.requestMcuCrashLog
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppError
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppWarning

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private fun Modifier.crashLogCard(shape: RoundedCornerShape = RoundedCornerShape(20.dp)): Modifier =
    this
        .shadow(6.dp, shape, spotColor = Color.Black.copy(alpha = 0.04f), ambientColor = Color.Transparent)
        .clip(shape)
        .background(AppSurface)
        .border(0.5.dp, Color.White.copy(alpha = 0.55f), shape)

@Composable
fun McuCrashLogScreen(
    language: Language,
    bleManager: BleManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by bleManager.mcuCrashLogState.collectAsState()
    val context = LocalContext.current
    var clearConfirm by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        bleManager.requestMcuCrashLog()
    }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1800)
            copied = false
        }
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text(t("确认清除崩溃日志？", "Clear crash log?", language)) },
            text = { Text(t("清除后无法恢复。", "This action cannot be undone.", language)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        bleManager.clearMcuCrashLog()
                        clearConfirm = false
                    }
                ) {
                    Text(t("清除", "Clear", language))
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
                    tint = AppPrimary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    t("MCU 崩溃日志", "MCU Crash Log", language),
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
            SectionTitle(t("状态", "Status", language))
            StatusBlock(language, state)

            when (val st = state) {
                is McuCrashLogState.Found -> {
                    val log = st.log
                    SectionTitle(t("概览", "Overview", language))
                    DiagRowPlain(t("崩溃类型", "Crash Type", language), crashTypeName(log), highlight = true)
                    DiagRowPlain(t("崩溃时运行时长", "Uptime at Crash", language), uptimeFormatted(log))
                    DiagRowPlain(t("崩溃线程", "Thread", language), log.thread)

                    if (log.pc != 0L || log.lr != 0L) {
                        SectionTitle(t("寄存器", "Registers", language))
                        DiagRowPlain("PC", hex(log.pc))
                        DiagRowPlain("LR", hex(log.lr))
                    }
                    if (log.cfsr != 0L || log.hfsr != 0L) {
                        SectionTitle("Fault Status")
                        DiagRowPlain("CFSR", hex(log.cfsr))
                        DiagRowPlain("HFSR", hex(log.hfsr))
                        DiagRowPlain(t("原因", "Reason", language), cfsrDescription(language, log.cfsr))
                    }

                    SectionTitle(t("详情", "Detail", language))
                    Text(
                        log.detail,
                        fontSize = 14.sp,
                        color = AppTextSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .crashLogCard()
                            .padding(16.dp)
                    )

                    if (log.backtrace.isNotEmpty()) {
                        SectionTitle(t("调用链（启发式）", "Backtrace (heuristic)", language))
                        log.backtrace.forEachIndexed { i, addr ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("#$i", fontSize = 12.sp, color = AppTextSecondary, fontFamily = FontFamily.Monospace)
                                Text(hex(addr), fontSize = 12.sp, color = AppTextPrimary, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Text(
                            t("使用 addr2line -e fw.elf <地址> 解码", "Decode with: addr2line -e fw.elf <addr>", language),
                            fontSize = 11.sp,
                            color = AppTextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    SectionTitle(t("操作", "Actions", language))
                    TextButtonRow(
                        label = if (copied) t("已复制", "Copied!", language) else t("复制到剪贴板", "Copy to Clipboard", language),
                        color = if (copied) AppSuccess else AppPrimary,
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("MCU crash", plainText(log)))
                            copied = true
                        }
                    )
                    TextButtonRow(
                        label = t("刷新", "Refresh", language),
                        color = AppPrimary,
                        icon = { Icon(Icons.Default.Refresh, null, tint = AppPrimary, modifier = Modifier.size(20.dp)) },
                        onClick = { bleManager.requestMcuCrashLog() }
                    )
                    TextButtonRow(
                        label = t("清除崩溃日志", "Clear Crash Log", language),
                        color = AppError,
                        icon = { Icon(Icons.Default.Delete, null, tint = AppError, modifier = Modifier.size(20.dp)) },
                        onClick = { clearConfirm = true }
                    )
                }
                else -> {
                    SectionTitle(t("操作", "Actions", language))
                    TextButtonRow(
                        label = t("刷新", "Refresh", language),
                        color = AppPrimary,
                        icon = { Icon(Icons.Default.Refresh, null, tint = AppPrimary, modifier = Modifier.size(20.dp)) },
                        onClick = { bleManager.requestMcuCrashLog() }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(language: Language, state: McuCrashLogState) {
    when (val s = state) {
        McuCrashLogState.Idle -> {
            Text(
                t("点击刷新以查询", "Tap Refresh to query", language),
                fontSize = 14.sp,
                color = AppTextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .crashLogCard()
                    .padding(16.dp)
            )
        }
        McuCrashLogState.Loading -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .crashLogCard()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AppPrimary)
                Text(
                    t("查询中…", "Querying…", language),
                    fontSize = 14.sp,
                    color = AppTextSecondary,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }
        is McuCrashLogState.Found -> {
            Text(
                t("发现崩溃记录", "Crash record found", language),
                fontSize = 14.sp,
                color = AppWarning,
                modifier = Modifier
                    .fillMaxWidth()
                    .crashLogCard()
                    .padding(16.dp)
            )
        }
        McuCrashLogState.NotFound -> {
            Text(
                t("无崩溃记录（运行正常）", "No crash record (healthy)", language),
                fontSize = 14.sp,
                color = AppSuccess,
                modifier = Modifier
                    .fillMaxWidth()
                    .crashLogCard()
                    .padding(16.dp)
            )
        }
        is McuCrashLogState.Error -> {
            Text(
                s.message,
                fontSize = 14.sp,
                color = AppError,
                modifier = Modifier
                    .fillMaxWidth()
                    .crashLogCard()
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun TextButtonRow(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .crashLogCard(RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        TextButton(onClick = onClick) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                icon?.invoke()
                Text(label, color = color, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppTextSecondary,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp)
    )
}

@Composable
private fun DiagRowPlain(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .crashLogCard()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = AppTextPrimary)
        Text(
            value,
            fontSize = 14.sp,
            color = if (highlight) AppWarning else AppTextSecondary,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun crashTypeName(log: McuCrashLog): String {
    return when (log.crashType) {
        1 -> "SWDT Miss"
        2 -> "HardFault"
        3 -> "HW WDT IRQ"
        else -> "Unknown (${log.crashType})"
    }
}

private fun uptimeFormatted(log: McuCrashLog): String {
    val s = log.uptimeMs / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%dh %dm %ds", h, m, sec)
    else if (m > 0) String.format("%dm %ds", m, sec)
    else String.format("%ds", sec)
}

private fun hex(v: Long): String = String.format("0x%08X", v)

private fun cfsrDescription(lang: Language, cfsr: Long): String {
    val u = cfsr.toUInt()
    if ((u and 0xFFFF0000u) != 0u) return "UsageFault"
    if ((u and 0x0000FF00u) != 0u) return "BusFault"
    if ((u and 0x000000FFu) != 0u) return "MemManageFault"
    return t("未知", "Unknown", lang)
}

private fun plainText(log: McuCrashLog): String {
    val lines = ArrayList<String>()
    lines += "===== MCU Crash Log ====="
    lines += "Type   : ${crashTypeName(log)}"
    lines += "Uptime : ${uptimeFormatted(log)} (${log.uptimeMs} ms)"
    lines += "Thread : ${log.thread}"
    if (log.pc != 0L || log.lr != 0L) {
        lines += "PC     : ${hex(log.pc)}"
        lines += "LR     : ${hex(log.lr)}"
    }
    if (log.cfsr != 0L || log.hfsr != 0L) {
        lines += "CFSR   : ${hex(log.cfsr)}"
        lines += "HFSR   : ${hex(log.hfsr)}"
    }
    lines += "Detail : ${log.detail}"
    if (log.backtrace.isNotEmpty()) {
        lines += "Backtrace:"
        log.backtrace.forEachIndexed { i, addr ->
            lines += "  #${i}  ${hex(addr)}"
        }
        lines += "  (decode: arm-none-eabi-addr2line -e fw.elf <addr>)"
    }
    return lines.joinToString("\n")
}
