package com.vaca.callmate.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SignalCellularNoSim
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

/**
 * 对标 iOS `OnboardingView.PostSetupReminderSheet`：配置完成后的系统设置提示 +「立即体验」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostSetupReminderBottomSheet(
    visible: Boolean,
    language: Language,
    onStartNow: () -> Unit,
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { },
        sheetState = sheetState,
        dragHandle = { },
    ) {
        PostSetupReminderSheetContent(language = language, onStartNow = onStartNow)
    }
}

@Composable
fun PostSetupReminderSheetContent(
    language: Language,
    onStartNow: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(AppSuccess.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AppSuccess,
                    modifier = Modifier.size(36.dp)
                )
            }
            Text(
                text = t("配置已完成", "Setup Complete", language),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary
            )
            Text(
                text = t("AI 已准备好为您接听电话", "AI is ready to take calls", language),
                fontSize = 16.sp,
                color = AppTextSecondary
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AppBackgroundSecondary.copy(alpha = 0.5f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = t("💡 使用前请确认", "💡 Please confirm before use", language),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary
            )
            SettingTipRow(
                icon = Icons.Default.Phone,
                iconTint = Color(0xFF007AFF),
                text = t("请把「筛选未知来电」改成「永不」", "Set 'Filter Unknown Callers' to 'Never'", language)
            )
            SettingTipRow(
                icon = Icons.Default.SignalCellularNoSim,
                iconTint = Color(0xFFFF9500),
                text = t("请关闭「运营商骚扰拦截」", "Turn off carrier spam filtering", language)
            )
            SettingTipRow(
                icon = Icons.Default.Shield,
                iconTint = Color(0xFFFF3B30),
                text = t("请关闭其他 App 的拦截以免冲突", "Turn off spam filtering from other apps", language)
            )
            SettingTipRow(
                icon = Icons.Default.Bedtime,
                iconTint = Color(0xFF5856D6),
                text = t("「勿扰模式」下我们无法帮您接听", "Do Not Disturb prevents AI from answering", language)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onStartNow,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppPrimary),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = t("立即体验", "Start Now", language),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SettingTipRow(
    icon: ImageVector,
    iconTint: Color,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = iconTint
        )
        Text(
            text = text,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = AppTextSecondary,
            modifier = Modifier.weight(1f)
        )
    }
}
