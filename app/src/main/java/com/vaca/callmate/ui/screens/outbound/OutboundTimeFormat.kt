package com.vaca.callmate.ui.screens.outbound

import com.vaca.callmate.data.Language
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatAbsoluteDateTime(millis: Long, language: Language): String {
    val fmt = if (language == Language.Zh) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    } else {
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US)
    }
    return fmt.format(Date(millis))
}

fun formatRelativeTime(millis: Long, language: Language): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    return when {
        diff < 60_000 -> if (language == Language.Zh) "刚刚" else "Just now"
        diff < 3600_000 -> {
            val m = diff / 60_000
            if (language == Language.Zh) "${m}分钟前" else "${m}m ago"
        }
        diff < 86400_000 -> {
            val h = diff / 3600_000
            if (language == Language.Zh) "${h}小时前" else "${h}h ago"
        }
        else -> {
            val d = diff / 86400_000
            if (language == Language.Zh) "${d}天前" else "${d}d ago"
        }
    }
}

fun formatDurationSeconds(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%d:%02d", m, s)
}
