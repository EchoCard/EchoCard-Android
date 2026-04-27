package com.vaca.callmate.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * iOS AppRadius 一比一
 */
object AppRadius {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 20.dp
    val xxl = 32.dp
    val full = 999.dp
}

/**
 * 常用 Shape：卡片、按钮等
 */
object AppShape {
    val card = RoundedCornerShape(AppRadius.lg)
    val button = RoundedCornerShape(AppRadius.full)
    val small = RoundedCornerShape(AppRadius.xs)
    val medium = RoundedCornerShape(AppRadius.md)
}

/**
 * 对标 iOS `AppShadow`（light: y=2 blur=8 / medium: y=8 blur=24 / heavy: y=16 blur=40）的 **elevation dp 近似**，
 * 供 `Card` / `Surface` 与 `Modifier.shadow` 使用（`UI-D02`）。
 */
object AppElevation {
    val sm = 2.dp
    val md = 8.dp
    val lg = 16.dp
}
