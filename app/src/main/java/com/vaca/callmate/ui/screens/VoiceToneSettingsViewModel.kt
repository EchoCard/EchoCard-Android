package com.vaca.callmate.ui.screens

import android.annotation.SuppressLint
import android.app.Application
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaca.callmate.core.audio.TTSFillerSyncCoordinator
import com.vaca.callmate.core.audio.VoiceCloneAudioUtils
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.network.BackendAuthManager
import com.vaca.callmate.core.network.SettingsVoiceRepository
import com.vaca.callmate.core.network.TtsVoiceDto
import com.vaca.callmate.core.network.VoiceCloneInfoDto
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private const val TAG = "VoiceToneVM"

data class VoiceListItemUi(
    val id: String,
    val name: String,
    val subtitle: String,
    val isCloneVoice: Boolean,
    val cloneState: String?
)

enum class CloneSheetStep { Guide, Reader }

class VoiceToneSettingsViewModel(
    application: Application,
    private val preferences: AppPreferences,
    private val bleManager: BleManager
) : AndroidViewModel(application) {

    private val _voices = MutableStateFlow<List<TtsVoiceDto>>(emptyList())
    val voices: StateFlow<List<TtsVoiceDto>> = _voices.asStateFlow()

    private val _customVoices = MutableStateFlow<List<VoiceListItemUi>>(emptyList())
    val customVoices: StateFlow<List<VoiceListItemUi>> = _customVoices.asStateFlow()

    private val _selectedItemId = MutableStateFlow("")
    val selectedItemId: StateFlow<String> = _selectedItemId.asStateFlow()

    private val _playingItemId = MutableStateFlow<String?>(null)
    val playingItemId: StateFlow<String?> = _playingItemId.asStateFlow()

    private val _showCloneSheet = MutableStateFlow(false)
    val showCloneSheet: StateFlow<Boolean> = _showCloneSheet.asStateFlow()

    private val _cloneStep = MutableStateFlow(CloneSheetStep.Guide)
    val cloneStep: StateFlow<CloneSheetStep> = _cloneStep.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isSubmittingClone = MutableStateFlow(false)
    val isSubmittingClone: StateFlow<Boolean> = _isSubmittingClone.asStateFlow()

    private val _cloneStatusText = MutableStateFlow<String?>(null)
    val cloneStatusText: StateFlow<String?> = _cloneStatusText.asStateFlow()

    private val _cloneCanTrain = MutableStateFlow(true)
    val cloneCanTrain: StateFlow<Boolean> = _cloneCanTrain.asStateFlow()

    private val _cloneDemoAudioUrl = MutableStateFlow<String?>(null)
    val cloneDemoAudioUrl: StateFlow<String?> = _cloneDemoAudioUrl.asStateFlow()

    private val _showUnknownCloneAlert = MutableStateFlow(false)
    val showUnknownCloneAlert: StateFlow<Boolean> = _showUnknownCloneAlert.asStateFlow()

    private val _isLoadingVoices = MutableStateFlow(false)
    val isLoadingVoices: StateFlow<Boolean> = _isLoadingVoices.asStateFlow()

    private val _cloneTrainingProgress = MutableStateFlow(0f)
    val cloneTrainingProgress: StateFlow<Float> = _cloneTrainingProgress.asStateFlow()

    private val _cloneTrainingSuccess = MutableStateFlow<Boolean?>(null)
    val cloneTrainingSuccess: StateFlow<Boolean?> = _cloneTrainingSuccess.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null

    val scriptTextZh =
        "福字要倒着贴，寓意福到，希望所有人新的一年福气满满，开开心心的。"
    val scriptTextEn =
        "Read naturally: Wishing everyone happiness and good luck in the new year."

    fun scriptForLanguage(language: Language): String =
        if (language == Language.Zh) scriptTextZh else scriptTextEn

    init {
        viewModelScope.launch {
            loadVoices()
            loadBoundCloneVoiceIfNeeded()
            refreshCloneStatusForCloneEntry()
            bootstrapSelection()
        }
    }

    private suspend fun runtimeDeviceId(): String? {
        val id = bleManager.runtimeMCUDeviceID.first()?.trim().orEmpty()
        return id.takeIf { it.isNotEmpty() }
    }

    private suspend fun runtimeBluetoothId(): String =
        preferences.getOrCreateRuntimeBluetoothId()

    fun dismissUnknownAlert() {
        _showUnknownCloneAlert.value = false
    }

    fun closeCloneSheet() {
        _showCloneSheet.value = false
        _cloneStep.value = CloneSheetStep.Guide
        _cloneTrainingProgress.value = 0f
        _cloneTrainingSuccess.value = null
        discardRecording()
    }

    fun openCloneSheetFromEmpty() {
        _cloneStep.value = CloneSheetStep.Guide
        _showCloneSheet.value = true
    }

    fun confirmGuideToReader(language: Language) {
        if (!_cloneCanTrain.value) {
            _cloneStatusText.value =
                if (language == Language.Zh) "该音色已无可用训练次数" else "No training attempts left for this voice"
            return
        }
        _cloneStep.value = CloneSheetStep.Reader
    }

    private suspend fun loadVoices() {
        _isLoadingVoices.value = true
        try {
            val token = BackendAuthManager.ensureToken(preferences) ?: return
            if (!BackendAuthManager.looksLikeJWT(token)) return
            val list = SettingsVoiceRepository.fetchVoices(token)
            _voices.value = list
            val currentId = preferences.voiceIdFlow.first()
            if (currentId.isEmpty() && list.isNotEmpty()) {
                preferences.setVoiceId(list.first().id)
                _selectedItemId.value = list.first().id
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadVoices: ${e.message}")
        } finally {
            _isLoadingVoices.value = false
        }
    }

    private suspend fun loadBoundCloneVoiceIfNeeded() {
        val token = BackendAuthManager.ensureToken(preferences) ?: return
        val deviceId = runtimeDeviceId() ?: return
        try {
            val resp = SettingsVoiceRepository.fetchBoundCloneVoice(deviceId, token)
            val info = resp.voiceClone ?: return
            upsertCloneVoiceItem(info)
            if (!info.demoAudio.isNullOrBlank()) {
                _cloneDemoAudioUrl.value = info.demoAudio
            }
            val manual = preferences.userManuallySelectedVoiceFlow.first()
            val disp = preferences.voiceDisplayNameOverrideFlow.first()
            val isMyVoiceLabel = disp == "我的声音" || disp == "My Voice"
            if (!manual && !isUnknownCloneState(info.state) &&
                (preferences.voiceIdFlow.first() == info.speakerId || isMyVoiceLabel)
            ) {
                preferences.setVoiceId(info.speakerId)
                preferences.setVoiceDisplayNameOverride(if (disp == "My Voice") "My Voice" else "我的声音")
                _selectedItemId.value = info.speakerId
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadBoundClone: ${e.message}")
        }
    }

    private suspend fun refreshCloneStatusForCloneEntry() {
        _cloneStatusText.value = null
        val token = BackendAuthManager.ensureToken(preferences)
        if (token == null || !BackendAuthManager.looksLikeJWT(token)) {
            _cloneCanTrain.value = true
            return
        }
        try {
            val deviceId = runtimeDeviceId()
            if (deviceId == null) {
                _cloneCanTrain.value = false
                _cloneStatusText.value = "Please connect EchoCard first"
                return
            }
            val bound = SettingsVoiceRepository.fetchBoundCloneVoice(deviceId, token)
            val info = bound.voiceClone
            if (info == null) {
                _cloneCanTrain.value = true
                return
            }
            upsertCloneVoiceItem(info)
            if (!info.demoAudio.isNullOrBlank()) {
                _cloneDemoAudioUrl.value = info.demoAudio
            }
            val status = SettingsVoiceRepository.queryCloneStatus(token, deviceId, info.speakerId)
            upsertCloneVoiceItem(
                VoiceCloneInfoDto(
                    speakerId = status.speakerId,
                    state = status.state,
                    trainFailedReason = status.trainFailedReason,
                    demoAudio = status.demoAudio,
                    expireTime = null,
                    canTrain = status.canTrain
                )
            )
            if (!status.demoAudio.isNullOrBlank()) {
                _cloneDemoAudioUrl.value = status.demoAudio
            }
            val can = status.canTrain ?: true
            _cloneCanTrain.value = can
            if (!can) {
                _cloneStatusText.value =
                    "Training attempts exhausted for this voice (max 15)"
            }
        } catch (e: Exception) {
            _cloneCanTrain.value = true
            Log.w(TAG, "refreshCloneStatus: ${e.message}")
        }
    }

    private fun upsertCloneVoiceItem(info: VoiceCloneInfoDto) {
        val subtitle = cloneSubtitleForState(info.state)
        val item = VoiceListItemUi(
            id = info.speakerId,
            name = "我的声音",
            subtitle = subtitle,
            isCloneVoice = true,
            cloneState = info.state
        )
        _customVoices.update { list ->
            val idx = list.indexOfFirst { it.id == info.speakerId }
            if (idx >= 0) {
                list.toMutableList().apply { this[idx] = item }
            } else {
                listOf(item) + list
            }
        }
        normalizeSelectionIfNeeded()
    }

    private fun cloneSubtitleForState(state: String?): String {
        val s = (state ?: "").lowercase()
        return when (s) {
            "success" -> "仅你可用"
            "training" -> "训练中"
            "failed" -> "训练失败，可重试"
            else -> "仅你可用"
        }
    }

    private fun isUnknownCloneState(state: String?): Boolean =
        (state ?: "").trim().lowercase() == "unknown"

    private fun isVoiceItemDisabled(item: VoiceListItemUi): Boolean =
        item.isCloneVoice && isUnknownCloneState(item.cloneState)

    private fun normalizeSelectionIfNeeded() {
        val currentId = _selectedItemId.value
        val all = mergedItems()
        val current = all.firstOrNull { it.id == currentId } ?: return
        if (!isVoiceItemDisabled(current)) return
        val fallback = all.firstOrNull { !isVoiceItemDisabled(it) } ?: return
        _selectedItemId.value = fallback.id
        viewModelScope.launch {
            if (fallback.isCloneVoice) {
                preferences.setVoiceDisplayNameOverride("我的声音")
                preferences.setVoiceId(fallback.id)
            } else {
                preferences.setVoiceDisplayNameOverride("")
                preferences.setVoiceId(fallback.id)
            }
        }
    }

    private fun mergedItems(): List<VoiceListItemUi> {
        val sys = _voices.value.map { v ->
            VoiceListItemUi(v.id, v.name, "在线音色", false, null)
        }
        val custom = _customVoices.value
        val seen = HashSet<String>()
        val out = ArrayList<VoiceListItemUi>()
        for (i in custom + sys) {
            if (seen.add(i.id)) out.add(i)
        }
        return out
    }

    private suspend fun bootstrapSelection() {
        val displayOverride = preferences.voiceDisplayNameOverrideFlow.first()
        val voiceId = preferences.voiceIdFlow.first()
        if (displayOverride.isNotBlank()) {
            val mine = _customVoices.value.firstOrNull { it.name == displayOverride }
            if (mine != null) {
                _selectedItemId.value = mine.id
                return
            }
        }
        if (voiceId.isNotEmpty() && mergedItems().any { it.id == voiceId }) {
            _selectedItemId.value = voiceId
            return
        }
        if (voiceId.isNotEmpty()) {
            val mine = VoiceListItemUi(
                id = voiceId,
                name = if (displayOverride.isBlank()) "我的声音" else displayOverride,
                subtitle = "仅你可用",
                isCloneVoice = true,
                cloneState = null
            )
            _customVoices.value = listOf(mine)
            _selectedItemId.value = mine.id
            return
        }
        val first = _voices.value.firstOrNull()
        if (first != null) {
            _selectedItemId.value = first.id
            preferences.setVoiceId(first.id)
        }
    }

    fun selectItem(item: VoiceListItemUi, language: Language) {
        if (isVoiceItemDisabled(item)) {
            _showUnknownCloneAlert.value = true
            return
        }
        _selectedItemId.value = item.id
        viewModelScope.launch {
            preferences.setUserManuallySelectedVoice(true)
            if (item.isCloneVoice) {
                preferences.setVoiceDisplayNameOverride(tMyVoice(language))
                preferences.setVoiceId(item.id)
            } else {
                preferences.setVoiceDisplayNameOverride("")
                preferences.setVoiceId(item.id)
            }
            triggerFillerPreloadIfPossible(item.id)
        }
    }

    /**
     * 与 iOS `SettingsVoiceToneSheet.triggerFillerPreloadIfPossible(voiceId:)` 对齐：
     * 已连接 + preload 通道就绪时，把这批 filler 预烧到 MCU。协调器内部做 coalesce/hash 短路。
     */
    private suspend fun triggerFillerPreloadIfPossible(voiceId: String) {
        val deviceId = runtimeDeviceId() ?: return
        if (!bleManager.isPreloadReady()) {
            Log.i(TAG, "filler preload skipped: preload char not ready")
            return
        }
        Log.i(TAG, "filler preload trigger voice=$voiceId device=$deviceId")
        TTSFillerSyncCoordinator.preload(voiceId, deviceId)
    }

    private fun tMyVoice(language: Language): String =
        if (language == Language.Zh) "我的声音" else "My Voice"

    fun togglePreview(item: VoiceListItemUi, language: Language) {
        if (_playingItemId.value == item.id) {
            stopPreview()
            return
        }
        stopPreview()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when {
                    item.isCloneVoice -> {
                        val url = _cloneDemoAudioUrl.value
                        if (!url.isNullOrBlank()) {
                            playUrl(url, item.id)
                        } else {
                            playBuiltinTts(item.id, language)
                        }
                    }
                    else -> {
                        val v = _voices.value.firstOrNull { it.id == item.id }
                        val url = v?.demoURL
                        if (!url.isNullOrBlank()) {
                            playUrl(url, item.id)
                        } else {
                            playBuiltinTts(item.id, language)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "preview: ${e.message}")
            }
        }
    }

    private fun playUrl(url: String, itemId: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                setOnCompletionListener {
                    _playingItemId.value = null
                    release()
                    mediaPlayer = null
                }
                prepare()
                start()
                _playingItemId.value = itemId
            }
        } catch (e: Exception) {
            Log.w(TAG, "playUrl: ${e.message}")
        }
    }

    private fun playBuiltinTts(itemId: String, language: Language) {
        viewModelScope.launch(Dispatchers.Main) {
            _playingItemId.value = itemId
            val app = getApplication<Application>()
            if (tts == null) {
                tts = TextToSpeech(app) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        speakBuiltinNow(itemId, language)
                    } else {
                        _playingItemId.value = null
                    }
                }
            } else {
                speakBuiltinNow(itemId, language)
            }
        }
    }

    private fun speakBuiltinNow(itemId: String, language: Language) {
        val t = tts ?: run {
            _playingItemId.value = null
            return
        }
        val locale = if (language == Language.Zh) Locale.CHINESE else Locale.US
        val langOk = t.setLanguage(locale)
        if (langOk == TextToSpeech.LANG_MISSING_DATA || langOk == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.getDefault())
        }
        val sample =
            if (language == Language.Zh) "你好，这是音色预览。" else "Hello, this is a voice preview."
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (_playingItemId.value == itemId) _playingItemId.value = null
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (_playingItemId.value == itemId) _playingItemId.value = null
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (_playingItemId.value == itemId) _playingItemId.value = null
            }
        })
        val utteranceId = "vt_preview_$itemId"
        val r = t.speak(sample, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (r == TextToSpeech.ERROR) {
            _playingItemId.value = null
        }
    }

    fun stopPreview() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        mediaPlayer?.release()
        mediaPlayer = null
        _playingItemId.value = null
    }

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        _cloneStatusText.value = null
        return try {
            discardRecording()
            val file = File(getApplication<Application>().cacheDir, "voice_clone_recording.m4a")
            recordingFile = file
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(getApplication())
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
            recorder = r
            _isRecording.value = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "startRecording: ${e.message}")
            _cloneStatusText.value = "Failed to start recording"
            false
        }
    }

    fun stopRecorderDiscard() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder?.release()
        recorder = null
        recordingFile?.delete()
        recordingFile = null
        _isRecording.value = false
    }

    private fun discardRecording() {
        stopRecorderDiscard()
    }

    fun stopRecordingAndSubmit(language: Language) {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder?.release()
        recorder = null
        _isRecording.value = false
        val file = recordingFile
        recordingFile = null
        if (file == null || !file.exists()) {
            _cloneStatusText.value = if (language == Language.Zh) "未获取到录音文件" else "No recording captured"
            return
        }
        when (VoiceCloneAudioUtils.validateTrainingSample(file)) {
            VoiceCloneAudioUtils.TrainingSampleValidation.Valid -> Unit
            VoiceCloneAudioUtils.TrainingSampleValidation.TooShort -> {
                _cloneStatusText.value =
                    if (language == Language.Zh) "提交的语音不能低于3秒" else "Voice sample must be at least 3 seconds"
                file.delete()
                return
            }
            VoiceCloneAudioUtils.TrainingSampleValidation.Silent -> {
                _cloneStatusText.value =
                    if (language == Language.Zh) "提交的语音必须要有声音" else "Voice sample must contain audible sound"
                file.delete()
                return
            }
        }
        viewModelScope.launch {
            submitCloneTraining(file, language)
        }
    }

    private suspend fun syncBoundCloneVoiceIfNeeded(language: Language) {
        val manual = preferences.userManuallySelectedVoiceFlow.first()
        if (manual) return
        val token = BackendAuthManager.ensureToken(preferences) ?: return
        val wsDeviceId = runtimeDeviceId() ?: return
        try {
            val resp = SettingsVoiceRepository.fetchBoundCloneVoice(wsDeviceId, token)
            val clone = resp.voiceClone ?: return
            if (isUnknownCloneState(clone.state)) {
                val fallback = _voices.value.firstOrNull { it.id != clone.speakerId }?.id
                    ?: _voices.value.firstOrNull()?.id
                if (fallback != null) {
                    preferences.setVoiceId(fallback)
                    preferences.setVoiceDisplayNameOverride("")
                }
                return
            }
            preferences.setVoiceId(clone.speakerId)
            preferences.setVoiceDisplayNameOverride(tMyVoice(language))
        } catch (_: Exception) {
        }
    }

    fun onAppearSyncClone(language: Language) {
        viewModelScope.launch { syncBoundCloneVoiceIfNeeded(language) }
    }

    private suspend fun submitCloneTraining(audioFile: File, language: Language) {
        _isSubmittingClone.value = true
        _cloneTrainingProgress.value = 0.05f
        _cloneTrainingSuccess.value = null
        _cloneStatusText.value = if (language == Language.Zh) "提交训练中..." else "Submitting training..."
        try {
            val token = BackendAuthManager.ensureToken(preferences)
            if (token == null || !BackendAuthManager.looksLikeJWT(token)) {
                _cloneStatusText.value = if (language == Language.Zh) "获取 token 失败" else "Failed to get token"
                return
            }
            val deviceId = runtimeDeviceId()
            if (deviceId == null) {
                _cloneStatusText.value = if (language == Language.Zh) "请先连接 EchoCard" else "Please connect EchoCard first"
                return
            }
            val bluetoothId = runtimeBluetoothId()
            try {
                BackendAuthManager.reportDevice(preferences, deviceId, bluetoothId, token)
            } catch (_: Exception) {
            }
            val bound = SettingsVoiceRepository.fetchBoundCloneVoice(deviceId, token)
            val speakerId = bound.voiceClone?.speakerId ?: deviceId
            val text = scriptForLanguage(language)
            _cloneTrainingProgress.value = 0.15f
            val train = SettingsVoiceRepository.trainClone(token, deviceId, speakerId, text, audioFile)
            upsertCloneVoiceItem(
                VoiceCloneInfoDto(
                    speakerId = train.speakerId,
                    state = train.state,
                    trainFailedReason = null,
                    demoAudio = null,
                    expireTime = null,
                    canTrain = null
                )
            )
            _cloneStatusText.value = if (language == Language.Zh) "训练任务已提交，正在查询状态..." else "Training submitted, polling status..."
            val status = SettingsVoiceRepository.pollCloneStatus(token, deviceId, train.speakerId) { a, m ->
                _cloneTrainingProgress.value = (a.toFloat() / m.toFloat()).coerceIn(0.15f, 0.95f)
                _cloneStatusText.value =
                    if (language == Language.Zh) "训练中（$a/$m）" else "Training ($a/$m)"
            }
            _cloneCanTrain.value = status.canTrain ?: true
            if (status.state?.lowercase() == "success") {
                if (!status.demoAudio.isNullOrBlank()) {
                    _cloneDemoAudioUrl.value = status.demoAudio
                }
                _selectedItemId.value = status.speakerId
                preferences.setVoiceId(status.speakerId)
                preferences.setVoiceDisplayNameOverride(tMyVoice(language))
                preferences.setUserManuallySelectedVoice(true)
                _cloneTrainingProgress.value = 1f
                _cloneTrainingSuccess.value = true
                _cloneStatusText.value =
                    if (language == Language.Zh) "训练成功，已切换到我的声音" else "Training successful, switched to My Voice"
                // 与 iOS 对齐：训练成功后显示 2 秒成功状态再自动关闭 sheet
                delay(2000)
                _showCloneSheet.value = false
                _cloneTrainingSuccess.value = null
                // 与 iOS `pollCloneStatus` 成功分支：拉完 clone 的 filler 预加载到 MCU。
                triggerFillerPreloadIfPossible(status.speakerId)
            } else {
                _cloneTrainingSuccess.value = false
                val reason = status.trainFailedReason
                    ?: if (language == Language.Zh) "请稍后重试" else "Please retry later"
                _cloneStatusText.value =
                    (if (language == Language.Zh) "训练失败：" else "Training failed: ") + reason
            }
        } catch (e: Exception) {
            Log.w(TAG, "submitClone: ${e.message}")
            _cloneTrainingSuccess.value = false
            _cloneStatusText.value = if (language == Language.Zh) "训练请求失败" else "Training request failed"
        } finally {
            _isSubmittingClone.value = false
            try {
                audioFile.delete()
            } catch (_: Exception) {
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPreview()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        discardRecording()
    }

    companion object {
        fun factory(
            application: Application,
            preferences: AppPreferences,
            bleManager: BleManager
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return VoiceToneSettingsViewModel(application, preferences, bleManager) as T
            }
        }
    }
}
