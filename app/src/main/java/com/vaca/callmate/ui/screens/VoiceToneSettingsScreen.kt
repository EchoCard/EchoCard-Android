package com.vaca.callmate.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.R
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSeparator
import com.vaca.callmate.ui.theme.AppShape
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppTextTertiary
import com.vaca.callmate.ui.theme.Gray500
private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

/** 与 iOS `avatarImageName(forVoiceId:voiceName:)` 对齐 */
private fun avatarResIdForVoice(id: String, name: String): Int? {
    val idLower = id.lowercase()
    return when {
        idLower.contains("taiwan") || idLower.contains("wanwan") || name == "湾湾小何" -> R.drawable.voice_avatar_boy
        idLower == "girl" || name == "邻家女孩" -> R.drawable.voice_avatar_girl
        idLower.contains("ceo") || idLower == "boss" || name == "霸道总裁" -> R.drawable.voice_avatar_boss
        idLower.contains("vivi") || name.contains("vivi") || name.contains("Vivi") -> R.drawable.voice_avatar_vivi
        else -> null
    }
}

private val CardShadow = Color.Black.copy(alpha = 0.04f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceToneSettingsScreen(
    language: Language,
    preferences: AppPreferences,
    bleManager: BleManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val vm: VoiceToneSettingsViewModel = viewModel(
        factory = VoiceToneSettingsViewModel.factory(application, preferences, bleManager)
    )

    val voices by vm.voices.collectAsState()
    val customVoices by vm.customVoices.collectAsState()
    val selectedItemId by vm.selectedItemId.collectAsState()
    val playingItemId by vm.playingItemId.collectAsState()
    val showCloneSheet by vm.showCloneSheet.collectAsState()
    val cloneStep by vm.cloneStep.collectAsState()
    val isRecording by vm.isRecording.collectAsState()
    val isSubmittingClone by vm.isSubmittingClone.collectAsState()
    val cloneStatusText by vm.cloneStatusText.collectAsState()
    val cloneCanTrain by vm.cloneCanTrain.collectAsState()
    val showUnknownAlert by vm.showUnknownCloneAlert.collectAsState()
    val isLoadingVoices by vm.isLoadingVoices.collectAsState()
    val cloneTrainingProgress by vm.cloneTrainingProgress.collectAsState()
    val cloneTrainingSuccess by vm.cloneTrainingSuccess.collectAsState()

    BackHandler(enabled = showUnknownAlert || showCloneSheet) {
        when {
            showUnknownAlert -> vm.dismissUnknownAlert()
            showCloneSheet -> vm.closeCloneSheet()
        }
    }

    var pendingOpenCloneAfterMic by remember { mutableStateOf(false) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        micGranted = ok
        if (ok && pendingOpenCloneAfterMic) {
            pendingOpenCloneAfterMic = false
            vm.openCloneSheetFromEmpty()
        } else if (!ok) {
            pendingOpenCloneAfterMic = false
        }
    }

    LaunchedEffect(language) {
        vm.onAppearSyncClone(language)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val systemItems = remember(voices, language) {
        voices.map { v ->
            VoiceListItemUi(v.id, v.name, t("在线音色", "Online Voice", language), false, null)
        }
    }
    val allRows = remember(customVoices, systemItems) {
        val seen = HashSet<String>()
        val out = ArrayList<VoiceListItemUi>()
        for (i in customVoices + systemItems) {
            if (seen.add(i.id)) out.add(i)
        }
        out
    }

    if (showCloneSheet) {
        ModalBottomSheet(
            onDismissRequest = { vm.closeCloneSheet() },
            sheetState = sheetState,
            dragHandle = null
        ) {
            when (cloneStep) {
                CloneSheetStep.Guide -> CloneGuideSheetContent(
                    language = language,
                    cloneCanTrain = cloneCanTrain,
                    onClose = { vm.closeCloneSheet() },
                    onConfirm = { vm.confirmGuideToReader(language) }
                )
                CloneSheetStep.Reader -> CloneReaderSheetContent(
                    language = language,
                    vm = vm,
                    micGranted = micGranted,
                    onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    isRecording = isRecording,
                    isSubmitting = isSubmittingClone,
                    statusText = cloneStatusText,
                    trainingProgress = cloneTrainingProgress,
                    trainingSuccess = cloneTrainingSuccess
                )
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
            IconButton(onClick = {
                vm.stopPreview()
                onBack()
            }, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBackIos,
                    contentDescription = null,
                    tint = AppTextPrimary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = t("声音", "Voice", language),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextPrimary
                )
            }
            Spacer(modifier = Modifier.size(44.dp))
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = if (customVoices.isEmpty()) 40.dp else 100.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = t("我的声音", "My Voice", language).uppercase(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Gray500,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
                )
                if (customVoices.isEmpty()) {
                    MyVoiceEmptyCard(
                        language = language,
                        cloneCanTrain = cloneCanTrain,
                        onClone = {
                            if (!micGranted) {
                                pendingOpenCloneAfterMic = true
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else vm.openCloneSheetFromEmpty()
                        }
                    )
                } else {
                    VoiceCardList(
                        items = customVoices,
                        selectedItemId = selectedItemId,
                        playingItemId = playingItemId,
                        language = language,
                        onSelect = { vm.selectItem(it, language) },
                        onPreview = { vm.togglePreview(it, language) },
                        onDisabledTap = { vm.dismissUnknownAlert(); /* alert shown */ }
                    )
                }

                Text(
                    text = t("系统音色", "System Voices", language).uppercase(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Gray500,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
                )
                if (isLoadingVoices && voices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AppPrimary)
                    }
                } else {
                    VoiceCardList(
                        items = systemItems,
                        selectedItemId = selectedItemId,
                        playingItemId = playingItemId,
                        language = language,
                        onSelect = { vm.selectItem(it, language) },
                        onPreview = { vm.togglePreview(it, language) },
                        onDisabledTap = { }
                    )
                }
            }

            if (customVoices.isNotEmpty()) {
                Button(
                    onClick = {
                        if (!micGranted) {
                            pendingOpenCloneAfterMic = true
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else vm.openCloneSheetFromEmpty()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .fillMaxWidth()
                        .shadow(10.dp, RoundedCornerShape(16.dp), spotColor = AppPrimary.copy(alpha = 0.25f), ambientColor = Color.Transparent),
                    colors = ButtonDefaults.buttonColors(containerColor = AppPrimary),
                    shape = RoundedCornerShape(16.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        t("克隆我的声音", "Clone My Voice", language),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }

    if (showUnknownAlert) {
        AlertDialog(
            onDismissRequest = { vm.dismissUnknownAlert() },
            confirmButton = {
                TextButton(onClick = { vm.dismissUnknownAlert() }) {
                    Text(t("知道了", "OK", language))
                }
            },
            title = { Text(t("该音色不可用", "Voice unavailable", language)) },
            text = { Text(t("请重新训练", "Please retrain", language)) }
        )
    }
}

@Composable
private fun MyVoiceEmptyCard(
    language: Language,
    cloneCanTrain: Boolean,
    onClone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(5.dp, AppShape.card, spotColor = CardShadow, ambientColor = Color.Transparent)
            .clip(AppShape.card)
            .background(AppSurface)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(AppTextTertiary.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Mic, contentDescription = null, tint = AppTextTertiary, modifier = Modifier.size(28.dp))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = t("暂无克隆声音", "No cloned voice yet", language),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = AppTextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = t(
                "创建专属你的AI分身声音，让沟通更具个性",
                "Create your AI voice for more personal conversations",
                language
            ),
            fontSize = 13.sp,
            color = AppTextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AppPrimary.copy(alpha = 0.1f))
                .clickable(enabled = cloneCanTrain, onClick = onClone)
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = AppPrimary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = t("克隆我的声音", "Clone My Voice", language),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppPrimary
            )
        }
    }
}

@Composable
private fun VoiceCardList(
    items: List<VoiceListItemUi>,
    selectedItemId: String,
    playingItemId: String?,
    language: Language,
    onSelect: (VoiceListItemUi) -> Unit,
    onPreview: (VoiceListItemUi) -> Unit,
    onDisabledTap: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(5.dp, AppShape.card, spotColor = CardShadow, ambientColor = Color.Transparent)
            .clip(AppShape.card)
            .background(AppSurface)
    ) {
        items.forEachIndexed { index, item ->
            val disabled = item.isCloneVoice && ((item.cloneState ?: "").trim().lowercase() == "unknown")
            VoiceRow(
                item = item,
                isSelected = selectedItemId == item.id,
                isPlaying = playingItemId == item.id,
                disabled = disabled,
                language = language,
                onSelect = {
                    if (disabled) onDisabledTap()
                    else onSelect(item)
                },
                onPreview = { if (!disabled) onPreview(item) }
            )
            if (index < items.lastIndex) {
                HorizontalDivider(color = AppSeparator, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun VoiceRow(
    item: VoiceListItemUi,
    isSelected: Boolean,
    isPlaying: Boolean,
    disabled: Boolean,
    language: Language,
    onSelect: () -> Unit,
    onPreview: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) AppPrimary.copy(alpha = 0.04f) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val avatarRes = if (!item.isCloneVoice) avatarResIdForVoice(item.id, item.name) else null
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (item.isCloneVoice) AppPrimary.copy(alpha = 0.15f)
                        else AppTextTertiary.copy(alpha = 0.08f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (avatarRes != null) {
                    Image(
                        painter = painterResource(avatarRes),
                        contentDescription = item.name,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Icon(
                        if (item.isCloneVoice) Icons.Default.Waves else Icons.Default.Person,
                        contentDescription = null,
                        tint = if (item.isCloneVoice) AppPrimary else AppTextTertiary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        item.name == "我的声音" && language == Language.En -> "My Voice"
                        else -> item.name
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) AppPrimary else AppTextPrimary
                )
                Text(
                    text = when (item.subtitle) {
                        "仅你可用" -> t("仅你可用", "Only available to you", language)
                        "训练中" -> t("训练中", "Training", language)
                        "训练失败，可重试" -> t("训练失败，可重试", "Training failed, retry", language)
                        "在线音色" -> t("在线音色", "Online Voice", language)
                        else -> item.subtitle
                    },
                    fontSize = 13.sp,
                    color = AppTextSecondary
                )
            }
        }
        if (disabled) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = AppTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onPreview, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isSelected) AppPrimary else AppTextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CloneGuideSheetContent(
    language: Language,
    cloneCanTrain: Boolean,
    onClose: () -> Unit,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .padding(bottom = 32.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = null, tint = AppTextSecondary)
            }
        }
        Text(
            t("克隆我的声音", "Clone My Voice", language),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = AppTextPrimary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        TipRow(
            t("录制自己的声音", "Record your own voice", language),
            t("若使用他人声音，请确认已获取他人授权", "If using others' voices, obtain authorization first", language)
        )
        TipRow(
            t("找一处安静的地方", "Find a quiet place", language),
            t("按照个人语气和说话习惯，自然流畅地朗读", "Read naturally with your own speaking style", language)
        )
        TipRow(
            t("严格按照文本朗读", "Follow the script strictly", language),
            t("请注意严格按照下面文本朗读", "Please read exactly as prompted", language)
        )
        if (!cloneCanTrain) {
            Text(
                t("该音色训练次数已用完（最多15次）", "Training attempts exhausted for this voice (max 15)", language),
                color = Color.Red,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onConfirm,
            enabled = cloneCanTrain,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppPrimary),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                t("确认录制", "Confirm Recording", language),
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun TipRow(title: String, subtitle: String) {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.Mic, contentDescription = null, tint = AppTextPrimary, modifier = Modifier.size(22.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, color = AppTextPrimary)
            Text(subtitle, fontSize = 14.sp, color = AppTextSecondary)
        }
    }
}

@Composable
private fun CloneReaderSheetContent(
    language: Language,
    vm: VoiceToneSettingsViewModel,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    isRecording: Boolean,
    isSubmitting: Boolean,
    statusText: String?,
    trainingProgress: Float,
    trainingSuccess: Boolean?
) {
    val script = vm.scriptForLanguage(language)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .padding(bottom = 40.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { vm.closeCloneSheet() }) {
                Icon(Icons.Default.Close, contentDescription = null, tint = AppTextSecondary)
            }
        }
        Text(
            if (isRecording) t("录音中，请朗读...", "Recording, please read...", language)
            else t("请朗读", "Please Read", language),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            script,
            fontSize = 22.sp,
            lineHeight = 30.sp,
            color = AppTextPrimary,
            modifier = Modifier.fillMaxWidth()
        )
        statusText?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = if (trainingSuccess == false) Color.Red else AppTextSecondary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(24.dp))
        when {
            isSubmitting && trainingSuccess == null -> {
                CloneTrainingProgressView(progress = trainingProgress, language = language)
            }
            trainingSuccess == true -> {
                CloneTrainingResultView(success = true, language = language)
            }
            trainingSuccess == false -> {
                CloneTrainingResultView(success = false, statusText = statusText, language = language)
            }
            else -> {
                HoldToRecordArea(
                    language = language,
                    micGranted = micGranted,
                    onRequestMic = onRequestMic,
                    isRecording = isRecording,
                    isSubmitting = isSubmitting,
                    onStart = { vm.startRecording() },
                    onCancel = { vm.stopRecorderDiscard() },
                    onEnd = { vm.stopRecordingAndSubmit(language) }
                )
            }
        }
    }
}

@Composable
private fun CloneTrainingProgressView(progress: Float, language: Language) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppBackgroundSecondary)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Waves,
                contentDescription = null,
                tint = AppPrimary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                t("声音训练中，请稍候…", "Training voice, please wait…", language),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = AppTextPrimary
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        // 渐变进度条（与 iOS 对齐）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFE5E7EB))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF007AFF), Color(0xFF34AAFF))
                        )
                    )
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${(progress * 100).toInt()}%",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = AppTextTertiary,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
private fun CloneTrainingResultView(
    success: Boolean,
    statusText: String? = null,
    language: Language
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppBackgroundSecondary)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (success) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(AppSuccess.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = AppSuccess,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                t("训练完成，已切换到我的声音", "Training complete, switched to My Voice", language),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppSuccess
            )
        } else {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                tint = Color.Red,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = statusText ?: t("训练失败，请稍后重试", "Training failed, please retry later", language),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.Red
            )
        }
    }
}

@Composable
private fun HoldToRecordArea(
    language: Language,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    isRecording: Boolean,
    isSubmitting: Boolean,
    onStart: () -> Boolean,
    onCancel: () -> Unit,
    onEnd: () -> Unit
) {
    var isCancelling by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isSubmitting) AppPrimary.copy(alpha = 0.85f)
                else AppBackgroundSecondary
            )
            .pointerInput(micGranted, isSubmitting) {
                if (isSubmitting) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!micGranted) {
                        onRequestMic()
                        return@awaitEachGesture
                    }
                    if (!onStart()) return@awaitEachGesture
                    isCancelling = false
                    val startY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = event.changes.any { it.pressed }
                        if (!pressed) break
                        val y = event.changes.firstOrNull()?.position?.y ?: startY
                        isCancelling = y < startY - 60f
                    }
                    val discard = isCancelling
                    isCancelling = false
                    if (discard) onCancel() else onEnd()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            isSubmitting -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        t("训练中，请稍候...", "Training in progress...", language),
                        color = Color.White
                    )
                }
            }
            isRecording -> {
                Text(
                    if (isCancelling) {
                        t("松手取消", "Release to cancel", language)
                    } else {
                        t("松手发送，上移取消", "Release to send, swipe up to cancel", language)
                    },
                    color = if (isCancelling) Color.Red else AppTextPrimary
                )
            }
            else -> {
                Text(
                    t("按住录制", "Hold to Record", language),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextPrimary
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun VoiceToneSettingsScreenPreview() {
    com.vaca.callmate.ui.preview.PreviewUnavailablePlaceholder("VoiceToneSettingsScreen")
}
