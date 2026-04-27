package com.vaca.callmate.ui.screens.device

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.data.Language
import com.vaca.callmate.features.device.latency.LatencyTestViewModel
import com.vaca.callmate.features.device.latency.LatencyWaveformKind
import com.vaca.callmate.features.device.latency.LatencyWaveformTrace
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppBorder
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private fun measurementTitle(id: String, lang: Language): String = when (id) {
    "playback_to_ble" -> t("播放方波到 BLE 环回", "Playback to BLE loopback", lang)
    "ble_to_recording" -> t("BLE 环回到录音", "BLE loopback to recording", lang)
    "total" -> t("总延时", "Total latency", lang)
    else -> id
}

private fun traceTitle(kind: LatencyWaveformKind, lang: Language): String = when (kind) {
    LatencyWaveformKind.Playback -> t("播放方波", "Playback Square Wave", lang)
    LatencyWaveformKind.BleLoopback -> t("BLE 环回解码", "Decoded BLE Loopback", lang)
    LatencyWaveformKind.Microphone -> t("本地录音", "Local Recording", lang)
}

private fun markerTitle(id: String, lang: Language): String = when (id) {
    "playback" -> t("播放开始", "Playback start", lang)
    "playback_to_ble" -> t("BLE 首包", "BLE first packet", lang)
    "total" -> t("录音首边沿", "Recording first edge", lang)
    else -> id
}

private fun traceColorKind(kind: LatencyWaveformKind): Color = when (kind) {
    LatencyWaveformKind.Playback -> Color(0xFF007AFF)
    LatencyWaveformKind.BleLoopback -> Color(0xFFFF9500)
    LatencyWaveformKind.Microphone -> Color(0xFF34C759)
}

private data class LatencyTimelineMarkerData(
    val id: String,
    val title: String,
    val timeMs: Double,
    val color: Color
)

private fun Modifier.latencyGroupedCard(shape: RoundedCornerShape = RoundedCornerShape(20.dp)): Modifier =
    this
        .shadow(
            elevation = 6.dp,
            shape = shape,
            spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.04f),
            ambientColor = androidx.compose.ui.graphics.Color.Transparent
        )
        .clip(shape)
        .background(AppSurface)
        .border(0.5.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f), shape)

@Composable
fun LatencyTestScreen(
    language: Language,
    bleManager: BleManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val vm: LatencyTestViewModel = viewModel(factory = LatencyTestViewModel.factory(app, bleManager))

    val isReady by bleManager.isReady.collectAsState()
    val status by vm.statusMessage.collectAsState()
    val err by vm.errorMessage.collectAsState()
    val latencyMs by vm.measuredLatencyMs.collectAsState()
    val running by vm.isRunning.collectAsState()
    val continuous by vm.isContinuousRunning.collectAsState()
    val freq by vm.continuousAnalyzer.currentFrequencyHz.collectAsState()
    val wave by vm.continuousAnalyzer.lastWaveformSamples.collectAsState()
    val spectrum by vm.continuousAnalyzer.lastSpectrumMagnitudes.collectAsState()
    val stageMeasurements by vm.stageMeasurements.collectAsState()
    val waveformTraces by vm.waveformTraces.collectAsState()

    val timelineMarkers = remember(stageMeasurements, language) {
        val markers = mutableListOf<LatencyTimelineMarkerData>()
        markers.add(
            LatencyTimelineMarkerData(
                id = "playback",
                title = markerTitle("playback", language),
                timeMs = 0.0,
                color = traceColorKind(LatencyWaveformKind.Playback)
            )
        )
        for (m in stageMeasurements) {
            if (m.id != "playback_to_ble" && m.id != "total") continue
            val ms = m.milliseconds ?: continue
            val kind = if (m.id == "playback_to_ble") LatencyWaveformKind.BleLoopback else LatencyWaveformKind.Microphone
            markers.add(
                LatencyTimelineMarkerData(
                    id = m.id,
                    title = markerTitle(m.id, language),
                    timeMs = ms,
                    color = traceColorKind(kind)
                )
            )
        }
        markers
    }

    val busy = running || continuous
    val showLiveContinuous = continuous || wave.isNotEmpty()

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
                    t("延迟测试", "Latency Test", language),
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            Text(
                t(
                    "整链：播放方波 → HFP/SCO → MCU → BLE 环回 → MCU → HFP → 麦克风；测量首边沿相对播放的延迟。需 Android 9+ 与 Telecom 自管理通话。",
                    "Round-trip: square wave → HFP/SCO → MCU → BLE echo → MCU → HFP → mic. Measures first edge delay. Requires Android 9+ and Telecom self-managed call.",
                    language
                ),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = AppTextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .latencyGroupedCard()
                    .padding(16.dp)
            )

            SectionLabel(t("状态", "Status", language))
            Column(
                Modifier
                    .fillMaxWidth()
                    .latencyGroupedCard()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = AppPrimary
                        )
                        Spacer(Modifier.size(10.dp))
                    }
                    Text(
                        localizedStatus(status, language),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTextPrimary
                    )
                }
                err?.let { e ->
                    Text(
                        e,
                        fontSize = 13.sp,
                        color = Color(0xFFFF3B30),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                latencyMs?.let { ms ->
                    Text(
                        t("测得延迟：", "Measured: ", language) + "${ms.toInt()} ms",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppPrimary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (stageMeasurements.isNotEmpty()) {
                    stageMeasurements.forEach { m ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                measurementTitle(m.id, language),
                                fontSize = 14.sp,
                                color = AppTextPrimary
                            )
                            Text(
                                m.milliseconds?.let { "${it.toInt()} ms" } ?: "--",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTextPrimary
                            )
                        }
                    }
                }
            }

            if (waveformTraces.isNotEmpty()) {
                SectionLabel(t("波形", "Waveforms", language), top = 16.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .latencyGroupedCard()
                        .padding(16.dp)
                ) {
                    Text(
                        t(
                            "第 1 张图只看三个关键时刻；后面 3 张图分别放大播放方波、BLE 环回、本地录音，便于看形状和相位。",
                            "The first chart shows only the three key arrival times; the next three charts zoom in on playback, BLE loopback, and local recording separately for waveform shape and phase.",
                            language
                        ),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = AppTextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LatencyEventTimelineView(markers = timelineMarkers)
                    Spacer(Modifier.height(12.dp))
                    waveformTraces.forEach { trace ->
                        LatencyWaveformZoomView(trace = trace, language = language)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            if (showLiveContinuous) {
                SectionLabel(t("实时频率与波形", "Live Frequency & Waveform", language), top = 16.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .latencyGroupedCard()
                        .padding(16.dp)
                ) {
                    if (freq != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                t("检测频率", "Detected frequency", language),
                                fontSize = 14.sp,
                                color = AppTextPrimary
                            )
                            Text(
                                "${freq!!.toInt()} Hz",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTextPrimary
                            )
                        }
                    }
                    if (wave.isNotEmpty()) {
                        Text(
                            t("本地录音波形", "Local recording waveform", language),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppTextPrimary,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        ContinuousWaveformCanvas(
                            samples = wave,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(88.dp)
                                .padding(top = 6.dp)
                        )
                    }
                    if (spectrum.isNotEmpty()) {
                        Text(
                            t("幅度谱 (0–1 kHz)", "Magnitude spectrum (0–1 kHz)", language),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppTextPrimary,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        ContinuousSpectrumCanvas(
                            magnitudes = spectrum,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(top = 6.dp)
                        )
                    }
                }
            }

            SectionLabel(t("操作", "Actions", language), top = 20.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .latencyGroupedCard()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { vm.startTest() },
                    enabled = isReady && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppPrimary),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        t("开始延迟测试", "Start Latency Test", language),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Button(
                    onClick = {
                        if (continuous) vm.stopContinuousTest()
                        else vm.startContinuousTest()
                    },
                    enabled = isReady && (continuous || !running),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTextSecondary.copy(alpha = 0.2f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        if (continuous) t("停止持续测试", "Stop Continuous Test", language)
                        else t("持续通话测试", "Continuous Call Test", language),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTextPrimary
                    )
                }
                if (running) {
                    Button(
                        onClick = { vm.stopTest() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(t("停止", "Stop", language), color = Color.White)
                    }
                }
            }

            if (!isReady) {
                Text(
                    t("请先连接 BLE 设备。", "Connect BLE device first.", language),
                    fontSize = 13.sp,
                    color = AppTextSecondary,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

private fun localizedStatus(raw: String, language: Language): String {
    if (raw.isEmpty()) return t("未开始", "Idle", language)
    return when (raw) {
        "Starting…" -> t("开始中…", "Starting…", language)
        "Connecting HFP…" -> t("连接 HFP…", "Connecting HFP…", language)
        "Waiting for SCO…" -> t("等待 SCO…", "Waiting for SCO…", language)
        "Starting latency encoder…" -> t("启动延迟编码…", "Starting latency encoder…", language)
        "Playing square wave & recording…" -> t("播放方波并录音…", "Playing square wave & recording…", language)
        "Completed" -> t("已完成", "Completed", language)
        "Stopped" -> t("已停止", "Stopped", language)
        "Error" -> t("错误", "Error", language)
        else -> raw
    }
}

@Composable
private fun SectionLabel(text: String, top: Dp = 0.dp) {
    Text(
        text = text.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppTextSecondary,
        modifier = Modifier.padding(start = 16.dp, top = top, bottom = 6.dp)
    )
}

@Composable
private fun LatencyEventTimelineView(markers: List<LatencyTimelineMarkerData>) {
    val maxTimeMs = remember(markers) {
        val markerMax = markers.maxOfOrNull { it.timeMs } ?: 0.0
        max(12.0, markerMax + 8.0)
    }
    Column {
        markers.forEach { m ->
            Row(
                Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(m.color)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "${m.title}: ${m.timeMs.toInt()} ms",
                    fontSize = 12.sp,
                    color = AppTextSecondary
                )
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .padding(vertical = 4.dp)
        ) {
            val w = size.width
            val h = size.height
            val yMid = h / 2
            drawRoundRect(
                color = AppTextSecondary.copy(alpha = 0.08f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx(), 10.dp.toPx())
            )
            drawLine(
                color = AppTextSecondary.copy(alpha = 0.22f),
                start = Offset(0f, yMid),
                end = Offset(w, yMid),
                strokeWidth = 1f
            )
            markers.forEach { m ->
                val x = (w * (m.timeMs / maxTimeMs)).toFloat().coerceIn(0f, w)
                drawLine(
                    color = m.color.copy(alpha = 0.65f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f)
                )
                drawCircle(
                    color = m.color,
                    radius = 5.dp.toPx(),
                    center = Offset(x, yMid)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 ms", fontSize = 10.sp, color = AppTextSecondary)
            Text("${(maxTimeMs / 2).toInt()} ms", fontSize = 10.sp, color = AppTextSecondary)
            Text("${maxTimeMs.toInt()} ms", fontSize = 10.sp, color = AppTextSecondary)
        }
    }
}

@Composable
private fun LatencyWaveformZoomView(trace: LatencyWaveformTrace, language: Language) {
    val color = traceColorKind(trace.kind)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                traceTitle(trace.kind, language),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppTextPrimary
            )
            trace.eventTimeMs?.let { et ->
                Text(
                    "${et.toInt()} ms",
                    fontSize = 12.sp,
                    color = AppTextSecondary
                )
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(88.dp)
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(10.dp))
        ) {
            val sz = size
            val sampleCount = max(trace.samples.size, 2)
            val maxTimeMs = (sampleCount - 1) * 1000.0 / trace.sampleRate
            drawRoundRect(
                color = AppTextSecondary.copy(alpha = 0.08f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx(), 10.dp.toPx())
            )
            val midY = sz.height / 2
            drawLine(
                color = AppTextSecondary.copy(alpha = 0.18f),
                start = Offset(0f, midY),
                end = Offset(sz.width, midY),
                strokeWidth = 1f
            )
            val eventX = trace.eventTimeMs?.let { eventTimeMs ->
                val relative = eventTimeMs - trace.startTimeMs
                if (relative >= 0 && maxTimeMs > 0) sz.width * (relative / maxTimeMs).toFloat() else null
            }
            if (eventX != null) {
                drawLine(
                    color = color.copy(alpha = 0.55f),
                    start = Offset(eventX, 0f),
                    end = Offset(eventX, sz.height),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                )
            }
            if (trace.samples.size >= 2) {
                val path = Path()
                trace.samples.forEachIndexed { index, sample ->
                    val x = sz.width * (index.toFloat() / (sampleCount - 1).toFloat())
                    val y = sz.height * (0.5f - sample * 0.40f)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = color, style = Stroke(width = 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun ContinuousWaveformCanvas(samples: FloatArray, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .clip(RoundedCornerShape(10.dp))
    ) {
        val sz = size
        val count = max(samples.size, 2)
        drawRoundRect(
            color = AppTextSecondary.copy(alpha = 0.08f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx(), 10.dp.toPx())
        )
        val midY = sz.height / 2
        drawLine(
            color = AppTextSecondary.copy(alpha = 0.18f),
            start = Offset(0f, midY),
            end = Offset(sz.width, midY),
            strokeWidth = 1f
        )
        if (samples.size >= 2) {
            val path = Path()
            samples.forEachIndexed { index, sample ->
                val x = sz.width * (index.toFloat() / (count - 1).toFloat())
                val y = sz.height * (0.5f - sample * 0.40f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = Color(0xFF34C759), style = Stroke(width = 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}

@Composable
private fun ContinuousSpectrumCanvas(magnitudes: FloatArray, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .clip(RoundedCornerShape(10.dp))
    ) {
        val w = size.width
        val h = size.height
        val n = magnitudes.size.coerceAtLeast(1)
        val spacing = 2.dp.toPx()
        val barW = ((w - (n - 1) * spacing) / n).coerceAtLeast(1f)
        drawRoundRect(
            color = AppTextSecondary.copy(alpha = 0.08f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx(), 10.dp.toPx())
        )
        var x = 0f
        for (i in magnitudes.indices) {
            val mag = magnitudes[i].coerceIn(0f, 1f)
            val barH = (h * mag).coerceAtLeast(2f)
            drawRoundRect(
                color = Color(0xFFFF9500).copy(alpha = 0.5f + 0.5f * mag),
                topLeft = Offset(x, h - barH),
                size = androidx.compose.ui.geometry.Size(barW, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
            x += barW + spacing
        }
    }
}
