package com.vaca.callmate.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 与 iOS `AbnormalCallRecordStore` / `callmate.abnormal_call_records` 对齐：
 * 陌生来电未由 AI 代接时的诊断记录（时间 + 原因）。
 */
data class AbnormalCallRecord(
    val id: String,
    val dateEpochMs: Long,
    val reasonCode: String,
    val detail: String?
)

class AbnormalCallRecordStore private constructor(prefs: SharedPreferences) {

    private val prefs: SharedPreferences = prefs
    private val _records = MutableStateFlow<List<AbnormalCallRecord>>(emptyList())
    val records: StateFlow<List<AbnormalCallRecord>> = _records.asStateFlow()

    init {
        load()
    }

    companion object {
        private const val PREFS = "callmate_abnormal_call_records"
        private const val KEY_JSON = "callmate.abnormal_call_records"
        private const val MAX_RECORDS = 200
        private const val MAX_DETAIL = 120

        @Volatile
        private var instance: AbnormalCallRecordStore? = null

        fun getInstance(context: Context): AbnormalCallRecordStore {
            return instance ?: synchronized(this) {
                instance ?: AbnormalCallRecordStore(
                    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }
    }

    fun append(reasonCode: String, detail: String? = null) {
        var trimmed: String? = detail?.takeIf { it.isNotEmpty() }
        if (trimmed != null && trimmed.length > MAX_DETAIL) {
            trimmed = trimmed.take(MAX_DETAIL) + "…"
        }
        val rec = AbnormalCallRecord(
            id = UUID.randomUUID().toString(),
            dateEpochMs = System.currentTimeMillis(),
            reasonCode = reasonCode,
            detail = trimmed
        )
        val next = listOf(rec) + _records.value
        _records.value = next.take(MAX_RECORDS)
        save()
    }

    fun clear() {
        _records.value = emptyList()
        save()
    }

    private fun load() {
        val s = prefs.getString(KEY_JSON, null) ?: return
        try {
            val arr = JSONArray(s)
            val list = ArrayList<AbnormalCallRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    AbnormalCallRecord(
                        id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                        dateEpochMs = o.optLong("dateEpochMs", System.currentTimeMillis()),
                        reasonCode = o.optString("reasonCode"),
                        detail = o.optString("detail").takeIf { it.isNotEmpty() }
                    )
                )
            }
            _records.value = list
        } catch (_: Exception) {
            _records.value = emptyList()
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (r in _records.value) {
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("dateEpochMs", r.dateEpochMs)
                    put("reasonCode", r.reasonCode)
                    if (r.detail != null) put("detail", r.detail)
                }
            )
        }
        prefs.edit().putString(KEY_JSON, arr.toString()).apply()
    }
}
