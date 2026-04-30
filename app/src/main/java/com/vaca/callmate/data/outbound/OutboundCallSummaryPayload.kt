package com.vaca.callmate.data.outbound

import org.json.JSONObject

/** call_outbound 场景服务端 summary JSON（plan §5.2）解析结果 */
data class OutboundCallSummaryPayload(
    val title: String,
    val outcome: String,
    val result: String,
    val actionRequired: String,
    val summary: String,
    val keyInfoLines: List<String>,
)

fun parseOutboundCallSummaryPayload(raw: String?): OutboundCallSummaryPayload? {
    if (raw.isNullOrBlank()) return null
    val t = raw.trim()
    return runCatching {
        val jo = JSONObject(t)
        val outcome = jo.optString("outcome", "").trim()
        if (outcome.isEmpty()) return null
        val keyLines = ArrayList<String>()
        val arr = jo.optJSONArray("key_info")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val keys = item.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    keyLines += "$k: ${item.optString(k)}"
                }
            }
        }
        OutboundCallSummaryPayload(
            title = jo.optString("title", "").trim(),
            outcome = outcome,
            result = jo.optString("result", "").trim(),
            actionRequired = jo.optString("action_required", "").trim(),
            summary = jo.optString("summary", "").trim(),
            keyInfoLines = keyLines,
        )
    }.getOrNull()
}
