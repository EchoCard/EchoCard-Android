package com.vaca.callmate.ui.screens

import com.vaca.callmate.data.CallRecord
import com.vaca.callmate.data.Language

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

/**
 * 与 iOS `CallsView` / `CallRowView` 首行一致：`displaySummary` 空则「未识别内容」
 * （全部通话列表用「（无摘要）」，见 [AllCallsScreen]）
 */
fun summaryHeadlineForDashboard(call: CallRecord, language: Language): String =
    call.summary.trim().ifEmpty { t("未识别内容", "Unrecognized", language) }

/** 与 iOS `CallRowView.aiSummaryLine`：仅非空 fullSummary */
fun aiSummaryLineOptional(call: CallRecord): String? =
    call.fullSummary.trim().takeIf { it.isNotEmpty() }

/**
 * 与 iOS `CallRowView` 第二行：`aiSummaryLine ?? t("通话内容识别失败", ...)`
 */
fun aiLineOrFallbackForDashboard(call: CallRecord, language: Language): String =
    aiSummaryLineOptional(call)
        ?: t("通话内容识别失败", "Call content not recognized", language)
