package com.vaca.callmate.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.DiscoveredDevice
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.Gray200
import com.vaca.callmate.ui.theme.Gray300
import com.vaca.callmate.ui.theme.Gray400
import com.vaca.callmate.ui.theme.Gray50
import com.vaca.callmate.ui.theme.CallMateTheme
import com.vaca.callmate.ui.theme.Gray500
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

/** 与 iOS `ScanningBindingView` 像素级对齐（浅色）。 */
@Composable
fun ScanningBindingContent(
    language: Language,
    bleManager: BleManager?,
    onBound: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val devices = if (bleManager != null) bleManager.devices.collectAsState(initial = emptyList()).value else emptyList()
    val bluetoothState = if (bleManager != null) {
        bleManager.bluetoothState.collectAsState(initial = BluetoothAdapter.STATE_OFF).value
    } else {
        BluetoothAdapter.STATE_ON
    }
    val connectingAddr = if (bleManager != null) bleManager.connectingAddress.collectAsState(initial = null).value else null
    val connectedAddr = if (bleManager != null) bleManager.connectedAddress.collectAsState(initial = null).value else null
    val isReady = if (bleManager != null) bleManager.isReady.collectAsState(initial = false).value else false
    val lastError = if (bleManager != null) bleManager.lastError.collectAsState(initial = null).value else null

    var cardAppear by remember { mutableStateOf(false) }
    val cardAlpha by animateFloatAsState(
        targetValue = if (cardAppear) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "cardAlpha"
    )
    val cardOffsetY by animateFloatAsState(
        targetValue = if (cardAppear) 0f else 20f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "cardY"
    )

    var showPairingToast by remember { mutableStateOf(false) }
    var showConnectTimeoutToast by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it } && bleManager != null) {
            bleManager.setBindingScanModeEnabled(true)
            bleManager.startScanning()
        }
    }

    fun isPairingRemovedError(err: String?): Boolean {
        if (err.isNullOrBlank()) return false
        val e = err.lowercase()
        return (e.contains("pairing") && e.contains("removed")) ||
            (err.contains("移除") && (err.contains("配对") || err.contains("配对信息")))
    }

    LaunchedEffect(bleManager) {
        if (bleManager == null) {
            delay(2500)
            onBound(null)
            return@LaunchedEffect
        }
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val needScan = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                val needConnect = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                if (needScan || needConnect) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        )
                    )
                    return@LaunchedEffect
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                    return@LaunchedEffect
                }
            }
        }
        bleManager.setBindingScanModeEnabled(true)
        bleManager.startScanning()
    }

    DisposableEffect(bleManager) {
        onDispose { bleManager?.setBindingScanModeEnabled(false) }
    }

    // 与 iOS 一致：就绪后延迟 5s 再进入 Bound（delay 结束后再次确认状态，避免闪烁）
    LaunchedEffect(isReady, connectedAddr, bleManager) {
        if (bleManager == null) return@LaunchedEffect
        if (!isReady || connectedAddr == null) return@LaunchedEffect
        val addr = connectedAddr
        delay(5000)
        val stillAddr = bleManager.connectedAddress.first()
        val stillReady = bleManager.isReady.first()
        if (stillReady && stillAddr == addr) onBound(addr)
    }

    LaunchedEffect(Unit) {
        delay(300)
        cardAppear = true
    }

    LaunchedEffect(lastError) {
        if (isPairingRemovedError(lastError)) {
            showPairingToast = true
            delay(4000)
            showPairingToast = false
        }
    }

    LaunchedEffect(connectingAddr, isReady, bleManager) {
        val manager = bleManager ?: return@LaunchedEffect
        val targetAddr = connectingAddr ?: return@LaunchedEffect
        if (isReady) return@LaunchedEffect
        delay(6000)
        val stillConnecting = manager.connectingAddress.first()
        val stillConnected = manager.connectedAddress.first()
        val stillReady = manager.isReady.first()
        if (!stillReady && stillConnected == null && stillConnecting == targetAddr) {
            showConnectTimeoutToast = true
            delay(6000)
            showConnectTimeoutToast = false
        }
    }

    val isConnectedState = connectedAddr != null && isReady
    val scanningTitle = scanningStatusTitle(bluetoothState, language)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isConnectedState) {
                    SuccessIllustrationIos()
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = t("连接完成，即将进入设置...", "Connected, entering setup...", language),
                        style = TextStyle(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF34C759),
                            letterSpacing = 0.5.sp
                        )
                    )
                } else {
                    RadarIllustrationIos()
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = scanningTitle,
                        style = TextStyle(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF007AFF),
                            letterSpacing = 0.5.sp
                        )
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .graphicsLayer {
                        alpha = cardAlpha
                        translationY = cardOffsetY
                    }
            ) {
                if (bluetoothState != BluetoothAdapter.STATE_ON) {
                    BluetoothOffSectionIos(language, context)
                } else {
                    Text(
                        text = t("以下为搜索到的 EchoCard 设备：", "Discovered EchoCard devices:", language),
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Gray500
                        ),
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .padding(bottom = 12.dp)
                    )

                    if (bleManager != null) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (devices.isEmpty() && !isConnectedState) {
                                item { EmptyDeviceCardIos(language) }
                            } else {
                                items(devices, key = { it.address }) { device ->
                                    val isThisConnected = connectedAddr == device.address
                                    val disabled = isConnectedState ||
                                        (connectingAddr != null && connectingAddr != device.address)
                                    DeviceRowIos(
                                        device = device,
                                        language = language,
                                        bleManager = bleManager,
                                        isConnecting = connectingAddr == device.address,
                                        isConnected = connectedAddr == device.address,
                                        isFullyConnected = isThisConnected && isReady,
                                        enabled = !disabled,
                                        modifier = Modifier.graphicsLayer {
                                            alpha = if (isConnectedState && !isThisConnected) 0.4f else 1f
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        EmptyDeviceCardIos(language)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        AnimatedVisibility(
            visible = showPairingToast,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = t(
                        "配对信息已失效。请打开「设置」→「蓝牙」，找到该设备，点击 ⓘ →「忽略此设备」，然后重新连接。",
                        "Pairing info is invalid. Go to Settings → Bluetooth, tap ⓘ next to the device, choose \"Forget This Device\", then reconnect.",
                        language
                    ),
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier
                        .width(280.dp)
                        .shadow(14.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.14f), ambientColor = Color.Transparent)
                        .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = showConnectTimeoutToast,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = t(
                        "连接超时。若该设备曾与此手机配对，请打开「设置」→「蓝牙」→「忽略此设备」，然后重新连接。",
                        "Connection timed out. If previously paired, go to Settings → Bluetooth → Forget This Device, then retry.",
                        language
                    ),
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier
                        .width(280.dp)
                        .shadow(14.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.14f), ambientColor = Color.Transparent)
                        .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                )
            }
        }
    }
}

private fun scanningStatusTitle(bluetoothState: Int, language: Language): String = when {
    bluetoothState == BluetoothAdapter.STATE_OFF ->
        t("等待开启蓝牙或授权...", "Waiting for Bluetooth...", language)
    bluetoothState != BluetoothAdapter.STATE_ON ->
        t("正在准备...", "Preparing...", language)
    else -> t("正在搜索设备...", "Searching for devices...", language)
}

@Composable
private fun SuccessIllustrationIos() {
    Box(
        modifier = Modifier.size(200.dp),
        contentAlignment = Alignment.Center
    ) {
        IosEchoPulseRings(color = Color(0xFF34C759))
        Box(
            modifier = Modifier
                .size(80.dp)
                .shadow(18.dp, CircleShape, spotColor = Color(0x4034C759), ambientColor = Color.Transparent)
                .clip(CircleShape)
                .background(Color(0xFF34C759)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun RadarIllustrationIos() {
    val rotation = rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rot"
    )
    Box(
        modifier = Modifier.size(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size((80 * 2.5f).dp)
                .clip(CircleShape)
                .background(Color(0x0F007AFF))
        )
        IosEchoPulseRings(color = Color(0xFF007AFF))
        Canvas(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .rotate(rotation.value)
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.sweepGradient(
                    colorStops = arrayOf(
                        0f to Color(0x38007AFF),
                        0.05f to Color(0x1A007AFF),
                        0.10f to Color(0x08007AFF),
                        0.14f to Color.Transparent,
                        1f to Color.Transparent
                    ),
                    center = c
                ),
                radius = r,
                center = c
            )
        }
        Box(
            modifier = Modifier
                .size(80.dp)
                .shadow(18.dp, CircleShape, spotColor = Color(0x40007AFF), ambientColor = Color.Transparent)
                .clip(CircleShape)
                .background(Color(0xFF007AFF)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(24.dp, 40.dp)) {
                val sx = size.width / 24f
                val sy = size.height / 40f
                val body = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = 1.5f * sx,
                            top = 1.5f * sy,
                            right = 22.5f * sx,
                            bottom = 38.5f * sy,
                            cornerRadius = CornerRadius(4.5f * sx, 4.5f * sy)
                        )
                    )
                }
                drawPath(
                    body,
                    color = Color.White,
                    style = Stroke(width = 2.5f * sx, join = StrokeJoin.Round)
                )
                val bar = Path().apply {
                    moveTo(9f * sx, 34f * sy)
                    lineTo(15f * sx, 34f * sy)
                }
                drawPath(
                    bar,
                    color = Color.White,
                    style = Stroke(width = 2.5f * sx, cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
private fun IosEchoPulseRings(color: Color) {
    Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        repeat(3) { i ->
            val inf = rememberInfiniteTransition(label = "pulse$i")
            val p by inf.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(3000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(offsetMillis = i * 1000)
                ),
                label = "p$i"
            )
            val scale = lerp(0.8f, 2.8f, p)
            val alpha = lerp(0.3f, 0f, p)
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun EmptyDeviceCardIos(language: Language) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                4.dp,
                RoundedCornerShape(16.dp),
                spotColor = Color.Black.copy(alpha = 0.04f),
                ambientColor = Color.Transparent
            )
            .background(Color.White, RoundedCornerShape(16.dp))
            .border(1.dp, Gray200, RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 12.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Gray50),
            contentAlignment = Alignment.Center
        ) {
            RadioIconIos(size = 24.dp, color = Gray300)
        }
        Text(
            text = t("暂未发现附近设备", "No nearby devices found", language),
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppTextPrimary),
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = t("请确保设备已开机并靠近手机", "Make sure the device is on and nearby", language),
            style = TextStyle(fontSize = 13.sp, color = Gray500)
        )
    }
}

@Composable
private fun BluetoothOffSectionIos(language: Language, context: android.content.Context) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = AppTextPrimary
            )
            Text(
                text = t("蓝牙无法连接搜索设备", "Bluetooth cannot connect to scan", language),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppTextPrimary)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    4.dp,
                    RoundedCornerShape(16.dp),
                    spotColor = Color.Black.copy(alpha = 0.04f),
                    ambientColor = Color.Transparent
                )
                .background(Color.White, RoundedCornerShape(16.dp))
                .border(1.dp, Gray200, RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            BtStepRowIos(
                number = "1",
                title = t("开启系统蓝牙", "Enable System Bluetooth", language),
                subtitle = t(
                    "下滑打开控制中心开启，或前往「设置」>「蓝牙」开启",
                    "Swipe down for Control Center, or go to Settings > Bluetooth",
                    language
                ),
                actionLabel = t("去开启", "Enable", language),
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(start = 32.dp)
                    .background(Gray200)
            )
            BtStepRowIos(
                number = "2",
                title = t("允许 App 访问蓝牙", "Allow App Bluetooth Access", language),
                subtitle = t(
                    "前往「设置」>「隐私与安全性」>「蓝牙」，确认本 App 已授权",
                    "Go to Settings > Privacy > Bluetooth, ensure this app is authorized",
                    language
                ),
                actionLabel = t("去授权", "Authorize", language),
                onAction = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(start = 32.dp)
                    .background(Gray200)
            )
            BtStepRowIos(
                number = "3",
                title = t("重启 App", "Restart App", language),
                subtitle = t(
                    "蓝牙已开启并授权还无法识别，可先关闭 App 再重新打开试试",
                    "If Bluetooth is on and authorized but still not working, try restarting the app",
                    language
                ),
                actionLabel = null,
                onAction = null
            )
        }
    }
}

@Composable
private fun BtStepRowIos(
    number: String,
    title: String,
    subtitle: String,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    Row(
        modifier = Modifier.padding(vertical = 16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = number,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF007AFF)),
            modifier = Modifier
                .width(22.dp)
                .padding(top = 2.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppTextPrimary),
                    modifier = Modifier.weight(1f)
                )
                if (actionLabel != null && onAction != null) {
                    Text(
                        text = actionLabel,
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF007AFF)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0x14007AFF))
                            .clickable(onClick = onAction)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
            Text(
                text = subtitle,
                style = TextStyle(fontSize = 13.sp, color = Gray500, lineHeight = 18.sp)
            )
        }
    }
}

@Composable
private fun DeviceRowIos(
    device: DiscoveredDevice,
    language: Language,
    bleManager: BleManager,
    isConnecting: Boolean,
    isConnected: Boolean,
    isFullyConnected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isFullyConnected) Color(0x4D34C759) else Gray200
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                4.dp,
                RoundedCornerShape(16.dp),
                spotColor = Color.Black.copy(alpha = 0.04f),
                ambientColor = Color.Transparent
            )
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) { bleManager.connect(device) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(if (isFullyConnected) Color(0x1A34C759) else Gray50)
            )
            RadioIconIos(
                size = 22.dp,
                color = if (isFullyConnected) Color(0xFF34C759) else Gray500
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = device.name,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppTextPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "ID: ${deviceIdShort(device.address)}",
                    style = TextStyle(fontSize = 12.sp, color = Gray400, fontFamily = FontFamily.Monospace),
                    maxLines = 1
                )
                Text(
                    text = "RSSI: ${device.rssi}",
                    style = TextStyle(fontSize = 12.sp, color = Gray400, fontFamily = FontFamily.Monospace),
                    maxLines = 1
                )
            }
        }
        when {
            isFullyConnected -> {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .shadow(2.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.05f), ambientColor = Color.Transparent)
                        .clip(CircleShape)
                        .background(Color(0xFF34C759)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.White
                    )
                }
            }
            isConnected || isConnecting -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color(0xFF007AFF),
                    strokeWidth = 2.dp
                )
            }
            else -> {
                Text(
                    text = t("连接", "Connect", language),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF007AFF)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0x14007AFF))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun RadioIconIos(size: androidx.compose.ui.unit.Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.width / 24f
        val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val cx = 12f * s
        val cy = 12f * s
        fun strokeArc(radius: Float, startDeg: Float, sweepDeg: Float) {
            val path = Path().apply {
                addArc(
                    oval = Rect(
                        cx - radius,
                        cy - radius,
                        cx + radius,
                        cy + radius
                    ),
                    startAngleDegrees = startDeg,
                    sweepAngleDegrees = sweepDeg
                )
            }
            drawPath(path, color, style = stroke)
        }
        strokeArc(6f * s, -60f, 120f)
        strokeArc(10f * s, -65f, 130f)
        strokeArc(6f * s, 120f, 120f)
        strokeArc(10f * s, 115f, 130f)
        drawCircle(color, 2f * s, Offset(cx, cy))
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ScanningBindingContentPreview() {
    CallMateTheme {
        ScanningBindingContent(
            language = Language.Zh,
            bleManager = null,
            onBound = {}
        )
    }
}

private fun deviceIdShort(address: String): String {
    val hex = address.replace(":", "").filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    if (hex.length >= 8) return hex.takeLast(8).uppercase()
    return hex.uppercase().padStart(8, '0')
}
