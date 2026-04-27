package com.vaca.callmate.ui.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.CallMateApplication
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.local.OutboundContactBookEntity
import com.vaca.callmate.data.outbound.OutboundContact
import com.vaca.callmate.data.repository.CallRepository
import com.vaca.callmate.data.repository.OutboundRepository
import com.vaca.callmate.features.outbound.ManualWsScene
import com.vaca.callmate.features.outbound.OutboundChatConnectionState
import com.vaca.callmate.features.outbound.OutboundChatController
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private const val AISec_LOG = "AISecView"

/**
 * 对标 iOS `AISecView.swift`：作为 [ModalBottomSheet] 内容呈现（近似 iOS `.large` detent）。
 * - 顶部工具栏：xmark 关闭 + 两行标题 + 扬声器切换
 * - 背景：线性 + 三段径向渐变（对齐 iOS）
 * - 内容：嵌入 [FeedbackChatModal]（`outbound_ai` WS scene=update_config）
 */
@Composable
fun AISecView(
    language: Language,
    bleManager: BleManager,
    preferences: AppPreferences,
    callRepository: CallRepository,
    outboundRepository: OutboundRepository,
    onCloseRequest: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var isSoundEnabled by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as? CallMateApplication }
    if (app == null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f),
        ) {
            AiSecGradientBackground(Modifier.fillMaxSize())
            Text(
                text = t("预览需完整应用上下文", "Preview requires full app context", language),
                modifier = Modifier.align(Alignment.Center),
                color = AppTextSecondary,
            )
        }
        return
    }

    val queueService = app.outboundTaskQueueService

    val chatController = remember(language, app) {
        OutboundChatController(
            context = app,
            bleManager = bleManager,
            preferences = preferences,
            outboundRepository = outboundRepository,
            queueService = queueService,
            language = language,
            wsScene = ManualWsScene.AI_AVATAR_UPDATE_CONFIG,
            avatarInitMessagesSeed = listOf(
                "user" to "你好",
                "assistant" to t(
                    "你好，我是你的专属AI分身。你可以直接让我帮你调整接听策略，也可以让我帮你打电话、订位或做预约。",
                    "Hi, I'm your AI personal secretary. I can help adjust call rules, place AI calls, book restaurants, or handle reservations.",
                    language,
                ),
            ),
        )
    }

    val connectedAddr by bleManager.connectedAddress.collectAsState(initial = null)
    val connectingAddr by bleManager.connectingAddress.collectAsState(initial = null)
    val bleReady by bleManager.isReady.collectAsState(initial = false)
    val bleCtrlReady by bleManager.isCtrlReady.collectAsState(initial = false)
    val mcuId by bleManager.runtimeMCUDeviceID.collectAsState(initial = null)
    LaunchedEffect(connectedAddr, connectingAddr, bleReady, bleCtrlReady, mcuId) {
        val ready = !connectedAddr.isNullOrBlank() && !mcuId.isNullOrBlank()
        val st = chatController.connectionState.value
        Log.i(
            AISec_LOG,
            "[avatar-ws] effect: connectedAddr=${connectedAddr ?: "null"} " +
                "connectingAddr=${connectingAddr ?: "null"} bleReady=$bleReady bleCtrlReady=$bleCtrlReady " +
                "mcuId=${if (mcuId.isNullOrBlank()) "empty" else "len=${mcuId.orEmpty().length}"} " +
                "readyGate=$ready connState=$st",
        )
        if (!ready) {
            Log.i(AISec_LOG, "[avatar-ws] skip start: BLE/device-id gate not satisfied")
            return@LaunchedEffect
        }
        if (st == OutboundChatConnectionState.Connecting || st == OutboundChatConnectionState.Connected) {
            Log.i(AISec_LOG, "[avatar-ws] skip start: already $st")
            return@LaunchedEffect
        }
        Log.i(AISec_LOG, "[avatar-ws] invoking chatController.start() (was $st)")
        chatController.start()
    }

    LaunchedEffect(isSoundEnabled) {
        chatController.setTtsMuted(!isSoundEnabled)
    }

    /** 对齐 iOS `showOutboundAssistant` / `showCreateTaskSheet`：未来从聊天回调打开时置位；当前预留。 */
    var showOutboundAssistant by remember { mutableStateOf(false) }
    var showCreateTaskSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = showCreateTaskSheet || showOutboundAssistant) {
        when {
            showCreateTaskSheet -> showCreateTaskSheet = false
            showOutboundAssistant -> showOutboundAssistant = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.95f),
    ) {
        AiSecGradientBackground(Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize()) {
            AiSecToolbar(
                language = language,
                isSoundEnabled = isSoundEnabled,
                onClose = onCloseRequest,
                onToggleSound = { isSoundEnabled = !isSoundEnabled },
            )

            FeedbackChatModal(
                onClose = {},
                feedbackType = "none",
                language = language,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                isEmbedded = true,
                showCloseButton = false,
                showInnerHeaderRow = false,
                showMessageAvatars = false,
                outboundChat = chatController,
                deferOutboundAutoStart = true,
            )
        }

        AnimatedVisibility(
            visible = showOutboundAssistant,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            com.vaca.callmate.ui.screens.outbound.OutboundCreateTaskAIScreen(
                language = language,
                onBack = { showOutboundAssistant = false },
                onOpenCreateTask = {
                    showOutboundAssistant = false
                    showCreateTaskSheet = true
                },
                bleManager = bleManager,
                preferences = preferences,
                outboundRepository = outboundRepository,
                queueService = queueService,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = showCreateTaskSheet,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            val templates by outboundRepository.templatesFlow.collectAsState(initial = emptyList())
            val contactBook by outboundRepository.contactsFlow.collectAsState(initial = emptyList())
            val existingContacts = remember(contactBook, language) {
                mergeContactsForOutbound(contactBook, language)
            }
            com.vaca.callmate.ui.screens.outbound.OutboundCreateTaskScreen(
                language = language,
                templates = templates,
                existingContacts = existingContacts,
                initialDraft = null,
                onOpenAI = {
                    showCreateTaskSheet = false
                    showOutboundAssistant = true
                },
                onClose = { showCreateTaskSheet = false },
                onCreate = { submission ->
                    queueService.createTask(submission)
                    showCreateTaskSheet = false
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun mergeContactsForOutbound(
    book: List<OutboundContactBookEntity>,
    language: Language,
): List<OutboundContact> {
    val seen = HashSet<String>()
    val out = ArrayList<OutboundContact>()
    for (e in book) {
        val p = e.phone.trim()
        if (p.isEmpty() || !seen.add(p)) continue
        val name = e.name.trim().ifEmpty {
            if (language == Language.Zh) "名单联系人" else "Address Book Contact"
        }
        out.add(OutboundContact(phone = p, name = name))
        if (out.size >= 60) break
    }
    return out
}

@Composable
private fun AiSecToolbar(
    language: Language,
    isSoundEnabled: Boolean,
    onClose: () -> Unit,
    onToggleSound: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(44.dp)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = t("关闭", "Close", language),
                tint = AppTextPrimary,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = t("AI 分身", "AI Avatar", language),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = t("内容由 AI 生成", "Content generated by AI", language),
                fontSize = 11.sp,
                color = Color(0xFF9CA3AF),
                textAlign = TextAlign.Center,
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(44.dp)
                .clickable(onClick = onToggleSound),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isSoundEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = null,
                tint = Color(0xFF6B7280),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 对齐 iOS `AISecView` 背景：线性 + 三段径向渐变。 */
@Composable
private fun AiSecGradientBackground(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFFF6F8FF),
                            0.4f to Color(0xFFF3F5FF),
                            0.7f to Color(0xFFF5F4FF),
                            1.0f to Color(0xFFF4F7FF),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFDCE8FF).copy(alpha = 0.5f), Color.Transparent),
                        center = Offset(220f, 180f),
                        radius = 1100f,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFE6E1FA).copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(900f, 1300f),
                        radius = 900f,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFD7E6FF).copy(alpha = 0.3f), Color.Transparent),
                        center = Offset(450f, 2000f),
                        radius = 800f,
                    ),
                ),
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun AISecViewPreview() {
    com.vaca.callmate.ui.preview.PreviewUnavailablePlaceholder("AISecView")
}
