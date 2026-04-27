package com.vaca.callmate.features.outbound

import org.json.JSONArray
import org.json.JSONObject

/**
 * 与 iOS `OutboundConfirmationData` 对齐（`CallMate/Features/Settings/FeedbackChatModalView.swift:29`）。
 * 由 [OutboundChatController] 在处理 `initiate_call` / `schedule_call` tool 调用时生成，驱动聊天内嵌外呼确认卡。
 */
data class OutboundConfirmationData(
    val phone: String,
    val contactName: String?,
    val goal: String?,
    val keyPoints: String?,
    val templateName: String,
    val scheduledAtMillis: Long?,
    val timeDescription: String?,
)

/**
 * 与 iOS `ProposalCardStatus` 对齐（`CallMate/Features/Settings/FeedbackChatModalView.swift:46`）。
 * - [Pending]：等待用户确认
 * - [Applied]：用户确认且执行成功
 * - [Cancelled]：用户主动取消
 * - [Expired]：超时未确认（目前不主动设置，预留）
 * - [Failed]：用户确认但执行失败
 */
enum class ProposalCardStatus { Pending, Applied, Cancelled, Expired, Failed }

/**
 * 单张外呼确认卡片在 UI 侧需要的状态集合。`data` + `status` + 可选失败原因。
 */
data class OutboundConfirmCardState(
    val callId: String,
    val data: OutboundConfirmationData,
    val status: ProposalCardStatus = ProposalCardStatus.Pending,
    val failureMessage: String? = null,
)

/**
 * 聊天消息中嵌入确认卡的哨兵前缀；与 [com.vaca.callmate.features.outbound.OutboundChatController]
 * 的 `appendSystem` 协作，由 [com.vaca.callmate.ui.screens.FeedbackChatModal] 识别并渲染富卡片。
 */
const val OUTBOUND_CONFIRM_SENTINEL_PREFIX: String = "__outbound_confirm__:"

/**
 * 与 iOS `AISecView.parseOutboundTemplateSections`（`CallMate/Features/Settings/AISecView.swift:421-475`）逐段对齐。
 * 优先解析 `#### Title ####` 格式（Phase 6 JIT-compiled call rules），识别「任务目标设定」与「背景信息」；
 * 回退到老的 `处理目标：/ 处理要点：` 冒号分段。两者都不命中时 goal=null、points=原文（供卡片兜底展示）。
 */
fun parseOutboundTemplateSections(text: String): Pair<String?, String?> {
    val remaining = text.trim()
    if (remaining.isEmpty()) return null to null

    val labelMapping = extractVariableLabelMapping(remaining)

    val hashSections = parseHashHeaderSections(remaining)
    if (hashSections.isNotEmpty()) {
        val goalRaw = hashSections["任务目标设定"]?.trim().orEmpty()
        val goal = goalRaw.ifEmpty { null }
        val bgRaw = hashSections["背景信息"]?.trim()
        val pointsRaw = extractBackgroundInfoLines(bgRaw, labelMapping).orEmpty()
        val points = pointsRaw.ifEmpty { null }
        if (goal != null || points != null) {
            return goal to points
        }
    }

    val sectionTitles = listOf("处理目标", "处理要点", "处理原则", "处理策略", "处理步骤", "示例")

    fun findMarker(title: String): Int {
        val i1 = remaining.indexOf("$title：")
        if (i1 >= 0) return i1
        val i2 = remaining.indexOf("$title:")
        if (i2 >= 0) return i2
        return -1
    }

    fun extractContent(title: String): String? {
        val idx = findMarker(title)
        if (idx < 0) return null
        val startRel = if (remaining[idx + title.length] == '：') idx + title.length + 1 else idx + title.length + 1
        var end = remaining.length
        for (other in sectionTitles) if (other != title) {
            val o = findMarker(other)
            if (o in (idx + 1) until end) end = o
        }
        val body = remaining.substring(startRel, end).trim()
        return body.ifEmpty { null }
    }

    val goal = extractContent("处理目标")
    val points = extractContent("处理要点")
        ?: extractContent("处理原则")
        ?: extractContent("处理策略")
        ?: extractContent("处理步骤")

    if (goal == null && points == null) {
        return null to remaining
    }
    return goal to points
}

/** 与 iOS `parseHashHeaderSections` 对齐：`#### 标题 ####` → content 段映射 */
private fun parseHashHeaderSections(text: String): Map<String, String> {
    val regex = Regex("####\\s*(.+?)\\s*####")
    val matches = regex.findAll(text).toList()
    if (matches.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for ((i, m) in matches.withIndex()) {
        val title = m.groupValues[1].trim()
        val start = m.range.last + 1
        val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
        if (start > end) continue
        out[title] = text.substring(start, end).trim()
    }
    return out
}

/**
 * 与 iOS `extractVariableLabelMapping` 对齐：从模板顶部 JSON（`routing_variables` / `business_variables`）抽
 * `name`→`description` 或 `key`→`label` 的映射。用于把「背景信息」里的变量 key 翻译成人类可读 label。
 */
private fun extractVariableLabelMapping(templateContent: String): Map<String, String> {
    val mapping = LinkedHashMap<String, String>()

    val leadingJson = extractLeadingJSON(templateContent)
    if (leadingJson != null) {
        try {
            val root = JSONObject(leadingJson)
            for (key in listOf("routing_variables", "business_variables")) {
                val arr = root.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    val name = (v.optString("name").takeIf { it.isNotEmpty() } ?: v.optString("key")).orEmpty()
                    val label = (v.optString("description").takeIf { it.isNotEmpty() } ?: v.optString("label")).orEmpty()
                    if (name.isNotEmpty() && label.isNotEmpty() && !mapping.containsKey(name)) {
                        mapping[name] = label
                    }
                }
            }
        } catch (_: Exception) { /* fall through to regex fallback */ }
    }

    if (mapping.isEmpty()) {
        val pattern = Regex("\"(?:name|key)\"\\s*:\\s*\"([^\"]+)\"[^}]*\"(?:description|label)\"\\s*:\\s*\"([^\"]+)\"")
        for (m in pattern.findAll(templateContent)) {
            val k = m.groupValues[1]
            val l = m.groupValues[2]
            if (!mapping.containsKey(k)) mapping[k] = l
        }
    }

    return mapping
}

/** 与 iOS `extractLeadingJSON` 对齐：取文本顶部第一个完整的 `{...}` JSON 对象 */
private fun extractLeadingJSON(text: String): String? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("{")) return null
    var depth = 0
    for ((i, ch) in trimmed.withIndex()) {
        when (ch) {
            '{' -> depth += 1
            '}' -> depth -= 1
        }
        if (depth == 0) return trimmed.substring(0, i + 1)
    }
    return null
}

/**
 * 与 iOS `extractBackgroundInfoLines` 对齐（`CallMate/Features/Settings/AISecView.swift`）：
 * 把「背景信息」区块内形如 `- {{variable_key}}：value` 的行转成 `- 人类可读 label：value`。
 */
private fun extractBackgroundInfoLines(
    bgRaw: String?,
    labelMapping: Map<String, String>,
): String? {
    val text = bgRaw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    val out = StringBuilder()
    val varRegex = Regex("\\{\\{([^}]+)}}")
    for (line in lines) {
        val mapped = varRegex.replace(line) { m ->
            val key = m.groupValues[1].trim()
            labelMapping[key] ?: key
        }
        if (out.isNotEmpty()) out.append('\n')
        out.append(mapped)
    }
    return out.toString().ifEmpty { null }
}

/** JSON 组装 helper：仅在此 file 内用来避免把 `put` 调用散到多处 */
@Suppress("unused")
private fun JSONObject.putIfNotNull(key: String, value: Any?): JSONObject {
    if (value != null) put(key, value)
    return this
}

@Suppress("unused")
private fun JSONArray.toStringList(): List<String> {
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out.add(optString(i))
    return out
}
