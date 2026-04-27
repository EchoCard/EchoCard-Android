package com.vaca.callmate.ui.screens.device

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.setIndicatorColor
import com.vaca.callmate.core.ble.setIndicatorLight
import com.vaca.callmate.core.ble.setPA20Level
import com.vaca.callmate.core.ble.requestDeviceInfo
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private fun Modifier.lightControlCard(shape: RoundedCornerShape = RoundedCornerShape(20.dp)): Modifier =
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

@Composable
fun DeviceLightControlScreen(
    language: Language,
    bleManager: BleManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCtrlReady by bleManager.isCtrlReady.collectAsState()
    val ledEnabled by bleManager.deviceLEDEnabled.collectAsState()
    val ledBrightness by bleManager.deviceLEDBrightness.collectAsState()
    val pa20 by bleManager.devicePA20LevelHigh.collectAsState()

    var localEnabled by remember { mutableStateOf(true) }
    var brightness by remember { mutableFloatStateOf(48f) }
    var selectedColor by remember { mutableStateOf("off") }
    var pa20Local by remember { mutableStateOf(false) }

    LaunchedEffect(ledEnabled, ledBrightness, pa20) {
        ledEnabled?.let { localEnabled = it }
        ledBrightness?.let { brightness = it.toFloat() }
        pa20?.let { pa20Local = it }
    }

    LaunchedEffect(Unit) {
        bleManager.requestDeviceInfo()
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
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = t("灯光控制", "Light Control", language),
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightControlCard()
                    .padding(vertical = 4.dp)
            ) {
                LightControlToggleRow(
                    title = t("指示灯开关", "Indicator Light", language),
                    subtitle = t("关闭后设备状态灯将保持熄灭", "When disabled, the device status light stays off", language),
                    accent = Color(0xFFFF9500),
                    checked = localEnabled,
                    enabled = isCtrlReady,
                    onCheckedChange = { v ->
                        localEnabled = v
                        if (isCtrlReady) bleManager.setIndicatorLight(enabled = v)
                    }
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp)
                        .height(0.5.dp)
                        .background(Color(0x14000000))
                )
                LightControlToggleRow(
                    title = t("PA20 输出", "PA20 Output", language),
                    subtitle = t("开启为高电平，关闭为低电平", "On = HIGH, Off = LOW", language),
                    accent = AppPrimary,
                    checked = pa20Local,
                    enabled = isCtrlReady,
                    onCheckedChange = { v ->
                        pa20Local = v
                        if (isCtrlReady) bleManager.setPA20Level(v)
                    }
                )
            }

            SectionHeader(t("亮度", "Brightness", language))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightControlCard()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFF9500)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.95f))
                            )
                        }
                        Text(
                            t("当前亮度", "Current Brightness", language),
                            fontSize = 17.sp,
                            color = AppTextPrimary
                        )
                    }
                    Text(
                        "${brightness.toInt()}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = AppPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(AppPrimary.copy(alpha = 0.1f))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it },
                    valueRange = 0f..255f,
                    onValueChangeFinished = {
                        if (isCtrlReady) bleManager.setIndicatorLight(brightness = brightness.toInt())
                    },
                    enabled = isCtrlReady && localEnabled
                )
                Text(
                    t("范围 0-255，数值越大越亮", "Range 0-255. Higher values mean brighter light", language),
                    fontSize = 13.sp,
                    color = AppTextSecondary
                )
            }

            SectionHeader(t("颜色", "Color", language))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightControlCard()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ColorButton(
                    label = t("红", "Red", language),
                    value = "red",
                    fill = Color.Red,
                    selected = selectedColor,
                    enabled = isCtrlReady && localEnabled,
                    onSelect = {
                        selectedColor = it
                        bleManager.setIndicatorColor(it)
                    }
                )
                ColorButton(
                    label = t("绿", "Green", language),
                    value = "green",
                    fill = Color(0xFF34C759),
                    selected = selectedColor,
                    enabled = isCtrlReady && localEnabled,
                    onSelect = {
                        selectedColor = it
                        bleManager.setIndicatorColor(it)
                    }
                )
                ColorButton(
                    label = t("蓝", "Blue", language),
                    value = "blue",
                    fill = Color(0xFF007AFF),
                    selected = selectedColor,
                    enabled = isCtrlReady && localEnabled,
                    onSelect = {
                        selectedColor = it
                        bleManager.setIndicatorColor(it)
                    }
                )
            }

            if (!isCtrlReady) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x14FF9500))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF9500))
                    )
                    Text(
                        t("设备未连接，暂时无法修改指示灯设置。", "Device is not connected, indicator settings cannot be changed right now.", language),
                        fontSize = 13.sp,
                        color = AppTextSecondary
                    )
                }
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
        modifier = Modifier.padding(start = 16.dp)
    )
}

@Composable
private fun LightControlToggleRow(
    title: String,
    subtitle: String,
    accent: Color,
    checked: Boolean,
    enabled: Boolean,
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
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.95f))
                )
            }
            Column {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    color = AppTextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = AppTextSecondary
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun ColorButton(
    label: String,
    value: String,
    fill: Color,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = enabled) { onSelect(value) }
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(fill)
                .then(
                    if (selected == value) Modifier.border(3.dp, AppPrimary, CircleShape)
                    else Modifier
                )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (selected == value) AppPrimary else AppTextSecondary
        )
    }
}
