package com.vaca.callmate.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// iOS AppTypography 一比一映射 (sp ≈ pt)
private val defaultFamily = FontFamily.Default

val AppTypography = Typography(
    // Large Title - 34 bold
    displayLarge = TextStyle(
        fontFamily = defaultFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = 0.sp
    ),
    // Title1 - 28 bold
    displayMedium = TextStyle(
        fontFamily = defaultFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp
    ),
    // Title2 - 22 semibold
    headlineLarge = TextStyle(
        fontFamily = defaultFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    // Title3 - 20 semibold
    headlineMedium = TextStyle(
        fontFamily = defaultFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp
    ),
    // Body - 17 regular / semibold
    bodyLarge = TextStyle(
        fontFamily = defaultFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    // Callout - 16
    bodyMedium = TextStyle(
        fontFamily = defaultFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    // Subheadline - 15
    bodySmall = TextStyle(
        fontFamily = defaultFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    // Footnote - 13
    labelLarge = TextStyle(
        fontFamily = defaultFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    // Caption1 - 12
    labelMedium = TextStyle(
        fontFamily = defaultFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    // Caption2 - 11
    labelSmall = TextStyle(
        fontFamily = defaultFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp
    )
)

// MaterialTheme 使用的 Typography（与 AppTypography 一致）
val Typography = AppTypography
