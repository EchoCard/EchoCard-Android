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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.sendFactoryResetCommand
import com.vaca.callmate.core.ble.sendRebootCommand
import com.vaca.callmate.core.ble.CallMateBleEvent
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppError
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppTextTertiary
import com.vaca.callmate.ui.theme.AppAccent
import com.vaca.callmate.ui.theme.AppWarning

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private fun Modifier.advancedGroupedCard(shape: RoundedCornerShape = RoundedCornerShape(20.dp)): Modifier =
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
fun DeviceAdvancedSettingsScreen(
    language: Language,
    bleManager: BleManager,
    onBack: () -> Unit,
    onOpenLight: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRebind: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCtrlReady by bleManager.isCtrlReady.collectAsState()
    val isReady by bleManager.isReady.collectAsState()
    var rebootConfirm by remember { mutableStateOf(false) }
    var rebindConfirm by remember { mutableStateOf(false) }
    var factoryConfirm by remember { mutableStateOf(false) }
    var factoryChecked by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        bleManager.bleEvents.collect { evt ->
            if (evt is CallMateBleEvent.Ack) {
                when (evt.cmd) {
                    "reboot" -> {
                        statusText = if (evt.result == 0) {
                            t("重启指令已发送，等待设备重连。", "Reboot command sent. Waiting for reconnect.", language)
                        } else {
                            t("重启失败，请重试。", "Reboot failed. Please try again.", language)
                        }
                    }
                    "factory_reset" -> {
                        statusText = if (evt.result == 0) {
                            t("恢复出厂指令已发送。", "Factory reset command sent.", language)
                        } else {
                            t("恢复出厂失败，请重试。", "Factory reset failed. Please try again.", language)
                        }
                    }
                }
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
                    text = t("高级设置", "Advanced Settings", language),
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
                    .advancedGroupedCard()
            ) {
                AdvancedRow(
                    icon = Icons.Default.Refresh,
                    iconBg = AppWarning,
                    title = t("重启设备", "Reboot Device", language),
                    enabled = isCtrlReady,
                    onClick = { rebootConfirm = true }
                )
                AdvancedRow(
                    icon = Icons.Default.Lightbulb,
                    iconBg = AppAccent,
                    title = t("灯光控制", "Light Control", language),
                    enabled = isCtrlReady,
                    onClick = onOpenLight
                )
                AdvancedRow(
                    icon = Icons.Default.Devices,
                    iconBg = AppPrimary,
                    title = t("设备诊断", "Device Diagnostics", language),
                    enabled = isReady,
                    onClick = onOpenDiagnostics
                )
                AdvancedRow(
                    icon = Icons.Default.Restore,
                    iconBg = AppError,
                    title = t("恢复出厂设置", "Factory Reset", language),
                    enabled = isCtrlReady,
                    onClick = {
                        factoryChecked = false
                        factoryConfirm = true
                    }
                )
                AdvancedRow(
                    icon = Icons.Default.Link,
                    iconBg = AppPrimary,
                    title = t("重新绑定", "Rebind Device", language),
                    enabled = true,
                    onClick = { rebindConfirm = true }
                )
            }
            statusText?.let { tx ->
                Text(
                    text = tx,
                    fontSize = 13.sp,
                    color = AppTextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }

    if (rebootConfirm) {
        AlertDialog(
            onDismissRequest = { rebootConfirm = false },
            title = { Text(t("重启设备", "Reboot Device", language)) },
            text = { Text(t("设备将立即重启，约 5-10 秒后恢复连接。", "Device will reboot immediately and reconnect in about 5-10 seconds.", language)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        rebootConfirm = false
                        bleManager.sendRebootCommand()
                    }
                ) {
                    Text(t("重启", "Reboot", language), color = AppError)
                }
            },
            dismissButton = {
                TextButton(onClick = { rebootConfirm = false }) {
                    Text(t("取消", "Cancel", language))
                }
            }
        )
    }

    if (rebindConfirm) {
        AlertDialog(
            onDismissRequest = { rebindConfirm = false },
            title = { Text(t("重新绑定", "Rebind", language)) },
            text = {
                Text(
                    t(
                        "将断开当前设备并进入绑定页面，可重新扫描并连接设备。",
                        "This will disconnect the current device and open the binding page to scan and connect again.",
                        language
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        rebindConfirm = false
                        onRebind()
                    }
                ) {
                    Text(t("确认", "Confirm", language), color = AppError)
                }
            },
            dismissButton = {
                TextButton(onClick = { rebindConfirm = false }) {
                    Text(t("取消", "Cancel", language))
                }
            }
        )
    }

    if (factoryConfirm) {
        AlertDialog(
            onDismissRequest = { factoryConfirm = false },
            title = { Text(t("恢复出厂设置", "Factory Reset", language)) },
            text = {
                Column {
                    Text(
                        t(
                            "EchoCard将会抹去APP和硬件上的所有数据，通话记录、AI应答策略、克隆声音和相关设置均会清空，同时该手机也不再是关联主机。",
                            "EchoCard will erase all data on the app and device: call history, AI response strategies, cloned voice and related settings will be cleared, and this phone will no longer be the linked host.",
                            language
                        ),
                        fontSize = 14.sp,
                        color = AppTextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { factoryChecked = !factoryChecked }
                    ) {
                        Checkbox(checked = factoryChecked, onCheckedChange = { factoryChecked = it })
                        Text(
                            t("我已了解此操作会清空数据！", "I understand this operation will clear all data!", language),
                            fontSize = 14.sp,
                            color = AppTextPrimary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!factoryChecked) return@TextButton
                        factoryConfirm = false
                        if (!isCtrlReady) {
                            statusText = t(
                                "设备未连接，无法发送恢复出厂指令。",
                                "Device is not connected. Unable to send factory reset.",
                                language
                            )
                            return@TextButton
                        }
                        bleManager.sendFactoryResetCommand()
                    },
                    enabled = factoryChecked
                ) {
                    Text(t("确认", "Confirm", language), color = AppError)
                }
            },
            dismissButton = {
                TextButton(onClick = { factoryConfirm = false }) {
                    Text(t("取消", "Cancel", language))
                }
            }
        )
    }
}

@Composable
private fun AdvancedRow(
    icon: ImageVector,
    iconBg: Color,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Normal,
            color = if (enabled) AppTextPrimary else AppTextTertiary,
            modifier = Modifier.weight(1f)
        )
        if (enabled) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFFD1D5DB),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
