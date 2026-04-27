package com.vaca.callmate.ui.screens.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppTextTertiary
import com.vaca.callmate.ui.theme.AppWarning

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private fun Modifier.permissionOuterCard(shape: RoundedCornerShape = RoundedCornerShape(20.dp)): Modifier =
    this
        .shadow(
            elevation = 6.dp,
            shape = shape,
            spotColor = Color.Black.copy(alpha = 0.04f),
            ambientColor = Color.Transparent
        )
        .clip(shape)
        .background(AppSurface)
        .border(0.5.dp, Color.White.copy(alpha = 0.55f), shape)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

@SuppressLint("MissingPermission")
private fun networkOk(context: Context): Boolean {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_NETWORK_STATE) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val n = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(n) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private enum class EchoPermKind {
    BluetoothDenied,
    BluetoothOff,
    Network,
    Microphone,
    Notification,
    HfpPairing,
}

@Composable
fun EchoCardPermissionsCard(
    language: Language,
    bleManager: BleManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val adapter = remember { BluetoothAdapter.getDefaultAdapter() }
    val btOn = adapter?.isEnabled == true
    val btPermOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
            PackageManager.PERMISSION_GRANTED
    }

    var permissionEpoch by remember { mutableStateOf(0) }
    var micPromptedOnce by remember { mutableStateOf(false) }
    var notifPromptedOnce by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        micPromptedOnce = true
        permissionEpoch++
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notifPromptedOnce = true
        permissionEpoch++
    }

    val micOk = remember(permissionEpoch) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val notifOk = remember(permissionEpoch) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    val netOk = remember(permissionEpoch) { networkOk(context) }

    val hfpPairing by bleManager.deviceHFPPairingNeeded.collectAsState()
    val isReady by bleManager.isReady.collectAsState()

    val missing = remember(
        btOn, btPermOk, micOk, notifOk, netOk, hfpPairing, isReady, permissionEpoch
    ) {
        buildList {
            if (!btPermOk) add(EchoPermKind.BluetoothDenied)
            else if (!btOn) add(EchoPermKind.BluetoothOff)
            if (!netOk) add(EchoPermKind.Network)
            if (!micOk) add(EchoPermKind.Microphone)
            if (!notifOk) add(EchoPermKind.Notification)
            if (hfpPairing && isReady) add(EchoPermKind.HfpPairing)
        }
    }

    if (missing.isEmpty()) return

    val onlyNetwork = missing.size == 1 && missing[0] == EchoPermKind.Network
    val onlyBtOff = missing.size == 1 && missing[0] == EchoPermKind.BluetoothOff
    val onlyBtDenied = missing.size == 1 && missing[0] == EchoPermKind.BluetoothDenied

    val headerTitle = when {
        onlyNetwork -> t("网络未连接，EchoCard 暂时不可用", "No internet connection — EchoCard is temporarily unavailable", language)
        onlyBtOff -> t("蓝牙未开启，EchoCard 暂时不可用", "Bluetooth is off — EchoCard is temporarily unavailable", language)
        onlyBtDenied -> t("蓝牙权限未授权，EchoCard 暂时不可用", "Bluetooth permission denied — EchoCard is temporarily unavailable", language)
        else -> t("需要开启权限才能正常使用 EchoCard", "Enable permissions for EchoCard", language)
    }

    val footerHint = when {
        onlyNetwork -> t("请打开 Wi‑Fi 或蜂窝数据后再试。", "Turn on Wi‑Fi or Cellular Data and try again.", language)
        onlyBtOff -> t("请在系统设置中开启蓝牙。", "Turn on Bluetooth in Settings.", language)
        onlyBtDenied -> t("请在系统设置中允许蓝牙访问。", "Allow Bluetooth access in Settings.", language)
        else -> t("若你刚刚点了「不允许」，请到系统设置中重新开启。", "If you tapped \"Don't Allow\", re-enable it in Settings.", language)
    }

    val micShowSettings = !micOk && micPromptedOnce && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
    val notifShowSettings = !notifOk && notifPromptedOnce && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .permissionOuterCard()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppWarning.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = AppWarning,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = headerTitle,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextPrimary
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                missing.forEach { kind ->
                    when (kind) {
                        EchoPermKind.BluetoothDenied -> PermissionInnerRow(
                            icon = Icons.Default.BluetoothDisabled,
                            title = t("蓝牙权限被拒绝", "Bluetooth permission denied", language),
                            subtitle = t("请在系统设置中允许蓝牙访问。", "Allow Bluetooth access in Settings.", language),
                            actionLabel = t("打开设置", "Open Settings", language),
                            onAction = { openAppSettings(context) },
                        )
                        EchoPermKind.BluetoothOff -> PermissionInnerRow(
                            icon = Icons.Default.Bluetooth,
                            title = t("蓝牙已关闭", "Bluetooth is off", language),
                            subtitle = t("请在控制中心或系统设置中打开蓝牙。", "Turn on Bluetooth in Control Center or Settings.", language),
                            actionLabel = null,
                            onAction = null,
                        )
                        EchoPermKind.Network -> PermissionInnerRow(
                            icon = Icons.Default.WifiOff,
                            title = t("网络未连接", "No internet connection", language),
                            subtitle = t("请开启 Wi‑Fi 或蜂窝数据。", "Enable Wi‑Fi or Cellular Data.", language),
                            actionLabel = null,
                            onAction = null,
                        )
                        EchoPermKind.Microphone -> PermissionInnerRow(
                            icon = Icons.Default.Mic,
                            title = t("允许麦克风", "Allow microphone", language),
                            subtitle = t("用于通话录音/语音交互。", "For call recording/voice input.", language),
                            actionLabel = if (micShowSettings) {
                                t("去设置", "Open Settings", language)
                            } else {
                                t("允许", "Allow", language)
                            },
                            onAction = {
                                if (micShowSettings) {
                                    openAppSettings(context)
                                } else {
                                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                        )
                        EchoPermKind.Notification ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                PermissionInnerRow(
                                    icon = Icons.Default.Notifications,
                                    title = t("允许通知", "Allow notifications", language),
                                    subtitle = t("用于紧急来电提醒。", "Used for urgent call alerts.", language),
                                    actionLabel = if (notifShowSettings) {
                                        t("去设置", "Open Settings", language)
                                    } else {
                                        t("允许", "Allow", language)
                                    },
                                    onAction = {
                                        if (notifShowSettings) {
                                            openAppSettings(context)
                                        } else {
                                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    },
                                )
                            }
                        EchoPermKind.HfpPairing -> PermissionInnerRow(
                            icon = Icons.Default.Bluetooth,
                            title = t("来电接听异常：需重新配对", "Call answering failed: re-pairing required", language),
                            subtitle = t(
                                "经典蓝牙未与手机配对。请在系统蓝牙设置中完成配对后再试。",
                                "Classic Bluetooth isn’t paired. Pair in system Bluetooth settings.",
                                language
                            ),
                            actionLabel = t("打开设置", "Open Settings", language),
                            onAction = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                        )
                    }
                }
            }

            Text(
                text = footerHint,
                fontSize = 12.sp,
                color = AppTextTertiary,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun PermissionInnerRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppBackgroundSecondary)
            .border(0.5.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(AppPrimary.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = AppPrimary, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = AppTextSecondary,
                lineHeight = 16.sp,
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(AppPrimary.copy(alpha = 0.08f))
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppPrimary
                )
            }
        } else {
            Spacer(Modifier.size(0.dp))
        }
    }
}
