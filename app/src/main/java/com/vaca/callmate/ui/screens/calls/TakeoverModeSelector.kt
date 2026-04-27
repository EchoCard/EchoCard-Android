package com.vaca.callmate.ui.screens.calls

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackgroundGrouped
import com.vaca.callmate.ui.theme.AppBorder
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppTextTertiary

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

/** 与 iOS `CallsView.modeHint` 一致 */
fun takeoverModeHint(mode: String, language: Language): String = when (mode) {
    "standby" -> t("蓝牙待机中，不会监听来电", "Bluetooth standby, not monitoring calls.", language)
    "semi" -> t("蓝牙工作中，持续监听陌生来电", "Bluetooth active, monitoring unknown calls.", language)
    "full" -> t("全程代接，智能沟通并妥善处理", "Full takeover. AI handles and summarizes.", language)
    else -> ""
}

/**
 * 对标 iOS `CallsView.modeSelector`：`dsCardStyle` 卡片 + 灰底轨道 + 蓝色滑动 pill + 三枚 segment（待机 / 智能 / 全接管）。
 */
@Composable
fun TakeoverModeSelector(
    language: Language,
    activeMode: String,
    onSelectMode: (String) -> Unit,
    onFullModeBlocked: () -> Unit,
    deviceConnected: Boolean,
    /** 与 iOS `shouldShowModeConnectBlocker`：已绑定设备但未连上、且未处于「立即连接」流程时显示。 */
    showConnectBlocker: Boolean,
    onConnectNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = when (activeMode) {
        "standby" -> 0
        "semi" -> 1
        "full" -> 2
        else -> 1
    }

    val cardShape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (deviceConnected) 1f else 0.92f)
                .shadow(
                    elevation = 6.dp,
                    shape = cardShape,
                    ambientColor = Color.Transparent,
                    spotColor = Color.Black.copy(alpha = 0.04f)
                ),
            shape = cardShape,
            color = AppSurface,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = t("接管模式", "Call Mode", language),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))
                TakeoverSegmentTrack(
                    language = language,
                    selectedIndex = selectedIndex,
                    onSelectIndex = { idx ->
                        when (idx) {
                            0 -> onSelectMode("standby")
                            1 -> onSelectMode("semi")
                            2 -> {
                                onFullModeBlocked()
                            }
                            else -> {}
                        }
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = AppTextTertiary
                    )
                    Text(
                        text = takeoverModeHint(activeMode, language),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        color = AppTextTertiary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (showConnectBlocker) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(cardShape)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AppSurface.copy(alpha = 0.96f))
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = t("请先连接 EchoCard", "Please connect EchoCard first", language),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1D1D1F)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        onClick = onConnectNow,
                        shape = RoundedCornerShape(12.dp),
                        color = AppPrimary,
                    ) {
                        Text(
                            text = t("立即连接", "Connect Now", language),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TakeoverSegmentTrack(
    language: Language,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
) {
    val insetDp = 6.dp
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val segmentSide: Dp = (maxWidth - insetDp * 2) / 3
        val trackHeight = segmentSide + insetDp * 2
        val pillOffsetTarget = insetDp + segmentSide * selectedIndex
        val trackShape = RoundedCornerShape(20.dp)
        val pillShape = RoundedCornerShape(16.dp)
        val pillOffset by animateDpAsState(
            targetValue = pillOffsetTarget,
            animationSpec = spring(
                dampingRatio = 0.72f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "takeoverPill"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackgroundGrouped, trackShape)
            )
            Box(
                modifier = Modifier
                    .offset(x = pillOffset, y = insetDp)
                    .size(segmentSide)
                    .shadow(
                        elevation = 8.dp,
                        shape = pillShape,
                        ambientColor = Color.Transparent,
                        spotColor = AppPrimary.copy(alpha = 0.22f)
                    )
                    .background(AppPrimary, pillShape)
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insetDp),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                TakeoverSegmentCell(
                    language = language,
                    labelZh = "待机",
                    labelEn = "Standby",
                    selected = selectedIndex == 0,
                    onClick = { onSelectIndex(0) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bedtime,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = it
                    )
                }
                TakeoverSegmentCell(
                    language = language,
                    labelZh = "智能",
                    labelEn = "Smart",
                    selected = selectedIndex == 1,
                    onClick = { onSelectIndex(1) },
                    modifier = Modifier.weight(1f)
                ) {
                    MagicSparkleIconPainted(
                        tint = it,
                        // 与 iOS `.frame(width: 22, height: 26.6)` 一致
                        modifier = Modifier.size(width = 22.dp, height = 26.6.dp)
                    )
                }
                TakeoverSegmentCell(
                    language = language,
                    labelZh = "全接管",
                    labelEn = "Full",
                    selected = selectedIndex == 2,
                    onClick = { onSelectIndex(2) },
                    modifier = Modifier.weight(1f)
                ) {
                    BrainHandsIconPainted(
                        tint = it,
                        // 与 iOS `.frame(width: 24, height: 23)` 一致
                        modifier = Modifier.size(width = 24.dp, height = 23.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TakeoverSegmentCell(
    language: Language,
    labelZh: String,
    labelEn: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (tint: Color) -> Unit,
) {
    val label = if (language == Language.Zh) labelZh else labelEn
    val tint = if (selected) Color.White else AppTextSecondary
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 2.dp)
        ) {
            Box(
                modifier = Modifier.size(30.dp),
                contentAlignment = Alignment.Center
            ) {
                icon(tint)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = tint
            )
        }
    }
}
