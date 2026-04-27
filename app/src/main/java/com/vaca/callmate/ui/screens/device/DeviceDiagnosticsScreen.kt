package com.vaca.callmate.ui.screens.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.CallMateBleEvent
import com.vaca.callmate.core.ble.requestDeviceDiagnostics
import com.vaca.callmate.core.ble.setRandomMacAddress
import com.vaca.callmate.data.AbnormalCallRecordStore
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppBackgroundTertiary
import com.vaca.callmate.ui.theme.AppError
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppPrimaryLight
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private fun Modifier.deviceDiagCard(shape: RoundedCornerShape = RoundedCornerShape(20.dp)): Modifier =
    this
        .shadow(
            6.dp,
            shape,
            spotColor = Color.Black.copy(alpha = 0.04f),
            ambientColor = Color.Transparent
        )
        .clip(shape)
        .background(AppSurface)
        .border(0.5.dp, Color.White.copy(alpha = 0.55f), shape)

private enum class DeviceDiagnosticsSubPage {
    Main, Latency, Abnormal, CrashLog, Registers
}

@Composable
fun DeviceDiagnosticsScreen(
    language: Language,
    bleManager: BleManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val abnormalStore = remember { AbnormalCallRecordStore.getInstance(context) }
    var subPage by remember { mutableStateOf(DeviceDiagnosticsSubPage.Main) }

    when (subPage) {
        DeviceDiagnosticsSubPage.Main ->
            DeviceDiagnosticsMainContent(
                language = language,
                bleManager = bleManager,
                onBack = onBack,
                onOpenLatency = { subPage = DeviceDiagnosticsSubPage.Latency },
                onOpenAbnormal = { subPage = DeviceDiagnosticsSubPage.Abnormal },
                onOpenCrashLog = { subPage = DeviceDiagnosticsSubPage.CrashLog },
                onOpenRegisters = { subPage = DeviceDiagnosticsSubPage.Registers },
                modifier = modifier
            )
        DeviceDiagnosticsSubPage.Latency ->
            LatencyTestScreen(language, bleManager, onBack = { subPage = DeviceDiagnosticsSubPage.Main }, modifier)
        DeviceDiagnosticsSubPage.Abnormal ->
            AbnormalCallRecordsScreen(language, abnormalStore, onBack = { subPage = DeviceDiagnosticsSubPage.Main }, modifier)
        DeviceDiagnosticsSubPage.CrashLog ->
            McuCrashLogScreen(language, bleManager, onBack = { subPage = DeviceDiagnosticsSubPage.Main }, modifier)
        DeviceDiagnosticsSubPage.Registers ->
            McuRegistersScreen(language, bleManager, onBack = { subPage = DeviceDiagnosticsSubPage.Main }, modifier)
    }
}

@Composable
private fun DeviceDiagnosticsMainContent(
    language: Language,
    bleManager: BleManager,
    onBack: () -> Unit,
    onOpenLatency: () -> Unit,
    onOpenAbnormal: () -> Unit,
    onOpenCrashLog: () -> Unit,
    onOpenRegisters: () -> Unit,
    modifier: Modifier = Modifier
) {
    val diag by bleManager.deviceDiagnostics.collectAsState()
    val runtimeId by bleManager.runtimeMCUDeviceID.collectAsState()
    val chip by bleManager.deviceChipName.collectAsState()
    val isReady by bleManager.isReady.collectAsState()
    val isAudioReady by bleManager.isAudioReady.collectAsState()
    val speedTestEnabled by bleManager.speedTest.speedTestEnabled.collectAsState()
    val downKBps by bleManager.speedTest.speedTestDownlinkKBps.collectAsState()
    val downKbps by bleManager.speedTest.speedTestDownlinkKbps.collectAsState()
    val downPps by bleManager.speedTest.speedTestDownlinkPacketsPerSec.collectAsState()
    val upKBps by bleManager.speedTest.speedTestUplinkKBps.collectAsState()
    val upKbps by bleManager.speedTest.speedTestUplinkKbps.collectAsState()
    val upPps by bleManager.speedTest.speedTestUplinkPacketsPerSec.collectAsState()

    var autoPoll by remember { mutableStateOf(false) }
    var lastSetMac by remember { mutableStateOf<String?>(null) }
    var isMacWriting by remember { mutableStateOf(false) }
    var pmToastMessage by remember { mutableStateOf<String?>(null) }
    var pmToastSuccess by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        bleManager.requestDeviceDiagnostics()
    }

    LaunchedEffect(autoPoll) {
        while (autoPoll) {
            bleManager.requestDeviceDiagnostics()
            delay(2000)
        }
    }

    LaunchedEffect(Unit) {
        bleManager.bleEvents.collect { evt ->
            if (evt !is CallMateBleEvent.Ack) return@collect
            val cmd = evt.cmd
            if (cmd !in setOf(
                    "pm_io_down", "pm_io_up", "pm_deepsleep", "pm_release_idle",
                    "power_test_idle", "power_off_rf_lcpu_halt",
                    "cpu_freq", "ble_adv_interval", "ble_conn_interval", "ble_tx_power"
                )
            ) return@collect
            if (evt.result == 0) {
                pmToastMessage = t("发送成功", "Sent successfully", language)
                pmToastSuccess = true
                if (cmd in setOf("cpu_freq", "ble_adv_interval", "ble_conn_interval", "ble_tx_power")) {
                    bleManager.requestDeviceDiagnostics()
                }
            } else {
                pmToastMessage = t("发送失败 (code: ${evt.result})", "Failed (code: ${evt.result})", language)
                pmToastSuccess = false
            }
            launch {
                delay(3000)
                pmToastMessage = null
            }
        }
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
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = t("设备诊断与测速", "Device Diagnostics & Speed Test", language),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextPrimary
                )
            }
            Spacer(modifier = Modifier.size(44.dp))
        }

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            pmToastMessage?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (pmToastSuccess) Color(0x1434C759) else Color(0x14FF3B30),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (pmToastSuccess) Color(0xFF34C759) else Color(0xFFFF3B30))
                    )
                    Text(
                        text = msg,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (pmToastSuccess) Color(0xFF34C759) else Color(0xFFFF3B30)
                    )
                }
            }

            SectionHeader(t("测试与调试", "Test & Debug", language))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .deviceDiagCard()
                    .padding(vertical = 4.dp)
            ) {
                DiagnosticsNavRow(
                    accent = Color(0xFFFF9500),
                    title = t("延迟测试", "Latency Test", language),
                    subtitle = t("HFP 环回测整链延迟", "HFP round-trip latency", language),
                    onClick = onOpenLatency
                )
                CardDivider()
                DiagnosticsToggleRow(
                    accent = Color(0xFF5856D6),
                    title = t("自动轮询", "Auto Polling", language),
                    subtitle = t("每 2 秒读取一次诊断数据", "Read diagnostics every 2 seconds", language),
                    checked = autoPoll,
                    onCheckedChange = { autoPoll = it }
                )
                CardDivider()
                DiagnosticsNavRow(
                    accent = Color(0xFFFF3B30),
                    title = t("异常通话记录", "Abnormal Call Records", language),
                    subtitle = t("未代接原因与时间列表", "Time and reason list", language),
                    onClick = onOpenAbnormal
                )
            }

            SectionHeader(t("系统信息", "System Info", language))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .deviceDiagCard()
                    .padding(vertical = 2.dp)
            ) {
                DiagnosticsInfoRow(t("芯片型号", "Chip", language), chip ?: "--")
                CardDivider(16.dp)
                DiagnosticsInfoRow(
                    "MCU Device-ID",
                    runtimeId?.trim()?.takeIf { it.isNotEmpty() } ?: t("未同步", "Not Synced", language)
                )
                CardDivider(16.dp)
                DiagnosticsInfoRow(t("CPU 使用率", "CPU Usage", language), diag?.cpuUsage?.let { String.format("%.2f%%", it) } ?: "--")
                CardDivider(16.dp)
                DiagnosticsInfoRow(t("CPU 频率", "CPU Freq", language), cpuFreqText(diag?.cpuFreqMhz))
                CardDivider(16.dp)
                DiagnosticsInfoRow(t("运行时长", "Uptime", language), uptimeText(language, diag?.uptimeMs))
                CardDivider(16.dp)
                DiagnosticsInfoRow(t("当前 Slot", "Active Slot", language), diag?.activeSlotName ?: "--")
                CardDivider(16.dp)
                DiagnosticsInfoRow(
                    t("OTA 状态", "OTA State", language),
                    when (diag?.otaState) {
                        1 -> "PENDING"
                        0 -> "IDLE"
                        else -> "--"
                    }
                )
                CardDivider(16.dp)
                DiagnosticsInfoRow(t("Deep Sleep", "Deep Sleep", language), deepSleepText(language, diag?.deepSleepAllowed))
            }

            SectionHeader(t("CPU 频率", "CPU Frequency", language))
            Text(
                t("选择 HCPU 频率（仅 SF32LB52X）；设置后立即生效。", "Select HCPU frequency (SF32LB52X only); takes effect immediately.", language),
                fontSize = 12.sp,
                color = AppTextSecondary
            )
            FreqChipRow(
                items = listOf(24, 48, 144, 240),
                selected = diag?.cpuFreqMhz,
                enabled = isReady,
                formatLabel = { "$it" },
                onSelect = { mhz ->
                    pmToastMessage = if (language == Language.Zh) "已发送 ${mhz} MHz…" else "Sending ${mhz} MHz…"
                    pmToastSuccess = true
                    bleManager.sendCommand("cpu_freq", JSONObject().put("mhz", mhz), expectAck = true)
                }
            )
            Text(
                t("24/48 MHz 低功耗；144/240 MHz 高性能。", "24/48 MHz low power; 144/240 MHz high performance.", language),
                fontSize = 12.sp,
                color = AppTextSecondary
            )

            SectionHeader(t("BLE 间隔", "BLE Intervals", language))
            DiagRow(t("广播间隔", "Advertising Interval", language), bleAdvIntervalText(diag?.bleAdvIntervalMs))
            DiagRow(t("连接间隔", "Connection Interval", language), bleConnIntervalText(diag?.bleConnIntervalUnits))
            DiagRow(t("TX Power", "TX Power", language), bleTxPowerText(diag?.bleTxPowerDbm))

            Text(
                t("广播间隔（毫秒）；设置后可能短暂断连。", "Advertising interval (ms); may briefly disconnect when set.", language),
                fontSize = 12.sp,
                color = AppTextSecondary
            )
            FreqChipRow(
                items = listOf(20, 50, 100, 200, 500),
                selected = diag?.bleAdvIntervalMs,
                enabled = isReady,
                formatLabel = { "$it" },
                onSelect = { ms ->
                    pmToastMessage = if (language == Language.Zh) "已发送 ${ms} ms…" else "Sending ${ms} ms…"
                    pmToastSuccess = true
                    bleManager.sendCommand("ble_adv_interval", JSONObject().put("ms", ms), expectAck = true)
                }
            )

            Text(
                t("连接间隔（毫秒）；仅连接后生效。", "Connection interval (ms); takes effect when connected.", language),
                fontSize = 12.sp,
                color = AppTextSecondary
            )
            FreqChipRow(
                items = listOf(6, 12, 24, 60, 96),
                selected = diag?.bleConnIntervalUnits,
                enabled = isReady,
                formatLabel = { units ->
                    val ms = (units * 125) / 100
                    if (ms == 7) "7.5" else "$ms"
                },
                onSelect = { units ->
                    val ms = (units * 125) / 100
                    pmToastMessage = if (language == Language.Zh) "已发送 ${ms} ms…" else "Sending ${ms} ms…"
                    pmToastSuccess = true
                    bleManager.sendCommand("ble_conn_interval", JSONObject().put("units", units), expectAck = true)
                }
            )

            Text(
                t("发射功率（dBm）；仅 SF32LB52x 支持。", "TX power (dBm); SF32LB52x only.", language),
                fontSize = 12.sp,
                color = AppTextSecondary
            )
            FreqChipRow(
                items = listOf(0, 4, 10, 13, 16, 19),
                selected = diag?.bleTxPowerDbm,
                enabled = isReady,
                formatLabel = { "$it" },
                onSelect = { dbm ->
                    pmToastMessage = if (language == Language.Zh) "已发送 ${dbm} dBm…" else "Sending ${dbm} dBm…"
                    pmToastSuccess = true
                    bleManager.sendCommand("ble_tx_power", JSONObject().put("dbm", dbm), expectAck = true)
                }
            )

            SectionHeader(t("堆 / SRAM", "Heap / SRAM", language))
            DiagRow(t("Heap 已用", "Heap Used", language), bytesText(diag?.heapUsedBytes))
            DiagRow(t("Heap 总量", "Heap Total", language), bytesText(diag?.heapTotalBytes))
            DiagRow(t("Heap 峰值", "Heap Peak", language), bytesText(diag?.heapPeakBytes))
            DiagRow(t("SRAM 已用", "SRAM Used", language), bytesText(diag?.sramUsedBytes))
            DiagRow(t("SRAM 总量", "SRAM Total", language), bytesText(diag?.sramTotalBytes))
            DiagRow(t("SRAM 使用率", "SRAM Usage", language), percentText(diag?.sramUsedBytes, diag?.sramTotalBytes))

            SectionHeader("PSRAM")
            DiagRow(t("PSRAM 已用", "PSRAM Used", language), bytesText(diag?.psramUsedBytes))
            DiagRow(t("PSRAM 总量", "PSRAM Total", language), bytesText(diag?.psramTotalBytes))
            DiagRow(t("PSRAM 使用率", "PSRAM Usage", language), percentText(diag?.psramUsedBytes, diag?.psramTotalBytes))

            SectionHeader("FlashDB")
            DiagRow(t("键数量", "Keys", language), diag?.flashdbKeys?.toString() ?: "--")
            DiagRow(t("已用空间", "Used", language), bytesText(diag?.flashdbUsedBytes))
            DiagRow(t("总空间", "Total", language), bytesText(diag?.flashdbTotalBytes))
            DiagRow(t("使用率", "Usage", language), percentText(diag?.flashdbUsedBytes, diag?.flashdbTotalBytes))

            SectionHeader(t("BLE测速模式", "BLE Speed Test", language))
            DiagRow(
                t("状态", "Status", language),
                if (speedTestEnabled) t("运行中", "Running", language) else t("未运行", "Stopped", language)
            )
            DiagRow(
                t("下行", "Downlink", language),
                String.format(
                    t("%.1f KB/s (%.1f kbps) · %d pkt/s", "%.1f KB/s (%.1f kbps) · %d pkt/s", language),
                    downKBps, downKbps, downPps.roundToInt()
                )
            )
            DiagRow(
                t("上行", "Uplink", language),
                String.format(
                    t("%.1f KB/s (%.1f kbps) · %d pkt/s", "%.1f KB/s (%.1f kbps) · %d pkt/s", language),
                    upKBps, upKbps, upPps.roundToInt()
                )
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { bleManager.startSpeedTest(160, 10) },
                    enabled = isAudioReady && !speedTestEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (speedTestEnabled) AppBackgroundTertiary else AppPrimary
                    ),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(t("开始测速", "Start Test", language), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { bleManager.stopSpeedTest() },
                    enabled = speedTestEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (speedTestEnabled) AppWarning else AppBackgroundTertiary
                    ),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        t("停止测速", "Stop Test", language),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (speedTestEnabled) AppTextPrimary else AppTextSecondary
                    )
                }
            }

            SectionHeader(t("工具", "Tools", language))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .deviceDiagCard()
                    .padding(vertical = 4.dp)
            ) {
                lastSetMac?.let { mac ->
                    DiagnosticsInfoRow(t("上次写入 MAC", "Last Written MAC", language), mac)
                    CardDivider(16.dp)
                }
                DiagnosticsActionRow(
                    accent = Color(0xFF5856D6),
                    title = t("随机生成 MAC 地址", "Randomize MAC Address", language),
                    subtitle = t("生成新地址并写入 MCU NVDS", "Generate new address and write to MCU NVDS", language),
                    enabled = isReady && !isMacWriting,
                    showChevron = false,
                    trailingLoading = isMacWriting,
                    onClick = {
                        isMacWriting = true
                        val mac = bleManager.setRandomMacAddress()
                        lastSetMac = mac
                        scope.launch {
                            delay(600)
                            isMacWriting = false
                        }
                    }
                )
                CardDivider()
                DiagnosticsActionRow(
                    accent = Color(0xFFFF3B30),
                    title = t("MCU 崩溃日志", "MCU Crash Log", language),
                    subtitle = t("查询、清除、复制上次崩溃记录", "View, clear, or copy the last crash record", language),
                    onClick = onOpenCrashLog
                )
                CardDivider()
                DiagnosticsActionRow(
                    accent = AppPrimary,
                    title = t("寄存器快照", "Register Snapshot", language),
                    subtitle = t("按外设展开查看，支持搜索", "Expand by peripheral, searchable", language),
                    onClick = onOpenRegisters
                )
            }

            SectionHeader(t("电源 / PM", "Power / PM", language))
            pmToastMessage?.let { msg ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (pmToastSuccess) AppPrimaryLight else AppError.copy(alpha = 0.12f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(msg, fontSize = 13.sp, color = if (pmToastSuccess) AppPrimary else AppError)
                }
            }

            PmButton(
                title = t("PM IO 下电", "PM IO Down", language),
                subtitle = t(
                    "BSP IO/外设下电，测漏电用；可能 BLE 断连，测完请点「PM IO 上电」恢复。",
                    "BSP IO/peripheral power-down for leakage test; BLE may disconnect — tap PM IO Up to restore.",
                    language
                ),
                enabled = isReady,
                onClick = {
                    pmToastMessage = t("已发送，等待回复…", "Sent, waiting for reply…", language)
                    pmToastSuccess = true
                    bleManager.sendCommand("pm_io_down", expectAck = true)
                }
            )
            PmShortButton(
                title = t("PM IO 上电", "PM IO Up", language),
                enabled = isReady,
                onClick = {
                    pmToastMessage = t("已发送，等待回复…", "Sent, waiting for reply…", language)
                    pmToastSuccess = true
                    bleManager.sendCommand("pm_io_up", expectAck = true)
                }
            )
            PmButton(
                title = t("要 IDLE 锁", "Request IDLE Lock", language),
                subtitle = t(
                    "仅调用 rt_pm_request(PM_SLEEP_MODE_IDLE)，仅 IDLE，禁止进入 Deep Sleep。",
                    "Calls rt_pm_request(PM_SLEEP_MODE_IDLE) only; no deep sleep.",
                    language
                ),
                enabled = isReady,
                onClick = {
                    pmToastMessage = t("已发送，等待回复…", "Sent, waiting for reply…", language)
                    pmToastSuccess = true
                    bleManager.sendCommand("pm_deepsleep", JSONObject().put("enable", false), expectAck = true)
                }
            )
            PmButton(
                title = t("释放 IDLE 锁", "Release IDLE Lock", language),
                subtitle = t(
                    "仅调用 rt_pm_release(PM_SLEEP_MODE_IDLE)，允许空闲时进入 Deep Sleep。",
                    "Calls rt_pm_release(PM_SLEEP_MODE_IDLE) only; allows deep sleep when idle.",
                    language
                ),
                enabled = isReady,
                onClick = {
                    pmToastMessage = t("已发送，等待回复…", "Sent, waiting for reply…", language)
                    pmToastSuccess = true
                    bleManager.sendCommand("pm_release_idle", expectAck = true)
                }
            )
            PmButton(
                title = t("允许 Deep Sleep", "Deep Sleep On", language),
                subtitle = t(
                    "释放 IDLE 锁，空闲时可进入 Deep Sleep 省电；BLE 通常保持连接。",
                    "Release IDLE lock; device may enter deep sleep when idle (BLE usually stays connected).",
                    language
                ),
                enabled = isReady,
                onClick = {
                    pmToastMessage = t("已发送，等待回复…", "Sent, waiting for reply…", language)
                    pmToastSuccess = true
                    bleManager.sendCommand("pm_deepsleep", JSONObject().put("enable", true), expectAck = true)
                }
            )
            PmShortButton(
                title = t("禁止 Deep Sleep", "Deep Sleep Off", language),
                enabled = isReady,
                onClick = {
                    pmToastMessage = t("已发送，等待回复…", "Sent, waiting for reply…", language)
                    pmToastSuccess = true
                    bleManager.sendCommand("pm_deepsleep", JSONObject().put("enable", false), expectAck = true)
                }
            )
            PmButton(
                title = t("功耗测试 Idle", "Power Test Idle", language),
                subtitle = t(
                    "BLE 关 + 允许深睡，测待机电流；设备会断连，串口或复位唤醒。",
                    "BLE off + deep sleep for standby current; device will disconnect, wake by UART or reset.",
                    language
                ),
                enabled = isReady,
                onClick = {
                    pmToastMessage = t("已发送，等待回复…", "Sent, waiting for reply…", language)
                    pmToastSuccess = true
                    bleManager.sendCommand("power_test_idle", expectAck = true)
                }
            )
            PmButton(
                title = t("关 RF + LCPU Halt", "RF Off + LCPU Halt", language),
                subtitle = t(
                    "BLE 关 + 深睡 + 2 秒后 halt LCPU；串口可能停，仅复位可恢复。",
                    "BLE off + deep sleep, then halt LCPU in 2s; serial may stop, reset to recover.",
                    language
                ),
                enabled = isReady,
                onClick = {
                    pmToastMessage = t("已发送，等待回复…", "Sent, waiting for reply…", language)
                    pmToastSuccess = true
                    bleManager.sendCommand("power_off_rf_lcpu_halt", expectAck = true)
                }
            )

            PmButton(
                title = t("彻底关机", "Shutdown", language),
                subtitle = t(
                    "设备进入休眠（hibernate），BLE 断开；RTC 或按键可唤醒。",
                    "Device enters hibernate and disconnects; wake by RTC or button.",
                    language
                ),
                enabled = isReady,
                titleColor = AppError,
                onClick = { bleManager.sendCommand("shutdown", expectAck = true) }
            )

            Button(
                onClick = { bleManager.requestDeviceDiagnostics() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppPrimary),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
            ) {
                Text(t("刷新诊断数据", "Refresh Diagnostics", language), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppTextSecondary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun CardDivider(start: androidx.compose.ui.unit.Dp = 56.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = start)
            .height(0.5.dp)
            .background(Color(0x14000000))
    )
}

@Composable
private fun DiagnosticsNavRow(
    accent: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.95f))
                )
            }
            Column {
                Text(title, fontSize = 17.sp, color = AppTextPrimary)
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = AppTextSecondary
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Color(0xFFD1D5DB),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun DiagnosticsToggleRow(
    accent: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.95f))
                )
            }
            Column {
                Text(title, fontSize = 17.sp, color = AppTextPrimary)
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = AppTextSecondary
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun DiagnosticsInfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = AppTextPrimary,
            fontSize = 15.sp
        )
        Text(
            text = value,
            color = AppTextSecondary,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun DiagnosticsActionRow(
    accent: Color,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    trailingLoading: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.95f))
                )
            }
            Column {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    color = if (enabled) AppTextPrimary else AppTextSecondary
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = AppTextSecondary
                )
            }
        }
        when {
            trailingLoading -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = AppPrimary
            )
            showChevron -> Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFFD1D5DB),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .deviceDiagCard()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = AppTextPrimary)
            Text(subtitle, fontSize = 12.sp, color = AppTextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppTextSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .deviceDiagCard()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = AppTextPrimary, fontSize = 14.sp)
        Text(value, color = AppTextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Normal)
    }
}

@Composable
private fun FreqChipRow(
    items: List<Int>,
    selected: Int?,
    enabled: Boolean,
    formatLabel: (Int) -> String,
    onSelect: (Int) -> Unit
) {
    val scroll = rememberScrollState()
    Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (item in items) {
            val isSelected = selected?.let { it == item } ?: false
            Button(
                onClick = { onSelect(item) },
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSelected) AppPrimaryLight else AppBackgroundSecondary,
                    contentColor = if (isSelected) AppPrimary else AppTextPrimary
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(formatLabel(item), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun PmButton(
    title: String,
    subtitle: String,
    enabled: Boolean,
    titleColor: androidx.compose.ui.graphics.Color = AppTextPrimary,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .deviceDiagCard(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = titleColor)
        Text(subtitle, fontSize = 12.sp, color = AppTextSecondary, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PmShortButton(title: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .deviceDiagCard(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = AppTextPrimary)
    }
}

private fun bytesText(value: Int?): String {
    if (value == null || value < 0) return "--"
    val kb = value / 1024.0
    return if (kb >= 1024) String.format("%.2f MB", kb / 1024.0) else String.format("%.1f KB", kb)
}

private fun percentText(used: Int?, total: Int?): String {
    if (used == null || total == null || total <= 0) return "--"
    val p = (used.toDouble() / total.toDouble()) * 100.0
    return String.format("%.1f%%", p)
}

private fun cpuFreqText(mhz: Int?): String {
    if (mhz == null || mhz <= 0) return "--"
    return "$mhz MHz"
}

private fun bleAdvIntervalText(ms: Int?): String {
    if (ms == null || ms < 0) return "--"
    return "$ms ms"
}

private fun bleConnIntervalText(units: Int?): String {
    if (units == null || units <= 0) return "--"
    val ms = (units * 125) / 100
    return "$ms ms ($units units)"
}

private fun bleTxPowerText(dbm: Int?): String {
    if (dbm == null) return "--"
    return "$dbm dBm"
}

private fun deepSleepText(language: Language, allowed: Int?): String {
    if (allowed == null) return "--"
    return if (allowed != 0) t("允许", "Allowed", language) else t("禁止", "Disabled", language)
}

private fun uptimeText(language: Language, uptimeMs: Int?): String {
    if (uptimeMs == null || uptimeMs < 0) return "--"
    val totalSeconds = uptimeMs / 1000
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (language == Language.Zh) {
        when {
            days > 0 -> "${days}天 ${hours}小时 ${minutes}分"
            hours > 0 -> "${hours}小时 ${minutes}分 ${seconds}秒"
            minutes > 0 -> "${minutes}分 ${seconds}秒"
            else -> "${seconds}秒"
        }
    } else {
        when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}
