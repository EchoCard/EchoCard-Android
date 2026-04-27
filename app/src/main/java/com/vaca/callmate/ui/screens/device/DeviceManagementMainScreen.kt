package com.vaca.callmate.ui.screens.device

import android.util.Log
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.requestDeviceInfo
import com.vaca.callmate.core.firmware.FirmwareUpdateService
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.Language
import com.vaca.callmate.core.firmware.FirmwareUpdateService.FirmwareUpdateStage
import com.vaca.callmate.ui.theme.AppAccent
import com.vaca.callmate.ui.theme.AppAccentLight
import com.vaca.callmate.ui.theme.AppBackgroundCard
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppChevron
import com.vaca.callmate.ui.theme.AppError
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppRadius
import com.vaca.callmate.ui.theme.AppSpacing
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppTextTertiary
import com.vaca.callmate.ui.theme.AppTypography
import com.vaca.callmate.ui.theme.AppWarning
import com.vaca.callmate.ui.theme.Gray100
import com.vaca.callmate.ui.theme.Gray700
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private val PremiumTitleLight = Color(0xFF8C5000)
private val PremiumGradientStart = Color(0xFFFFF9F0)
private val PremiumGradientEnd = Color(0xFFFFF0D9)

private const val DEVICE_UI_LOG = "CallMateDeviceUI"

private fun Modifier.deviceMainCard(shape: RoundedCornerShape = RoundedCornerShape(AppRadius.xl)): Modifier =
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
fun DeviceManagementMainScreen(
    language: Language,
    bleManager: BleManager,
    preferences: AppPreferences,
    onClose: () -> Unit,
    onUnbindDevice: () -> Unit,
    onOpenAdvanced: () -> Unit,
    modifier: Modifier = Modifier
) {
    val btState by bleManager.bluetoothState.collectAsState()
    val isCtrlReady by bleManager.isCtrlReady.collectAsState()
    val connecting by bleManager.connectingAddress.collectAsState()
    val connected by bleManager.connectedAddress.collectAsState()
    val devices by bleManager.devices.collectAsState()
    val battery by bleManager.deviceBattery.collectAsState()
    val charging by bleManager.deviceCharging.collectAsState()
    val fw by bleManager.deviceFirmwareVersion.collectAsState()
    val chipName by bleManager.deviceChipName.collectAsState()
    val latestMeta by FirmwareUpdateService.latestMetadata.collectAsState()
    val fwChecking by FirmwareUpdateService.isChecking.collectAsState()
    val fwUpdating by FirmwareUpdateService.isUpdating.collectAsState()
    val fwProgress by FirmwareUpdateService.progress.collectAsState()
    val fwStatusText by FirmwareUpdateService.statusText.collectAsState()
    val fwUpdateErr by FirmwareUpdateService.lastError.collectAsState()
    val fwStage by FirmwareUpdateService.updateStage.collectAsState()
    val scope = rememberCoroutineScope()
    val latestVer = latestMeta?.version ?: "--"
    val canUpgrade = FirmwareUpdateService.isUpdateAvailable(fw, latestMeta?.version)
    val bleBond by bleManager.deviceBleBondState.collectAsState()
    val aiCallsTotal by preferences.aiCallsTotalFlow.collectAsState(initial = 0)

    var manualReconnect by remember { mutableStateOf(false) }
    LaunchedEffect(manualReconnect) {
        if (!manualReconnect) return@LaunchedEffect
        delay(16_000)
        manualReconnect = false
    }

    LaunchedEffect(Unit) {
        Log.i(
            DEVICE_UI_LOG,
            "enter DeviceManagement: btState=${bleManager.bluetoothState.value} " +
                "connected=${bleManager.connectedAddress.value} " +
                "connecting=${bleManager.connectingAddress.value} " +
                "isCtrlReady=${bleManager.isCtrlReady.value} isReady=${bleManager.isReady.value}"
        )
        if (bleManager.isCtrlReady.value) {
            Log.i(DEVICE_UI_LOG, "onAppear: requestDeviceInfo (iOS parity)")
            bleManager.requestDeviceInfo()
        } else {
            Log.w(DEVICE_UI_LOG, "onAppear: ctrl not ready — polling will call get_info when ready")
        }
        while (true) {
            delay(5_000)
            if (bleManager.isCtrlReady.value) {
                bleManager.requestDeviceInfo()
            }
        }
    }

    /** 与 iOS `DeviceModalView.onAppear` 的 `checkForUpdate` 一致（网络可用即拉取；chip 变化时重试）。 */
    LaunchedEffect(chipName) {
        FirmwareUpdateService.checkForUpdate(chipName)
    }

    val deviceName = remember(connected, devices) {
        connected?.let { addr -> devices.firstOrNull { it.address == addr }?.name }
            ?: t("未命名设备", "Unnamed Device", language)
    }
    val idSuffix = connected?.replace(":", "")?.takeLast(8) ?: "--"
    val (statusText, statusColor) = deviceManagementConnectionLabel(
        language, isCtrlReady, connecting, connected, btState
    )

    val callsGoal = 100
    val totalCalls = maxOf(0, aiCallsTotal)
    val callsProgress = (totalCalls.toFloat() / callsGoal).coerceIn(0f, 1f)
    val reportsGoal = 3
    val reportsDone = (totalCalls / 30).coerceAtMost(reportsGoal)
    val reportsProgress = (reportsDone.toFloat() / reportsGoal).coerceIn(0f, 1f)

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
            IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBackIos,
                    contentDescription = null,
                    tint = AppPrimary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = t("设备管理", "Device", language),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextPrimary
                )
            }
            Spacer(modifier = Modifier.size(44.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EchoCardPermissionsCard(language = language, bleManager = bleManager)

            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .deviceMainCard()
                        .padding(20.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(AppPrimary)
                                .shadow(4.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.06f), ambientColor = Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = deviceName,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTextPrimary,
                                maxLines = 1
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Text(
                                    text = statusText,
                                    fontSize = 13.sp,
                                    color = AppTextSecondary
                                )
                                if (isCtrlReady && battery != null) {
                                    val ch = charging == true
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(statusColor.copy(alpha = 0.1f))
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${battery}%${if (ch) " ⚡" else ""}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = statusColor
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "ID: $idSuffix · BLE: ${bleBond ?: "--"}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AppTextTertiary
                            )
                        }
                    }
                }
                if (isCtrlReady) {
                    Text(
                        text = t("断开", "Disconnect", language),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppError,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(20.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppError.copy(alpha = 0.12f))
                            .clickable {
                                bleManager.disconnect()
                                onClose()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                } else if (connected == null && btState == android.bluetooth.BluetoothAdapter.STATE_ON) {
                    Text(
                        text = if (manualReconnect) t("连接中...", "Connecting...", language)
                        else t("连接", "Connect", language),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppPrimary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(20.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppPrimary.copy(alpha = 0.12f))
                            .clickable(enabled = connecting == null) {
                                manualReconnect = true
                                bleManager.forceReconnect()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .deviceMainCard()
                    .padding(AppSpacing.lg)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = AppPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = t("固件信息", "Firmware Info", language),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    if (canUpgrade) {
                        Button(
                            onClick = {
                                scope.launch {
                                    FirmwareUpdateService.startUpdateIfAvailable(
                                        bleManager = bleManager,
                                        languageZh = language == Language.Zh,
                                    )
                                }
                            },
                            enabled = !fwChecking && !fwUpdating,
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppPrimary,
                                disabledContainerColor = AppPrimary.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(AppRadius.sm)
                        ) {
                            Text(
                                text = t("升级", "Upgrade", language),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    bleManager.requestDeviceInfo()
                                    FirmwareUpdateService.checkForUpdate(chipName)
                                }
                            },
                            enabled = !fwChecking && !fwUpdating,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = t("检查更新", "Check for update", language),
                                tint = AppPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                if (fwUpdating || fwStatusText.isNotEmpty() || fwStage == FirmwareUpdateStage.Rebooting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    if (fwUpdating && fwStage != FirmwareUpdateStage.Rebooting) {
                        LinearProgressIndicator(
                            progress = { fwProgress.toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = AppPrimary,
                            trackColor = AppPrimary.copy(alpha = 0.15f),
                        )
                    }
                    if (fwStatusText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = fwStatusText,
                            fontSize = 13.sp,
                            color = AppTextSecondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (fwUpdateErr != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = fwUpdateErr!!,
                            fontSize = 13.sp,
                            color = AppError,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = t("当前版本", "Current", language),
                            style = AppTypography.labelLarge,
                            color = AppTextSecondary
                        )
                        Text(
                            text = fw ?: "--",
                            style = AppTypography.displayMedium.copy(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 28.sp
                            ),
                            color = AppTextPrimary
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = t("最新版本", "Latest", language),
                            style = AppTypography.labelLarge,
                            color = AppTextSecondary
                        )
                        Text(
                            text = latestVer,
                            style = AppTypography.displayMedium.copy(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 28.sp
                            ),
                            color = AppTextPrimary
                        )
                    }
                }
            }

            InternshipCard(language, totalCalls, callsGoal, callsProgress, reportsDone, reportsGoal, reportsProgress)

            PremiumCard(language)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .deviceMainCard()
                    .clickable(onClick = onOpenAdvanced)
                    .padding(horizontal = AppSpacing.md, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = t("高级设置", "Advanced Settings", language),
                        style = AppTypography.bodyLarge,
                        color = AppTextPrimary
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = AppChevron,
                    modifier = Modifier.size(16.dp)
                )
            }

            Button(
                onClick = onUnbindDevice,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppError.copy(alpha = 0.12f),
                    contentColor = AppError
                ),
                shape = RoundedCornerShape(AppRadius.sm)
            ) {
                Icon(
                    Icons.Default.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = AppError
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    t("解除绑定设备", "Unbind Device", language),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun InternshipCard(
    language: Language,
    totalCalls: Int,
    callsGoal: Int,
    callsProgress: Float,
    reportsDone: Int,
    reportsGoal: Int,
    reportsProgress: Float
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                6.dp,
                RoundedCornerShape(AppRadius.xl),
                spotColor = Color.Black.copy(alpha = 0.04f),
                ambientColor = Color.Transparent
            )
            .border(0.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(AppRadius.xl))
            .clip(RoundedCornerShape(AppRadius.xl))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppBackgroundCard)
                .padding(AppSpacing.lg)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(AppAccentLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.TrendingUp,
                        contentDescription = null,
                        tint = AppAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = t("实习期转正", "Internship", language),
                    style = AppTypography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    color = AppTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = t("进行中", "Active", language),
                    style = AppTypography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = AppAccent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.full))
                        .background(AppAccentLight)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            InternshipProgressRow(
                label = t("累计代接电话", "Total Calls", language),
                valueText = "$totalCalls ",
                suffix = t("/ $callsGoal 次", "/ $callsGoal", language),
                progress = callsProgress
            )
            Spacer(modifier = Modifier.height(20.dp))
            InternshipProgressRow(
                label = t("工作汇报", "Reports", language),
                valueText = "$reportsDone ",
                suffix = t("/ $reportsGoal 份", "/ $reportsGoal", language),
                progress = reportsProgress
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(128.dp)
                .offset(x = 32.dp, y = (-16).dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(AppAccent.copy(alpha = 0.1f), Color.Transparent),
                        start = Offset.Zero,
                        end = Offset(400f, 400f)
                    )
                )
        )
    }
}

@Composable
private fun PremiumCard(language: Language) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                6.dp,
                RoundedCornerShape(AppRadius.xl),
                spotColor = Color.Black.copy(alpha = 0.04f),
                ambientColor = Color.Transparent
            )
            .clip(RoundedCornerShape(AppRadius.xl))
            .background(
                Brush.horizontalGradient(
                    listOf(PremiumGradientStart, PremiumGradientEnd)
                )
            )
            .border(1.dp, AppWarning.copy(alpha = 0.1f), RoundedCornerShape(AppRadius.xl))
            .padding(AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.6f))
                .shadow(
                    4.dp,
                    CircleShape,
                    spotColor = Color.Black.copy(alpha = 0.06f),
                    ambientColor = Color.Transparent
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = AppWarning,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = t("高级订阅", "Premium", language),
                style = AppTypography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = PremiumTitleLight
            )
            Text(
                text = t("转正后解锁专属特权", "Unlock exclusive benefits after graduation", language),
                style = AppTypography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = PremiumTitleLight.copy(alpha = 0.6f)
            )
        }
        Text(
            text = t("即将推出", "Coming", language),
            style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = PremiumTitleLight,
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.full))
                .background(Color.White.copy(alpha = 0.5f))
                .shadow(
                    4.dp,
                    RoundedCornerShape(AppRadius.full),
                    spotColor = Color.Black.copy(alpha = 0.06f),
                    ambientColor = Color.Transparent
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun InternshipProgressRow(
    label: String,
    valueText: String,
    suffix: String,
    progress: Float
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = AppTypography.bodySmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = Gray700
            )
            Row {
                Text(
                    text = valueText,
                    style = AppTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = AppAccent
                )
                Text(
                    text = suffix,
                    style = AppTypography.labelLarge,
                    color = AppTextTertiary
                )
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = AppAccent,
            trackColor = Gray100
        )
    }
}
