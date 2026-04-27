package com.vaca.callmate.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.vaca.callmate.ui.theme.AppPrimary
import kotlinx.coroutines.launch

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

/**
 * 对标 iOS [MainTabView.swift]：去掉底 tab，主页只有 [CallsView] + 右下「AI分身」FAB。
 * 点击 FAB 以 [ModalBottomSheet]（`skipPartiallyExpanded = true`，近似 iOS `.large` detent）
 * 唤出 [AISecView]。外呼 tab 整体裁掉，创建能力收敛到聊天里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabView(
    language: Language,
    setLanguage: (Language) -> Unit,
    onUnbind: () -> Unit,
    bleManager: BleManager,
    sessionViewModel: com.vaca.callmate.features.calls.CallSessionViewModel? = null,
    callRepository: com.vaca.callmate.data.repository.CallRepository? = null,
    preferences: AppPreferences,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as? CallMateApplication }
    val resolvedCallRepository = callRepository ?: app?.callRepository
    val outboundRepository = app?.outboundRepository

    /** 对标 iOS `showAIFabOnHome`：CallsView 处于全屏子页时（LiveCall / DeviceManagement / 模拟通话 / 测试报告）隐藏 FAB */
    var showFabOnHome by remember { mutableStateOf(true) }
    /** 对标 iOS `showAISheet` */
    var showAISheet by remember { mutableStateOf(false) }

    /** 外呼接通：MCU 报 active 时由 [BleManager.outboundLivePresentation] 置位；sheet 打开时要让路给 CallsView 的 LiveCall 全屏 */
    val outboundLivePresentation by bleManager.outboundLivePresentation.collectAsState(initial = false)
    LaunchedEffect(outboundLivePresentation) {
        if (outboundLivePresentation && showAISheet) {
            showAISheet = false
        }
    }

    BackHandler(enabled = showAISheet) { showAISheet = false }

    Box(modifier = modifier.fillMaxSize()) {
        CallsView(
            onUnbind = onUnbind,
            language = language,
            setLanguage = setLanguage,
            bleManager = bleManager,
            sessionViewModel = sessionViewModel,
            callRepository = resolvedCallRepository,
            preferences = preferences,
            onFullScreenSubviewChange = { isSubview -> showFabOnHome = !isSubview },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = showFabOnHome && !showAISheet,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 32.dp),
        ) {
            AiFab(
                language = language,
                onClick = { showAISheet = true },
            )
        }
    }

    if (showAISheet && resolvedCallRepository != null && outboundRepository != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        ModalBottomSheet(
            onDismissRequest = { showAISheet = false },
            sheetState = sheetState,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            dragHandle = null,
        ) {
            AISecView(
                language = language,
                bleManager = bleManager,
                preferences = preferences,
                callRepository = resolvedCallRepository,
                outboundRepository = outboundRepository,
                onCloseRequest = {
                    scope.launch {
                        sheetState.hide()
                        showAISheet = false
                    }
                },
            )
        }
    }
}

@Composable
private fun AiFab(
    language: Language,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .shadow(
                elevation = 12.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = AppPrimary.copy(alpha = 0.5f),
                spotColor = AppPrimary.copy(alpha = 0.5f),
            )
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.92f))
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.5f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = t("AI分身", "Avatar", language),
                tint = AppPrimary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = t("AI分身", "Avatar", language),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppPrimary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MainTabViewPreview() {
    com.vaca.callmate.ui.preview.PreviewUnavailablePlaceholder("MainTabView")
}
