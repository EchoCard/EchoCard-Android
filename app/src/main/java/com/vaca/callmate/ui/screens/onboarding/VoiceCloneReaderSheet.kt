package com.vaca.callmate.ui.screens.onboarding

import android.annotation.SuppressLint
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.core.audio.TTSFillerSyncCoordinator
import com.vaca.callmate.core.audio.VoiceCloneAudioUtils
import com.vaca.callmate.core.network.BackendAuthManager
import com.vaca.callmate.core.network.SettingsVoiceRepository
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.screens.chat.VoiceRecordingOverlay
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "VoiceCloneReaderSheet"
private const val DELAYED_APPLY_MS = 10_000L
private val onboardingCloneApplyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private data class CloneTrainingResult(
    val success: Boolean,
    val statusText: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCloneReaderBottomSheet(
    visible: Boolean,
    language: Language,
    speakerId: String?,
    deviceId: String,
    bluetoothId: String,
    preferences: AppPreferences,
    onDismiss: () -> Unit,
    onCloneSuccess: () -> Unit,
    onRecordingChanged: ((Boolean) -> Unit)? = null,
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        VoiceCloneReaderSheetContent(
            language = language,
            speakerId = speakerId,
            deviceId = deviceId,
            bluetoothId = bluetoothId,
            preferences = preferences,
            onRecordingChanged = onRecordingChanged,
            onSuccess = {
                onCloneSuccess()
                onDismiss()
            },
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
fun VoiceCloneReaderSheetContent(
    language: Language,
    speakerId: String?,
    deviceId: String,
    bluetoothId: String,
    preferences: AppPreferences,
    onRecordingChanged: ((Boolean) -> Unit)? = null,
    onSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val script = remember(language) {
        t(
            "福字要倒着贴，寓意福到，希望所有人新的一年福气满满，开开心心的。",
            "Read naturally: Wishing everyone happiness and good luck in the new year.",
            language
        )
    }

    var isRecording by remember { mutableStateOf(false) }
    var isCancelling by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var success by remember { mutableStateOf<Boolean?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }

    val recorderHolder = remember { object { var r: MediaRecorder? = null; var file: File? = null } }

    fun stopRecorder(discard: Boolean) {
        try {
            recorderHolder.r?.stop()
        } catch (_: Exception) {
        }
        try {
            recorderHolder.r?.release()
        } catch (_: Exception) {
        }
        recorderHolder.r = null
        if (discard) {
            recorderHolder.file?.delete()
            recorderHolder.file = null
        }
        isRecording = false
    }

    fun startRecording(): Boolean {
        statusText = null
        return try {
            stopRecorder(discard = true)
            val file = File(context.cacheDir, "onboarding_voice_clone.m4a")
            recorderHolder.file = file
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(128000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorderHolder.r = r
            isRecording = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "startRecording: ${e.message}")
            statusText = t("录音启动失败", "Failed to start recording", language)
            false
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = t("声音克隆", "Voice Clone", language),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary
            )
            Text(
                text = t("请朗读以下文字", "Please read the text below", language),
                fontSize = 13.sp,
                color = AppTextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )
            Text(
                text = script,
                fontSize = 18.sp,
                lineHeight = 26.sp,
                color = AppTextPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTextSecondary.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(20.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            statusText?.let {
                Text(text = it, fontSize = 13.sp, color = AppPrimary)
            }

            when {
                isSubmitting && success == null -> {
                    CloneTrainingProgressView(progress = progress, language = language)
                }
                success == true -> {
                    CloneTrainingResultView(success = true, language = language)
                }
                success == false -> {
                    CloneTrainingResultView(success = false, statusText = statusText, language = language)
                }
                else -> {
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(isSubmitting, success) {
                                if (isSubmitting || success != null) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    onRecordingChanged?.invoke(true)
                                    isCancelling = false
                                    if (!startRecording()) {
                                        onRecordingChanged?.invoke(false)
                                        return@awaitEachGesture
                                    }
                                    val startY = down.position.y
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                        val pressed = event.changes.any { it.pressed }
                                        if (!pressed) break
                                        val y = event.changes.firstOrNull()?.position?.y ?: startY
                                        if (y < startY - 60f) {
                                            isCancelling = true
                                        }
                                    }
                                    onRecordingChanged?.invoke(false)
                                    val discard = isCancelling
                                    isCancelling = false
                                    stopRecorder(discard = discard)
                                    val file = recorderHolder.file
                                    recorderHolder.file = null
                                    if (discard) {
                                        file?.delete()
                                        return@awaitEachGesture
                                    }
                                    if (file == null || !file.exists()) {
                                        statusText = t("未获取到录音文件", "No recording captured", language)
                                        return@awaitEachGesture
                                    }
                                    when (VoiceCloneAudioUtils.validateTrainingSample(file)) {
                                        VoiceCloneAudioUtils.TrainingSampleValidation.Valid -> Unit
                                        VoiceCloneAudioUtils.TrainingSampleValidation.TooShort -> {
                                            statusText =
                                                t("提交的语音不能低于3秒", "Voice sample must be at least 3 seconds", language)
                                            file.delete()
                                            return@awaitEachGesture
                                        }
                                        VoiceCloneAudioUtils.TrainingSampleValidation.Silent -> {
                                            statusText =
                                                t("提交的语音必须要有声音", "Voice sample must contain audible sound", language)
                                            file.delete()
                                            return@awaitEachGesture
                                        }
                                    }
                                    isSubmitting = true
                                    progress = 0.05f
                                    scope.launch {
                                        val result = submitTraining(
                                            language = language,
                                            file = file,
                                            deviceId = deviceId,
                                            bluetoothId = bluetoothId,
                                            speakerId = speakerId,
                                            script = script,
                                            preferences = preferences,
                                            onProgress = { p -> progress = p },
                                        )
                                        success = result.success
                                        statusText = result.statusText
                                        isSubmitting = false
                                        if (result.success) {
                                            delay(2000)
                                            onSuccess()
                                        }
                                    }
                                }
                            }
                            .background(AppPrimary, RoundedCornerShape(16.dp))
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = t("按住录制", "Hold to Record", language),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = t("建议在安静环境录制", "Record in a quiet place", language),
                        fontSize = 13.sp,
                        color = AppTextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
        }

        if (isRecording) {
            VoiceRecordingOverlay(
                language = language,
                isCancelling = isCancelling,
                modifier = Modifier
                    .matchParentSize()
                    .fillMaxSize()
            )
        }
    }
}

@Composable
private fun CloneTrainingProgressView(progress: Float, language: Language) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppTextSecondary.copy(alpha = 0.08f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Waves, contentDescription = null, tint = AppPrimary, modifier = Modifier.size(18.dp))
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
            color = AppTextSecondary
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
            .padding(top = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppTextSecondary.copy(alpha = 0.08f))
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
                Icon(Icons.Default.Check, contentDescription = null, tint = AppSuccess, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                t("训练完成", "Training Complete", language),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppSuccess
            )
        } else {
            Icon(Icons.Default.Close, contentDescription = null, tint = Color.Red, modifier = Modifier.size(28.dp))
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

private suspend fun submitTraining(
    language: Language,
    file: File,
    deviceId: String,
    bluetoothId: String,
    speakerId: String?,
    script: String,
    preferences: AppPreferences,
    onProgress: (Float) -> Unit,
): CloneTrainingResult {
    val token = BackendAuthManager.ensureToken(preferences)
    if (token == null || !BackendAuthManager.looksLikeJWT(token)) {
        return CloneTrainingResult(success = true, statusText = null)
    }
    try {
        BackendAuthManager.reportDevice(preferences, deviceId, bluetoothId, token)
    } catch (_: Exception) {
    }
    val resolvedSpeaker = speakerId?.trim()?.takeIf { it.isNotEmpty() } ?: deviceId
    return try {
        withContext(Dispatchers.Main) { onProgress(0.1f) }
        val train = withContext(Dispatchers.IO) {
            SettingsVoiceRepository.trainClone(token, deviceId, resolvedSpeaker, script, file)
        }
        withContext(Dispatchers.Main) { onProgress(0.35f) }
        val status = withContext(Dispatchers.IO) {
            SettingsVoiceRepository.pollCloneStatus(token, deviceId, train.speakerId)
        }
        withContext(Dispatchers.Main) { onProgress(1f) }
        val st = status.state?.lowercase().orEmpty()
        if (st == "success") {
            val resolvedSpeaker = status.speakerId.trim()
            if (resolvedSpeaker.isEmpty()) {
                CloneTrainingResult(
                    success = false,
                    statusText = t("训练结果无效，请重试", "Invalid training result, please retry", language)
                )
            } else {
                val displayName = t("我的声音", "My Voice", language)
                preferences.setVoiceId(resolvedSpeaker)
                preferences.setVoiceDisplayNameOverride(displayName)
                preferences.setUserManuallySelectedVoice(true)
                triggerOnboardingFillerPreload(resolvedSpeaker, deviceId, "train_success")
                scheduleApplyCloneAsDefaultVoice(preferences, language, deviceId)
                CloneTrainingResult(success = true, statusText = null)
            }
        } else {
            val reason = status.trainFailedReason
                ?: t("请稍后重试", "Please retry later", language)
            CloneTrainingResult(
                success = false,
                statusText = t("训练失败：", "Training failed: ", language) + reason
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "submitTraining", e)
        CloneTrainingResult(
            success = false,
            statusText = t("训练请求失败，请重试", "Training failed, please retry", language)
        )
    } finally {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }
}

private fun scheduleApplyCloneAsDefaultVoice(
    preferences: AppPreferences,
    language: Language,
    deviceId: String,
) {
    val trimmedDeviceId = deviceId.trim()
    if (trimmedDeviceId.isEmpty()) return
    onboardingCloneApplyScope.launch {
        val snapshotVoiceId = preferences.voiceIdFlow.first()
        val snapshotDisplay = preferences.voiceDisplayNameOverrideFlow.first()
        val snapshotManual = preferences.userManuallySelectedVoiceFlow.first()
        delay(DELAYED_APPLY_MS)
        applyDelayedCloneVoiceDefaultsUsingSettingsStyleQueries(
            preferences = preferences,
            language = language,
            deviceId = trimmedDeviceId,
            snapshotVoiceId = snapshotVoiceId,
            snapshotDisplay = snapshotDisplay,
            snapshotManual = snapshotManual,
        )
    }
}

private suspend fun applyDelayedCloneVoiceDefaultsUsingSettingsStyleQueries(
    preferences: AppPreferences,
    language: Language,
    deviceId: String,
    snapshotVoiceId: String,
    snapshotDisplay: String,
    snapshotManual: Boolean,
) {
    val stillMatches =
        preferences.voiceIdFlow.first() == snapshotVoiceId &&
            preferences.voiceDisplayNameOverrideFlow.first() == snapshotDisplay &&
            preferences.userManuallySelectedVoiceFlow.first() == snapshotManual
    if (!stillMatches) return

    val token = BackendAuthManager.ensureToken(preferences) ?: return
    if (!BackendAuthManager.looksLikeJWT(token)) return

    try {
        val bound = SettingsVoiceRepository.fetchBoundCloneVoice(deviceId, token)
        val info = bound.voiceClone ?: return
        val speakerId = info.speakerId.trim()
        if (speakerId.isEmpty()) return

        val status = SettingsVoiceRepository.queryCloneStatus(token, deviceId, speakerId)
        if (status.state?.lowercase() != "success") return

        val resolvedSpeakerId = status.speakerId.trim()
        if (resolvedSpeakerId.isEmpty()) return

        val currentStillMatches =
            preferences.voiceIdFlow.first() == snapshotVoiceId &&
                preferences.voiceDisplayNameOverrideFlow.first() == snapshotDisplay &&
                preferences.userManuallySelectedVoiceFlow.first() == snapshotManual
        if (!currentStillMatches) return

        preferences.setVoiceId(resolvedSpeakerId)
        preferences.setVoiceDisplayNameOverride(t("我的声音", "My Voice", language))
        preferences.setUserManuallySelectedVoice(true)
        triggerOnboardingFillerPreload(resolvedSpeakerId, deviceId, "delayed_apply")
    } catch (e: Exception) {
        Log.w(TAG, "delayed apply failed: ${e.message}")
    }
}

private fun triggerOnboardingFillerPreload(voiceId: String, deviceId: String, source: String) {
    val trimmedVoiceId = voiceId.trim()
    val trimmedDeviceId = deviceId.trim()
    if (trimmedVoiceId.isEmpty() || trimmedDeviceId.isEmpty()) return
    Log.i(TAG, "trigger filler preload ($source) voice=$trimmedVoiceId device=$trimmedDeviceId")
    TTSFillerSyncCoordinator.preload(trimmedVoiceId, trimmedDeviceId)
}
