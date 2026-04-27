package com.vaca.callmate.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.vaca.callmate.R
import com.vaca.callmate.data.Language
import com.vaca.callmate.features.outbound.RuleChangeRequest
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import kotlinx.coroutines.yield
import org.json.JSONObject

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

/** 与 iOS `strategyCard(from:)` 对齐：`{"trigger","action"}` */
fun parseStrategyCardJson(text: String): Pair<String, String>? {
    val t = text.trim()
    if (!t.startsWith("{")) return null
    return try {
        val o = JSONObject(t)
        val trigger = o.optString("trigger", "").trim()
        val action = o.optString("action", "").trim()
        if (trigger.isEmpty() || action.isEmpty()) null else trigger to action
    } catch (_: Exception) {
        null
    }
}

@Composable
fun StrategyCardRow(
    trigger: String,
    action: String,
    language: Language,
    isApplied: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val top = if (isApplied) Color(0xFF34C759) else AppPrimary
    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .clip(shape)
            .border(0.5.dp, Color.White.copy(alpha = 0.5f), shape)
            .background(
                Brush.verticalGradient(
                    colors = if (isApplied) {
                        listOf(Color(0xFFE0F5E8).copy(alpha = 0.75f), Color.White.copy(alpha = 0.55f))
                    } else {
                        listOf(Color(0xFFE6EEFC).copy(alpha = 0.75f), Color.White.copy(alpha = 0.55f))
                    }
                )
            )
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(15.dp))
                    .background(top.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(text = "◎", fontSize = 20.sp, color = top)
            }
            Column {
                Text(
                    text = trigger,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary
                )
                if (isApplied) {
                    Text(
                        text = t("修改已生效", "Applied", language),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppSuccess
                    )
                }
            }
        }
        Spacer(modifier = Modifier.padding(top = 10.dp))
        Text(
            text = action,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = AppTextSecondary
        )
    }
}

@Composable
fun OnboardingRuleChangeCardRow(
    change: RuleChangeRequest,
    language: Language,
    isInitConfigAutoApplied: Boolean,
    onConfirm: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val primary = change.updatedRules.firstOrNull()
    val trigger = primary?.type
        ?: t("规则更新", "Rule Update", language)
    val action = primary?.rule?.trim().orEmpty().ifEmpty { change.updatedRuleSummary }
    StrategyCardRow(
        trigger = trigger,
        action = action,
        language = language,
        isApplied = isInitConfigAutoApplied,
        modifier = modifier
    )
    if (!isInitConfigAutoApplied && onConfirm != null && onCancel != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(t("取消", "Cancel", language))
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
            ) {
                Text(t("确认", "Confirm", language))
            }
        }
    }
}

@Composable
fun CloneAuthorizationCardRow(
    language: Language,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .clip(shape)
            .border(0.5.dp, Color.White.copy(alpha = 0.5f), shape)
            .background(Color.White.copy(alpha = 0.72f))
            .padding(18.dp)
    ) {
        Text(
            text = t("声音克隆授权", "Voice Clone Authorization", language),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = AppTextPrimary
        )
        Spacer(modifier = Modifier.padding(top = 8.dp))
        Text(
            text = t(
                "AI 需要克隆你的声音，用于接听电话时让对方感觉更自然。你的声音将加密存储，仅用于帮你接听电话，不会用于任何其他用途。",
                "AI needs to clone your voice to make calls feel more natural. Your voice will be encrypted and used only to answer calls on your behalf.",
                language
            ),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = AppTextSecondary
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                Text(t("拒绝", "Decline", language))
            }
            Button(
                onClick = onAccept,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
            ) {
                Text(t("授权", "Authorize", language))
            }
        }
    }
}

@Composable
fun AuthorizationRequestCardRow(
    language: Language,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .clip(shape)
            .border(0.5.dp, Color.White.copy(alpha = 0.5f), shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFE6EEFC).copy(alpha = 0.75f), Color.White.copy(alpha = 0.55f))
                )
            )
            .padding(18.dp)
    ) {
        Text(
            text = t("需要您的授权", "Your Authorization Needed", language),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = AppTextPrimary
        )
        Spacer(modifier = Modifier.padding(top = 8.dp))
        Text(
            text = t(
                "授权AI分身帮您接电话，获取您的个人信息，持续分析来电内容等。",
                "Authorize your AI avatar to answer calls, access your personal information, and continuously analyze call content.",
                language
            ),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = AppTextSecondary
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                Text(t("拒绝", "Decline", language))
            }
            Button(
                onClick = onAccept,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
            ) {
                Text(t("接受", "Accept", language))
            }
        }
    }
}

/**
 * 与 iOS `GuideImageCardContent` / bundle 资源对齐：`takeover_reminder` 视频、`ios_call_filter_setting`、`unknown_call_handling` 图。
 */
@Composable
fun GuideImageCardRow(
    imageId: String,
    caption: String?,
    language: Language,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.widthIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (imageId) {
            "takeover_reminder" -> {
                val videoUri = remember(context.packageName) {
                    Uri.parse("android.resource://${context.packageName}/${R.raw.takeover_reminder}")
                }
                val player = remember(videoUri) {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(videoUri))
                        repeatMode = Player.REPEAT_MODE_ONE
                    }
                }
                DisposableEffect(player) {
                    onDispose { player.release() }
                }
                LaunchedEffect(player) {
                    yield()
                    player.prepare()
                    player.playWhenReady = true
                }
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply { useController = false }
                    },
                    update = { view ->
                        if (view.player !== player) view.player = player
                    },
                    onRelease = { view -> (view as? PlayerView)?.player = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
            }
            "ios_call_filter_setting" -> {
                Image(
                    painter = painterResource(R.drawable.guide_filter_call),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            }
            "unknown_call_handling" -> {
                Image(
                    painter = painterResource(R.drawable.guide_unknown_call),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            }
            else -> {
                Text(
                    text = t("引导图", "Guide", language) + ": $imageId",
                    fontSize = 13.sp,
                    color = AppTextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF3F4F6))
                        .padding(16.dp)
                )
            }
        }
        caption?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, fontSize = 13.sp, color = AppTextPrimary)
        }
    }
}
