package com.vaca.callmate.ui.theme

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Material3 映射到 iOS `AppColors`（浅色）：
 * - `ColorScheme.background` → 页面底 `backgroundSecondary` / [AppBackgroundSecondary]（F2F2F7）
 * - `ColorScheme.surface` → 卡片 `surface` / [AppSurface]（FFFFFF）
 * - `onSurfaceVariant` → 次要正文
 *
 * 与 [androidx.activity.ComponentActivity.enableEdgeToEdge] 配合：**状态栏/导航栏透明**，
 * 由 Compose 根节点与各屏背景铺满至系统栏区域，避免与页面底色「分层」。
 */
private val CallMateLightColorScheme = lightColorScheme(
    primary = AppPrimary,
    onPrimary = Color.White,
    primaryContainer = AppPrimaryLight,
    onPrimaryContainer = AppTextPrimary,
    secondary = AppAccent,
    onSecondary = Color.White,
    tertiary = AppSuccess,
    onTertiary = Color.White,
    error = AppError,
    onError = Color.White,
    background = AppBackgroundSecondary,
    onBackground = AppTextPrimary,
    surface = AppSurface,
    onSurface = AppTextPrimary,
    surfaceVariant = AppSurfaceElevated,
    onSurfaceVariant = AppTextSecondary,
    outline = AppBorder,
    outlineVariant = AppSeparator
)

@Composable
fun CallMateTheme(
    darkTheme: Boolean = false, // 强制浅色，与 iOS 一致
    content: @Composable () -> Unit
) {
    val colorScheme = CallMateLightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode && view.context is Activity) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = AndroidColor.TRANSPARENT
            window.navigationBarColor = AndroidColor.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.xs),
            small = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.sm),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.md),
            large = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.lg),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.xl)
        ),
        content = content
    )
}
