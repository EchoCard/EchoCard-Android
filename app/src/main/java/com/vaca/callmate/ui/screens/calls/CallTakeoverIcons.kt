package com.vaca.callmate.ui.screens.calls

import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.core.graphics.PathParser

/**
 * 与 iOS `CustomIcons.swift` 中 `MagicSparkleIcon` / `BrainHandsIcon` 同路径数据（viewBox 内坐标）。
 */
private val magicSparklePathStrings = listOf(
    "M3.80815 18.333C3.80809 16.3748 8.58522 17.3099 10.6627 15.2325C12.7402 13.155 12.6751 8.37788 13.7633 8.37789C14.8515 8.37789 14.3378 13.0454 16.3748 15.2325C18.4119 17.4195 23.7185 16.7557 23.7185 18.333C23.7185 19.9104 18.5271 18.7923 16.3748 20.9446C14.2225 23.0969 15.2323 28.5608 13.7633 28.2882C12.2943 28.0156 12.8498 22.9817 10.6627 20.9446C8.47564 18.9075 3.8082 20.2913 3.80815 18.333Z",
    "M0.208847 9.9599C0.21009 8.93556 2.70833 9.42162 3.79638 8.33358C4.88443 7.24553 4.85347 4.74674 5.42271 4.74604C5.99194 4.74533 5.72017 7.18717 6.78431 8.32987C7.84845 9.47257 10.6247 9.12189 10.6236 9.94697C10.6226 10.772 7.90783 10.1906 6.7806 11.3178C5.65337 12.445 6.17804 15.3025 5.40978 15.1608C4.64152 15.0192 4.93537 12.3856 3.79267 11.3215C2.64997 10.2574 0.207604 10.9842 0.208847 9.9599Z",
    "M8.48223 2.6951C8.48285 2.18293 9.73197 2.42596 10.276 1.88194C10.82 1.33792 10.8045 0.0885192 11.0892 0.088167C11.3738 0.0878148 11.2379 1.30873 11.77 1.88008C12.302 2.45143 13.6901 2.2761 13.6896 2.68863C13.6891 3.10117 12.3317 2.81043 11.7681 3.37405C11.2045 3.93767 11.4668 5.36638 11.0827 5.29556C10.6986 5.22475 10.8455 3.90797 10.2741 3.3759C9.70279 2.84384 8.48161 3.20727 8.48223 2.6951Z"
)

private val brainHandsPathStrings = listOf(
    "M8.672 24.496H8.112C6.752 24.496 5.552 23.456 5.136 21.904L4.928 21.104L3.952 21.152C1.792 21.152 0.016 18.864 0.016 16.064C0.016 14.928 0.304 13.856 0.848 12.96L1.152 12.448L0.848 11.92C0.304 10.976 0 9.84 0 8.656C0 5.92 1.52 3.664 3.504 3.36L4.976 6.4C5.152 6.768 5.52 6.976 5.904 6.976C6.0767 6.97765 6.24698 6.93531 6.3988 6.85298C6.55063 6.77064 6.67899 6.65102 6.77182 6.50538C6.86465 6.35974 6.91888 6.19287 6.92941 6.02048C6.93994 5.84809 6.90641 5.67586 6.832 5.52L5.248 2.24C5.744 0.88 6.848 0 8.112 0H8.672C10.4 0 11.792 1.664 11.792 3.712V15.216C11.616 15.184 11.424 15.216 11.248 15.28L7.904 16.688C7.376 16.912 7.136 17.504 7.36 18.032C7.4665 18.282 7.66773 18.4796 7.91964 18.5816C8.17154 18.6836 8.45358 18.6816 8.704 18.576L11.792 17.28V20.784C11.792 22.832 10.384 24.496 8.672 24.496ZM24.784 12.96C25.328 13.872 25.616 14.944 25.616 16.064C25.616 18.752 24 20.96 21.936 21.136C21.936 21.088 21.904 21.056 21.888 21.024L20.416 18.112C20.356 17.9914 20.2725 17.884 20.1705 17.796C20.0685 17.708 19.95 17.6412 19.8219 17.5995C19.6938 17.5578 19.5587 17.542 19.4245 17.5531C19.2902 17.5642 19.1595 17.6019 19.04 17.664C18.528 17.92 18.336 18.528 18.592 19.04L20.064 21.952C20.144 22.096 20.24 22.224 20.368 22.32C19.872 23.648 18.768 24.512 17.52 24.512H16.96C15.232 24.512 13.84 22.848 13.84 20.8V9.36C13.936 9.36 14.016 9.328 14.096 9.28L16.688 8.128C16.8107 8.07433 16.9214 7.99675 17.0138 7.89981C17.1061 7.80287 17.1782 7.6885 17.2259 7.56338C17.2735 7.43826 17.2958 7.3049 17.2914 7.17109C17.2869 7.03727 17.2559 6.90568 17.2 6.784C17.1463 6.66134 17.0688 6.5506 16.9718 6.45825C16.8749 6.3659 16.7605 6.29379 16.6354 6.24612C16.5103 6.19846 16.3769 6.1762 16.2431 6.18064C16.1093 6.18508 15.9777 6.21614 15.856 6.272L13.84 7.168V3.744C13.84 1.696 15.248 0.0320001 16.96 0.0320001H17.52C18.88 0.0320001 20.08 1.072 20.496 2.624L20.704 3.424L21.68 3.376C23.84 3.376 25.616 5.76 25.616 8.704C25.616 9.888 25.328 11.024 24.768 11.968L24.464 12.496L24.768 13.008L24.784 12.96Z"
)

private fun combinedComposePath(pathStrings: List<String>): androidx.compose.ui.graphics.Path {
    val androidPath = AndroidPath()
    pathStrings.forEach { s ->
        androidPath.addPath(PathParser.createPathFromPathData(s))
    }
    return androidPath.asComposePath()
}

@Composable
fun MagicSparkleIconPainted(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val path = remember { combinedComposePath(magicSparklePathStrings) }
    Canvas(modifier) {
        // 与 iOS `MagicSparkleIcon` viewBox 24×29 + `.frame(22, 26.6)` 等效：等比缩放后居中绘制
        drawSvgPathCentered(path, tint, viewBoxWidth = 24f, viewBoxHeight = 29f)
    }
}

@Composable
fun BrainHandsIconPainted(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val path = remember { combinedComposePath(brainHandsPathStrings) }
    Canvas(modifier) {
        // 与 iOS `BrainHandsIcon` viewBox 26×25 + `.frame(24, 23)` 等效
        drawSvgPathCentered(path, tint, viewBoxWidth = 26f, viewBoxHeight = 25f)
    }
}

private fun DrawScope.drawSvgPathCentered(
    path: androidx.compose.ui.graphics.Path,
    color: Color,
    viewBoxWidth: Float,
    viewBoxHeight: Float,
) {
    val s = minOf(size.width / viewBoxWidth, size.height / viewBoxHeight)
    val scaledW = viewBoxWidth * s
    val scaledH = viewBoxHeight * s
    val tx = (size.width - scaledW) * 0.5f
    val ty = (size.height - scaledH) * 0.5f
    translate(tx, ty) {
        scale(s, s, pivot = Offset.Zero) {
            drawPath(path = path, color = color)
        }
    }
}
