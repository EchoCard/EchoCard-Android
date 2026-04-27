package com.vaca.callmate.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.vaca.callmate.core.notifications.ResidentNotificationHelper
import com.vaca.callmate.core.network.BackendAuthManager
import com.vaca.callmate.core.network.SettingsVoiceRepository
import com.vaca.callmate.core.network.TtsVoiceDto
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.update.AppSelfUpdateService
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.VoiceTone
import com.vaca.callmate.ui.theme.AppError
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppChevron
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppPrimaryLight
import com.vaca.callmate.ui.theme.AppSeparator
import com.vaca.callmate.ui.theme.AppShape
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.Gray500
import kotlinx.coroutines.launch

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private val CardShadowAmbient = Color.Black.copy(alpha = 0.04f)
private val IosBlue = Color(0xFF007AFF)
private val IosPurple = Color(0xFF5856D6)
private val IosGrayIcon = Color(0xFF9CA3AF)

private fun appVersionLabel(context: Context): String {
    return try {
        val pm = context.packageManager
        val pkg = context.packageName
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        val name = info.versionName ?: "1.0"
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "$name ($code)"
    } catch (_: Exception) {
        "1.0 (1)"
    }
}

private fun packageVersionCodeLong(context: Context): Long {
    return try {
        val pm = context.packageManager
        val pkg = context.packageName
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    } catch (_: Exception) {
        1L
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Gray500,
        letterSpacing = 1.2.sp,
        modifier = Modifier
            .padding(start = 16.dp)
            .padding(bottom = 6.dp)
    )
}

@Composable
private fun SettingsGroupedCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, AppShape.card, spotColor = CardShadowAmbient, ambientColor = Color.Transparent)
            .clip(AppShape.card)
            .background(AppSurface)
            .border(0.5.dp, Color.White.copy(alpha = 0.55f), AppShape.card)
    ) {
        content()
    }
}

@Composable
private fun SettingsRowIcon(icon: ImageVector, tint: Color, container: Color) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onTest: () -> Unit,
    onSimulationCalls: () -> Unit,
    onOpenDeviceModal: () -> Unit,
    onOpenVoiceTone: () -> Unit,
    language: Language,
    setLanguage: (Language) -> Unit,
    onOpenAiChat: () -> Unit,
    preferences: AppPreferences,
    bleManager: BleManager,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val pickupDelay by preferences.pickupDelayFlow.collectAsState(initial = 5)
    val mcuSilent by preferences.mcuSilentUpdateFlow.collectAsState(initial = true)
    val liveActivityResident by preferences.liveActivityResidentFlow.collectAsState(initial = false)
    val voiceToneRaw by preferences.voiceToneFlow.collectAsState(initial = VoiceTone.Taiwan.raw)
    val voiceDisplayOverride by preferences.voiceDisplayNameOverrideFlow.collectAsState(initial = "")
    val voiceId by preferences.voiceIdFlow.collectAsState(initial = "")
    var ttsVoices by remember { mutableStateOf<List<TtsVoiceDto>>(emptyList()) }

    val localVersionCode = remember { packageVersionCodeLong(context) }
    val remoteAppMeta by AppSelfUpdateService.remoteMeta.collectAsState(initial = null)
    val appUpdateChecking by AppSelfUpdateService.isChecking.collectAsState(initial = false)
    val appUpdateDownloading by AppSelfUpdateService.isDownloading.collectAsState(initial = false)
    val hasAppUpdate = remoteAppMeta?.let { it.versionCodeLong > localVersionCode } == true

    val btState by bleManager.bluetoothState.collectAsState()
    val isReady by bleManager.isReady.collectAsState()
    val connected by bleManager.connectedAddress.collectAsState()
    val connecting by bleManager.connectingAddress.collectAsState()

    val deviceText = settingsDeviceConnectionText(language, btState, isReady, connected, connecting)
    val deviceColor = settingsDeviceConnectionColor(btState, isReady, connected, connecting)

    /** 与 iOS `SettingsViewModel.currentVoiceLabel`：用在线音色列表解析 `voice_id`，禁止直接展示长串 ID */
    val voiceLabel = remember(voiceDisplayOverride, voiceId, voiceToneRaw, ttsVoices, language) {
        when {
            voiceDisplayOverride.isNotBlank() -> voiceDisplayOverride
            voiceId.isNotBlank() -> {
                val name = ttsVoices.firstOrNull { it.id == voiceId }?.name?.trim()?.takeIf { it.isNotEmpty() }
                name ?: VoiceTone.fromRaw(voiceToneRaw).displayName(language)
            }
            else -> VoiceTone.fromRaw(voiceToneRaw).displayName(language)
        }
    }

    LaunchedEffect(Unit) {
        if (ttsVoices.isNotEmpty()) return@LaunchedEffect
        val token = BackendAuthManager.ensureToken(preferences)
        if (token == null || !BackendAuthManager.looksLikeJWT(token)) return@LaunchedEffect
        runCatching {
            ttsVoices = SettingsVoiceRepository.fetchVoices(token)
        }
    }

    LaunchedEffect(liveActivityResident, language) {
        ResidentNotificationHelper.update(
            context = context.applicationContext,
            enabled = liveActivityResident,
            title = t("EchoCard 待机", "EchoCard standby", language),
            text = t("与 iOS 灵动岛类似：低优先级常驻提示", "Low-priority resident hint (like Dynamic Island)", language)
        )
    }

    LaunchedEffect(localVersionCode) {
        AppSelfUpdateService.checkForUpdate(localVersionCode)
    }

    var showPromptModal by remember { mutableStateOf(false) }

    BackHandler(enabled = showPromptModal) { showPromptModal = false }

    Box(modifier = modifier.fillMaxSize().background(AppBackgroundSecondary)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
                        text = t("设置", "Settings", language),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTextPrimary
                    )
                }
                Spacer(modifier = Modifier.size(44.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                SettingsGroupedCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDeviceModal() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsRowIcon(Icons.Default.Bluetooth, Color.White, IosBlue)
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = t("设备管理", "Device", language),
                            fontSize = 17.sp,
                            color = AppTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(deviceColor)
                            )
                            Text(
                                text = deviceText,
                                fontSize = 15.sp,
                                color = AppTextSecondary
                            )
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppChevron, modifier = Modifier.size(14.dp))
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsRowIcon(Icons.Default.Language, Color.White, IosPurple)
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = t("界面语言", "Language", language),
                            fontSize = 17.sp,
                            color = AppTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IosCapsuleToggle(
                            isLeft = language == Language.Zh,
                            leftLabel = "中文",
                            rightLabel = "EN",
                            onLeftClick = { setLanguage(Language.Zh) },
                            onRightClick = { setLanguage(Language.En) }
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenVoiceTone() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsRowIcon(Icons.Default.GraphicEq, Color.White, IosPurple)
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = t("AI 音色", "Voice", language),
                            fontSize = 17.sp,
                            color = AppTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = voiceLabel,
                            fontSize = 15.sp,
                            color = AppTextSecondary,
                            maxLines = 1
                        )
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppChevron, modifier = Modifier.size(14.dp))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsRowIcon(Icons.Default.Schedule, Color.White, IosBlue)
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = t("响铃延迟", "Pickup Delay", language),
                            fontSize = 17.sp,
                            color = AppTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(AppPrimaryLight)
                                    .clickable {
                                        scope.launch {
                                            preferences.setPickupDelay((pickupDelay - 1).coerceAtLeast(1))
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = null, tint = AppPrimary, modifier = Modifier.size(18.dp))
                            }
                            Text(
                                text = "${pickupDelay}s",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppTextPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(AppPrimaryLight)
                                    .clickable {
                                        scope.launch {
                                            preferences.setPickupDelay((pickupDelay + 1).coerceAtMost(60))
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = AppPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsRowIcon(Icons.Default.ScreenShare, Color.White, IosBlue)
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = t("待机时显示灵动岛", "Show Dynamic Island in Standby", language),
                            fontSize = 17.sp,
                            color = AppTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = liveActivityResident,
                            onCheckedChange = { v -> scope.launch { preferences.setLiveActivityResident(v) } },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AppSurface,
                                checkedTrackColor = AppSuccess
                            )
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsRowIcon(Icons.Default.SystemUpdate, Color.White, IosBlue)
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = t("MCU 静默升级", "MCU Silent Update", language),
                            fontSize = 17.sp,
                            color = AppTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = mcuSilent,
                            onCheckedChange = { v -> scope.launch { preferences.setMcuSilentUpdate(v) } },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AppSurface,
                                checkedTrackColor = AppSuccess
                            )
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (hasAppUpdate) {
                                    Modifier.clickable {
                                        scope.launch {
                                            try {
                                                val apk = AppSelfUpdateService.downloadApkToCache(context.applicationContext)
                                                val started = AppSelfUpdateService.startInstallApk(context.applicationContext, apk)
                                                if (!started) {
                                                    Toast.makeText(
                                                        context,
                                                        t("请在设置中允许安装该应用", "Allow installing this app in Settings", language),
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(
                                                    context,
                                                    t("下载失败", "Download failed", language) + ": ${e.message.orEmpty()}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsRowIcon(Icons.Default.Info, Color.White, IosGrayIcon)
                        Spacer(modifier = Modifier.size(12.dp))
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = t("版本号", "Version", language),
                                fontSize = 17.sp,
                                color = AppTextPrimary
                            )
                            if (hasAppUpdate) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(AppError)
                                )
                                Text(
                                    text = t("新版本", "New version", language),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(AppError)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (appUpdateDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = AppPrimary
                                )
                            } else if (appUpdateChecking && !hasAppUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = AppTextSecondary.copy(alpha = 0.5f)
                                )
                            }
                            Text(
                                text = appVersionLabel(context),
                                fontSize = 15.sp,
                                color = AppTextSecondary
                            )
                            if (hasAppUpdate) {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = AppChevron,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Column {
                    SettingsSectionHeader(t("AI 配置", "AI Settings", language))
                    SettingsGroupedCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPromptModal = true }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingsRowIcon(
                                Icons.Default.Description,
                                tint = Color.White,
                                container = IosBlue
                            )
                            Spacer(modifier = Modifier.size(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = t("接听规则", "Call Rules", language),
                                    fontSize = 17.sp,
                                    color = AppTextPrimary
                                )
                                Text(
                                    text = t("查看完整的 AI 指令", "View full AI instructions", language),
                                    fontSize = 13.sp,
                                    color = AppTextSecondary
                                )
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppChevron, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Column {
                    SettingsSectionHeader(t("测试工具", "Testing", language))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(6.dp, AppShape.card, spotColor = CardShadowAmbient, ambientColor = Color.Transparent)
                                .clip(AppShape.card)
                                .background(AppPrimary)
                                .clickable { onTest() }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(
                                    text = t("模拟陌生来电", "Simulate Call", language),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(6.dp, AppShape.card, spotColor = CardShadowAmbient, ambientColor = Color.Transparent)
                                .clip(AppShape.card)
                                .background(AppSurface)
                                .clickable { onSimulationCalls() }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.History, contentDescription = null, tint = AppTextPrimary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(
                                    text = t("模拟测试通话记录", "Simulation Call History", language),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppTextPrimary
                                )
                            }
                        }
                    }
                    Text(
                        text = t("测试不会产生真实通话费用，仅用于验证策略", "No cost, simulation only.", language),
                        fontSize = 12.sp,
                        color = AppTextSecondary,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

    }

    if (showPromptModal) {
        PromptModal(onClose = { showPromptModal = false }, language = language)
    }
}

@Composable
private fun IosCapsuleToggle(
    isLeft: Boolean,
    leftLabel: String,
    rightLabel: String,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit
) {
    val pillOffset by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isLeft) 0.dp else 46.dp,
        animationSpec = androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "pill"
    )
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 30.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFF2F2F7))
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .offset(x = pillOffset)
                .size(width = 46.dp, height = 26.dp)
                .shadow(2.dp, RoundedCornerShape(50), spotColor = Color.Black.copy(alpha = 0.1f))
                .clip(RoundedCornerShape(50))
                .background(Color.White)
        )
        Row(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onLeftClick),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    leftLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isLeft) Color(0xFF007AFF) else Color(0xFF6B7280)
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onRightClick),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    rightLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (!isLeft) Color(0xFF007AFF) else Color(0xFF6B7280)
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SettingsScreenPreview() {
    com.vaca.callmate.ui.preview.PreviewUnavailablePlaceholder("SettingsScreen")
}
