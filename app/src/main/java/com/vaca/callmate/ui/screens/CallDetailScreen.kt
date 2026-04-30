package com.vaca.callmate.ui.screens

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.CallMateApplication
import com.vaca.callmate.core.audio.CallRecordingFiles
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.CallRecord
import com.vaca.callmate.data.ChatMessage
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.MessageSender
import com.vaca.callmate.data.repository.CallRepository
import com.vaca.callmate.features.outbound.ManualWsScene
import com.vaca.callmate.features.outbound.OutboundChatController
import com.vaca.callmate.ui.theme.AppAccent
import com.vaca.callmate.ui.theme.AppBackground
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppBorder
import com.vaca.callmate.ui.theme.AppError
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppSurfaceElevated
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppWarning
import com.vaca.callmate.ui.theme.CallMateTheme
import com.vaca.callmate.data.outbound.parseOutboundCallSummaryPayload
import com.vaca.callmate.ui.screens.outbound.OutboundCallOutcomeCard
import com.vaca.callmate.ui.preview.PreviewSamples
import com.vaca.callmate.ui.screens.chat.VoiceRecordingOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

/** 与 iOS `CallLog.displaySummary` 一致 */
private fun displaySummary(summary: String): String {
    return summary
        .replace("[OUTBOUND_TASK] ", "")
        .replace("[OUTBOUND_TASK]", "")
        .trim()
}

private fun formatTimeSeconds(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/** 与 iOS `openDialerWithNumber` 一致：仅保留可拨号字符 */
private fun dialCharsOnly(phone: String): String =
    phone.filter { it in '0'..'9' || it == '+' || it == '*' || it == '#' }

private fun hasDialablePhone(phone: String): Boolean = dialCharsOnly(phone).isNotEmpty()

/** 与 iOS `CallDetailView.feedbackInitMessages` / `feedbackOpeningText` 对齐 */
private fun buildCallDetailEvaluationInitSeed(feedbackType: String, language: Language): List<Pair<String, String>> {
    val userSeed = when (feedbackType) {
        "good" -> t("这次服务很好。", "The service was great this time.", language)
        "bad" -> t("这次服务不太好。", "The service was not good this time.", language)
        "average" -> t("这次服务一般。", "The service was average this time.", language)
        else -> t("我想补充一下这通电话的反馈。", "I want to add feedback for this call.", language)
    }
    val assistantOpening = when (feedbackType) {
        "good" -> t(
            "谢谢鼓励！你可以按住说话告诉我做得好的地方。",
            "Thanks! Hold to talk and tell me what worked well.",
            language,
        )
        "bad" -> t(
            "抱歉这次体验不好。你可以按住说话告诉我哪里需要改进。",
            "Sorry this wasn't great. Hold to talk and tell me what to improve.",
            language,
        )
        "average" -> t(
            "收到你的评价。你可以按住说话补充更多细节。",
            "Got it. Hold to talk and share more details.",
            language,
        )
        else -> t(
            "你可以按住说话，继续补充本次通话反馈。",
            "Hold to talk and share more feedback for this call.",
            language,
        )
    }
    return listOf("user" to userSeed, "assistant" to assistantOpening)
}

/** 与 iOS `evaluationChatHistory`：转写中非 AI 行 role=other */
private fun buildCallDetailEvaluationTranscript(transcript: List<ChatMessage>): List<Pair<String, String>> {
    if (transcript.isEmpty()) return emptyList()
    return transcript.mapNotNull { msg ->
        val text = msg.text.trim()
        if (text.isEmpty()) return@mapNotNull null
        val role = if (msg.sender == MessageSender.Ai) "assistant" else "other"
        role to text
    }
}

@Composable
fun CallDetailScreen(
    call: CallRecord,
    onBack: () -> Unit,
    isTest: Boolean,
    language: Language,
    modifier: Modifier = Modifier,
    callRepository: CallRepository? = null,
    repeatInboundCount: Int = 0,
    /** 非空时评价区走真实 WS + 按住说话上行 Opus（对标 iOS `scene=evaluation`） */
    bleManager: BleManager? = null,
    preferences: AppPreferences? = null,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as? CallMateApplication }
    val scope = rememberCoroutineScope()
    var currentTime by remember { mutableStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // 按 roomLogId 存状态，避免 LaunchedEffect(call) 在重组时用旧 prop 覆盖 observe 拉到的摘要
    var detailCall by remember(call.roomLogId) { mutableStateOf(call) }
    LaunchedEffect(call.roomLogId, callRepository, language) {
        val id = call.roomLogId ?: return@LaunchedEffect
        val r = callRepository ?: return@LaunchedEffect
        r.getById(id, language)?.let { detailCall = it }
        r.observeCallRecord(id, language).collect { rec ->
            if (rec != null) detailCall = rec
        }
    }

    var audioDurationSec by remember(detailCall.roomLogId, detailCall.recordingFileName) {
        mutableStateOf(detailCall.duration.coerceAtLeast(1))
    }
    var selectedFeedback by remember(call.roomLogId) { mutableStateOf<String?>(null) }
    var feedbackRecording by remember { mutableStateOf(false) }
    var feedbackCancelling by remember { mutableStateOf(false) }

    val scrollStateNoFeedback = rememberScrollState()
    val scrollStateFeedbackTop = rememberScrollState()

    val transcript = detailCall.transcript ?: emptyList()

    val feedbackOutboundChat = remember(
        detailCall.roomLogId,
        selectedFeedback,
        language,
        bleManager,
        preferences,
        callRepository,
        transcript,
        app,
    ) {
        val fb = selectedFeedback ?: return@remember null
        val ble = bleManager ?: return@remember null
        val prefs = preferences ?: return@remember null
        val repo = callRepository ?: return@remember null
        val ctxApp = app ?: return@remember null
        OutboundChatController(
            context = ctxApp,
            bleManager = ble,
            preferences = prefs,
            outboundRepository = ctxApp.outboundRepository,
            queueService = ctxApp.outboundTaskQueueService,
            language = language,
            wsScene = ManualWsScene.EVALUATION,
            avatarInitMessagesSeed = null,
            evaluationInitMessagesSeed = buildCallDetailEvaluationInitSeed(fb, language),
            evaluationTranscriptTemplate = buildCallDetailEvaluationTranscript(transcript),
        )
    }
    val headerTitle = if (isTest) t("测试报告", "Test Report", language) else detailCall.phone
    val showDial = remember(detailCall.phone) { hasDialablePhone(detailCall.phone) }
    val summaryDisplay = remember(detailCall.summary) { displaySummary(detailCall.summary) }
    val fullSummaryText = detailCall.fullSummary.trim()
    val headerCategoryText = remember(detailCall.label, isTest, language) {
        val trimmed = detailCall.label.trim()
        when {
            isTest -> t("测试通话", "Test Call", language)
            trimmed.isEmpty() -> t("通话详情", "Call Detail", language)
            else -> trimmed
        }
    }

    val strangerRepeatTagText: String? =
        if (isTest || repeatInboundCount < 2) null
        else if (repeatInboundCount == 2) t("重复", "Repeat", language)
        else t("多次", "Multiple", language)

    val repeatTagColor = if (repeatInboundCount > 2) AppError else AppPrimary

    LaunchedEffect(call.roomLogId, callRepository) {
        val id = call.roomLogId ?: return@LaunchedEffect
        val repo = callRepository ?: return@LaunchedEffect
        selectedFeedback = repo.getLatestFeedbackRating(id)
    }

    LaunchedEffect(selectedFeedback) {
        if (selectedFeedback == null) {
            feedbackRecording = false
            feedbackCancelling = false
        } else {
            delay(100)
            scrollStateFeedbackTop.scrollTo(scrollStateFeedbackTop.maxValue)
        }
    }

    DisposableEffect(detailCall.recordingFileName, detailCall.roomLogId) {
        val name = detailCall.recordingFileName
        if (name.isNullOrBlank()) {
            mediaPlayer = null
            return@DisposableEffect onDispose { }
        }
        val f = CallRecordingFiles.file(context, name)
        if (!f.exists()) {
            mediaPlayer = null
            return@DisposableEffect onDispose { }
        }
        val mp = MediaPlayer()
        mp.setDataSource(f.absolutePath)
        mp.prepare()
        mp.setOnCompletionListener {
            isPlaying = false
            currentTime = (mp.duration / 1000).coerceAtLeast(0)
        }
        mediaPlayer = mp
        audioDurationSec = (mp.duration / 1000).coerceAtLeast(1)
        onDispose {
            try {
                mp.stop()
            } catch (_: Exception) {
            }
            mp.release()
            mediaPlayer = null
        }
    }

    LaunchedEffect(isPlaying, mediaPlayer) {
        val mp = mediaPlayer ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        while (isPlaying && mp.isPlaying) {
            delay(50)
            currentTime = (mp.currentPosition / 1000).coerceIn(0, audioDurationSec)
        }
    }

    fun persistFeedback(ratingRaw: String) {
        val id = detailCall.roomLogId ?: return
        val repo = callRepository ?: return
        scope.launch {
            runCatching {
                repo.insertCallFeedback(id, ratingRaw)
            }
            selectedFeedback = ratingRaw
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(AppBackgroundSecondary)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(52.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBackIos,
                        contentDescription = null,
                        tint = AppTextPrimary,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = headerTitle,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTextPrimary,
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 200.dp)
                    )
                    if (!isTest) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.LocalOffer,
                                contentDescription = null,
                                tint = AppPrimary,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = headerCategoryText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppPrimary,
                                maxLines = 1
                            )
                            if (strangerRepeatTagText != null) {
                                Surface(
                                    color = repeatTagColor.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = strangerRepeatTagText,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = repeatTagColor,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
                if (showDial) {
                    IconButton(
                        onClick = {
                            val trimmed = dialCharsOnly(detailCall.phone)
                            if (trimmed.isNotEmpty()) {
                                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$trimmed")))
                            }
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(AppSuccess.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Call,
                                contentDescription = null,
                                tint = AppSuccess,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.size(44.dp))
                }
            }
            if (selectedFeedback == null) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollStateNoFeedback)
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                        .padding(bottom = 24.dp)
                ) {
                    CallDetailPlaybackTranscriptAnalysis(
                        call = detailCall,
                        context = context,
                        language = language,
                        transcript = transcript,
                        summaryDisplay = summaryDisplay,
                        fullSummaryText = fullSummaryText,
                        currentTime = currentTime,
                        audioDurationSec = audioDurationSec,
                        mediaPlayer = mediaPlayer,
                        isPlaying = isPlaying,
                        onSliderValueChange = { v ->
                            currentTime = v.toInt()
                            mediaPlayer?.seekTo(currentTime * 1000)
                        },
                        onPlayPauseClick = {
                            mediaPlayer?.let { mp ->
                                if (isPlaying) {
                                    mp.pause()
                                    isPlaying = false
                                } else {
                                    mp.start()
                                    isPlaying = true
                                }
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(
                                12.dp,
                                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                                spotColor = Color(0xFF64748B).copy(alpha = 0.05f),
                                ambientColor = Color.Transparent
                            )
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.88f),
                                        Color(0xFFEDF3FF).copy(alpha = 0.66f)
                                    )
                                )
                            )
                            .border(
                                0.6.dp,
                                Color.White.copy(alpha = 0.42f),
                                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                Color.White.copy(alpha = 0.9f),
                                                Color.White,
                                                Color.White.copy(alpha = 0.9f),
                                                Color.Transparent
                                            )
                                        )
                                    )
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = t("结果点评", "Call Review", language),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTextPrimary,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isTest) {
                                    t("测试结果符合预期吗？", "Does this match expectations?", language)
                                } else {
                                    t("AI 表现如何？", "How did AI perform?", language)
                                },
                                fontSize = 12.sp,
                                color = AppTextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                FeedbackChip(
                                    label = t("不好", "Bad", language),
                                    icon = { Icon(Icons.Default.ThumbDown, null, tint = AppError) },
                                    tint = AppError,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { persistFeedback("bad") }
                                )
                                FeedbackChip(
                                    label = t("一般", "Fair", language),
                                    icon = { Icon(Icons.Default.RemoveCircle, null, tint = AppWarning) },
                                    tint = AppWarning,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { persistFeedback("average") }
                                )
                                FeedbackChip(
                                    label = t("很好", "Good", language),
                                    icon = { Icon(Icons.Default.ThumbUp, null, tint = AppSuccess) },
                                    tint = AppSuccess,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { persistFeedback("good") }
                                )
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(scrollStateFeedbackTop)
                            .padding(horizontal = 16.dp, vertical = 20.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        CallDetailPlaybackTranscriptAnalysis(
                            call = detailCall,
                            context = context,
                            language = language,
                            transcript = transcript,
                            summaryDisplay = summaryDisplay,
                            fullSummaryText = fullSummaryText,
                            currentTime = currentTime,
                            audioDurationSec = audioDurationSec,
                            mediaPlayer = mediaPlayer,
                            isPlaying = isPlaying,
                            onSliderValueChange = { v ->
                                currentTime = v.toInt()
                                mediaPlayer?.seekTo(currentTime * 1000)
                            },
                            onPlayPauseClick = {
                                mediaPlayer?.let { mp ->
                                    if (isPlaying) {
                                        mp.pause()
                                        isPlaying = false
                                    } else {
                                        mp.start()
                                        isPlaying = true
                                    }
                                }
                            },
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = t("结果点评", "Call Review", language),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTextPrimary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        FeedbackChatModal(
                            onClose = { },
                            feedbackType = selectedFeedback ?: "none",
                            language = language,
                            modifier = Modifier.fillMaxSize(),
                            isEmbedded = true,
                            showCloseButton = false,
                            showInnerHeaderRow = false,
                            showMessageAvatars = false,
                            voiceOverlayInParent = true,
                            onRecordingStateChange = { feedbackRecording = it },
                            onGlassVoiceCancelStateChange = { feedbackCancelling = it },
                            outboundChat = feedbackOutboundChat,
                        )
                    }
                }
            }
        }
        if (feedbackRecording && selectedFeedback != null) {
            VoiceRecordingOverlay(
                language = language,
                isCancelling = feedbackCancelling,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun CallDetailPlaybackTranscriptAnalysis(
    call: CallRecord,
    context: Context,
    language: Language,
    transcript: List<ChatMessage>,
    summaryDisplay: String,
    fullSummaryText: String,
    currentTime: Int,
    audioDurationSec: Int,
    mediaPlayer: MediaPlayer?,
    isPlaying: Boolean,
    onSliderValueChange: (Float) -> Unit,
    onPlayPauseClick: () -> Unit,
) {
    val recName = call.recordingFileName
    val recFile = recName?.let { CallRecordingFiles.file(context, it) }
    val fileOk = recFile != null && recFile.exists()
    /** 与 iOS `CallDetailView`：有文件名或有时长即展示录音区；无文件时说明原因（模拟通话必有文件） */
    val showRecordingSection = recName != null || call.duration > 0
    val canPlay = fileOk && mediaPlayer != null

    if (showRecordingSection) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = t("通话录音", "Recording", language),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextSecondary,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                if (!fileOk) {
                    Text(
                        text = when {
                            recName != null -> t(
                                "录音文件不存在或已删除。",
                                "Recording file is missing or was removed.",
                                language
                            )
                            else -> t(
                                "本次通话未生成本地录音文件。",
                                "No local recording file was saved for this call.",
                                language
                            )
                        },
                        fontSize = 14.sp,
                        color = AppTextSecondary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                Slider(
                    value = currentTime.toFloat(),
                    onValueChange = onSliderValueChange,
                    enabled = canPlay,
                    valueRange = 0f..audioDurationSec.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = AppPrimary,
                        activeTrackColor = AppPrimary,
                        inactiveTrackColor = AppBackgroundSecondary,
                        disabledThumbColor = AppTextSecondary,
                        disabledActiveTrackColor = AppTextSecondary.copy(alpha = 0.35f),
                        disabledInactiveTrackColor = AppBackgroundSecondary
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTimeSeconds(currentTime),
                        fontSize = 12.sp,
                        color = AppTextSecondary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start
                    )
                    IconButton(
                        onClick = onPlayPauseClick,
                        enabled = canPlay,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canPlay) AppPrimary else AppTextSecondary.copy(alpha = 0.45f)
                                )
                                .padding(10.dp)
                        )
                    }
                    Text(
                        text = formatTimeSeconds(audioDurationSec),
                        fontSize = 12.sp,
                        color = AppTextSecondary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (transcript.isEmpty()) {
        Text(
            text = t("暂无转写记录", "No transcript available", language),
            fontSize = 14.sp,
            color = AppTextSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            textAlign = TextAlign.Center
        )
    } else {
        transcript.forEach { msg ->
            val isAi = msg.sender == MessageSender.Ai
            val startSec = msg.startTime
            val endSec = msg.endTime
            val isActive = if (startSec != null && endSec != null) {
                currentTime >= startSec && currentTime <= endSec
            } else {
                false
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = if (isAi) Arrangement.End else Arrangement.Start
            ) {
                val bubbleShape = RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (isAi) 18.dp else 4.dp,
                    bottomEnd = if (isAi) 4.dp else 18.dp
                )
                Box(
                    modifier = Modifier
                        .then(
                            if (!isAi) Modifier.shadow(1.5.dp, bubbleShape, spotColor = Color.Black.copy(alpha = 0.04f), ambientColor = Color.Transparent) else Modifier
                        )
                        .clip(bubbleShape)
                        .background(if (isAi) AppPrimary else Color.White.copy(alpha = 0.82f))
                        .then(
                            if (!isAi) Modifier.border(0.5.dp, Color.White.copy(alpha = 0.7f), bubbleShape) else Modifier
                        )
                        .then(
                            if (isActive && !isAi) Modifier.border(0.5.dp, AppPrimary.copy(alpha = 0.35f), bubbleShape) else Modifier
                        )
                ) {
                    Text(
                        text = msg.text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        fontSize = 17.sp,
                        color = if (isAi) Color.White else AppTextPrimary
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    /** 与 iOS `CallDetailView.analysisSection`：标题在渐变卡片外；行内为 `AnalysisRow`(标签+正文) */
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val outboundPayload = remember(call.backendSummary, call.fullSummary, call.isOutbound) {
            if (!call.isOutbound) null
            else parseOutboundCallSummaryPayload(call.backendSummary)
                ?: parseOutboundCallSummaryPayload(call.fullSummary)
        }
        if (outboundPayload != null) {
            OutboundCallOutcomeCard(
                payload = outboundPayload,
                language = language,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = AppAccent,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = t("AI 分析", "AI Analysis", language),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1D1D1F),
                letterSpacing = (-0.4).sp
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    5.dp,
                    RoundedCornerShape(20.dp),
                    spotColor = Color.Black.copy(alpha = 0.04f),
                    ambientColor = Color.Transparent
                )
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFEEF5FF).copy(alpha = 0.92f),
                            Color.White.copy(alpha = 0.86f)
                        )
                    )
                )
                .border(0.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CallDetailAnalysisRow(
                label = t("通话信息概览", "Summary", language),
                value = summaryDisplay.ifEmpty { t("（无摘要）", "(No summary)", language) },
            )
            if (fullSummaryText.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = AppBorder.copy(alpha = 0.45f),
                )
                CallDetailAnalysisRow(
                    label = t("AI应对结果", "Suggestion", language),
                    value = fullSummaryText,
                )
            }
        }
    }
}

/** 与 iOS `CallDetailView.AnalysisRow` 一致 */
@Composable
private fun CallDetailAnalysisRow(
    label: String,
    value: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppAccent,
        )
        Text(
            text = value,
            fontSize = 16.sp,
            color = AppTextPrimary,
            letterSpacing = (-0.2).sp
        )
    }
}

@Composable
private fun FeedbackChip(
    label: String,
    icon: @Composable () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Text(
            text = label,
            fontSize = 12.sp,
            color = AppTextSecondary
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun CallDetailScreenPreview() {
    CallMateTheme {
        CallDetailScreen(
            call = PreviewSamples.callRecord,
            onBack = {},
            isTest = false,
            language = Language.Zh
        )
    }
}
