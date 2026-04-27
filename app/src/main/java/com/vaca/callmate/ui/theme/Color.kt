package com.vaca.callmate.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 与 `CallMate/Shared/UI/DesignSystem.swift` → `AppColors` **浅色**一致（Android 当前不启深色，数值对 light）。
 *
 * | iOS `AppColors` | Android |
 * |---|---|
 * | `primary` | [AppPrimary] |
 * | `textPrimary` | [AppTextPrimary] |
 * | `textSecondary` / `textTertiary` | [AppTextSecondary] / [AppTextTertiary]（3C3C43 @ 60% / 30%） |
 * | `background`（卡片） | [AppBackground] = [AppBackgroundCard] |
 * | `backgroundSecondary`（页面底） | [AppBackgroundSecondary] = [AppBackgroundPage] |
 * | `separator` / `border` | [AppSeparator] / [AppBorder] |
 * | `surface` / `surfaceElevated` | [AppSurface] / [AppSurfaceElevated] |
 * | `backgroundGrouped` | [AppBackgroundGrouped] |
 * | `chevron` | [AppChevron] |
 */
private val Base3C3C43 = Color(0xFF3C3C43)

// Primary
val AppPrimary = Color(0xFF007AFF)
val AppPrimaryLight = Color(0x1A007AFF) // 10%

// Accent
val AppAccent = Color(0xFF5856D6)
val AppAccentLight = Color(0x1A5856D6)

// Semantic
val AppSuccess = Color(0xFF34C759)
val AppWarning = Color(0xFFFF9500)
val AppError = Color(0xFFFF3B30)

// Text — textPrimary #000000；secondary/tertiary 为 3C3C43 @ 60% / 30%（与 iOS 一致）
val AppTextPrimary = Color(0xFF000000)
val AppTextSecondary = Base3C3C43.copy(alpha = 0.6f)
val AppTextTertiary = Base3C3C43.copy(alpha = 0.3f)

// Background — iOS：background = backgroundCard = FFFFFF；backgroundSecondary = backgroundPage = F2F2F7；backgroundTertiary 浅色同卡片
val AppBackground = Color(0xFFFFFFFF)
val AppBackgroundSecondary = Color(0xFFF2F2F7)
val AppBackgroundTertiary = Color(0xFFFFFFFF)

/** 与 iOS `backgroundPage` / `backgroundSecondary` 同名语义 */
val AppBackgroundPage = AppBackgroundSecondary

/** 与 iOS `background` / `backgroundCard` 同名语义 */
val AppBackgroundCard = AppBackground

/** 与 iOS `backgroundGrouped` 浅色一致（分段控件等底层） */
val AppBackgroundGrouped = Color(0xFFF2F2F7)

// Separator & Border — iOS：3C3C43 @ 18%（浅色）
val AppSeparator = Base3C3C43.copy(alpha = 0.18f)
val AppBorder = Base3C3C43.copy(alpha = 0.18f)

// Surfaces
val AppSurface = Color(0xFFFFFFFF)
val AppSurfaceElevated = Color(0xFFF9F9F9)

// 设置行 chevron 等（浅色）
val AppChevron = Color(0xFFD1D5DB)

// ========== Legacy / aliases（保持现有引用兼容）==========
val PrimaryBlue = AppPrimary
val Blue50 = Color(0xFFEFF6FF)
val Blue100 = Color(0xFFDBEAFE)
val Blue200 = Color(0xFFBFDBFE)
val Blue600 = Color(0xFF2563EB)

val Gray50 = Color(0xFFF9FAFB)
val Gray100 = Color(0xFFF3F4F6)
val Gray200 = Color(0xFFE5E7EB)
val Gray300 = Color(0xFFD1D5DB)
val Gray400 = Color(0xFF9CA3AF)
val Gray500 = Color(0xFF6B7280)
val Gray700 = Color(0xFF374151)
val Gray800 = Color(0xFF1F2937)
val Gray900 = Color(0xFF111827)

val Green500 = AppSuccess
val Green100 = Color(0xFFDCFCE7)
val Red500 = AppError
val Red50 = Color(0xFFFEF2F2)
val Red100 = Color(0xFFFEE2E2)
val Orange400 = Color(0xFFFB923C)
val Orange50 = Color(0xFFFFF7ED)
val Orange100 = Color(0xFFFFEDD5)
val Indigo50 = Color(0xFFEEF2FF)
val Indigo100 = Color(0xFFE0E7FF)
val Indigo400 = Color(0xFF818CF8)
val Indigo500 = Color(0xFF6366F1)
val Indigo600 = Color(0xFF4F46E5)
val Purple50 = Color(0xFFF5F3FF)
val Purple100 = Color(0xFFEDE9FE)
val Purple200 = Color(0xFFE9D5FF)

// Theme defaults (Material)
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
