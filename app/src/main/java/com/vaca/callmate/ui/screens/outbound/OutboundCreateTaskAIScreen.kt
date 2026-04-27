package com.vaca.callmate.ui.screens.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.Language
import com.vaca.callmate.features.outbound.OutboundChatController
import com.vaca.callmate.features.outbound.OutboundTaskQueueService
import com.vaca.callmate.data.repository.OutboundRepository
import com.vaca.callmate.ui.screens.FeedbackChatModal
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

/**
 * 对标 iOS `OutboundCreateTaskAIView`：全屏嵌入 + `outbound_chat` WebSocket（与 iOS 协议对齐）。
 */
@Composable
fun OutboundCreateTaskAIScreen(
    language: Language,
    onBack: () -> Unit,
    onOpenCreateTask: () -> Unit,
    onTabBarHiddenChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    bleManager: BleManager,
    preferences: AppPreferences,
    outboundRepository: OutboundRepository,
    queueService: OutboundTaskQueueService,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val outboundChat = remember(language) {
        OutboundChatController(
            context = appContext,
            bleManager = bleManager,
            preferences = preferences,
            outboundRepository = outboundRepository,
            queueService = queueService,
            language = language,
        )
    }
    Column(modifier = modifier.fillMaxSize().background(AppBackgroundSecondary)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(AppBackgroundSecondary)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBackIos,
                    contentDescription = null,
                    tint = AppPrimary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Text(
                text = t("AI 外呼助手", "AI Outbound", language),
                modifier = Modifier.weight(1f),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary
            )
            Box(
                modifier = Modifier
                    .shadow(
                        elevation = 4.dp,
                        shape = RoundedCornerShape(999.dp),
                        spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.03f),
                        ambientColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                    .clip(RoundedCornerShape(999.dp))
                    .background(AppSurface)
                    .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
                    .clickable(onClick = onOpenCreateTask)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = t("创建批量任务", "Create Batch", language),
                    color = AppPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        FeedbackChatModal(
            onClose = {},
            feedbackType = "outbound_ai",
            language = language,
            modifier = Modifier.weight(1f),
            isEmbedded = true,
            showCloseButton = false,
            showInnerHeaderRow = false,
            showMessageAvatars = false,
            onRecordingStateChange = onTabBarHiddenChange,
            outboundChat = outboundChat,
        )
    }
}
