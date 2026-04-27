package com.vaca.callmate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * 与 iOS ProcessStrategyStore 对齐：来电处理策略 JSON 持久化（key: ws_process_strategy）
 */
private val Context.strategyDataStore: DataStore<Preferences> by preferencesDataStore(name = "callmate_strategy")

/** 与 iOS `ProcessStrategyChange` 对齐：用于 `display_rule_change` 确认后应用 */
data class ProcessStrategyChange(
    val type: String,
    val rule: String,
    val action: String,
)

data class ProcessStrategyRule(
    val id: Int,
    val type: String,
    val rule: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("rule", rule)
    }
    companion object {
        fun fromJson(obj: JSONObject): ProcessStrategyRule = ProcessStrategyRule(
            id = obj.optInt("id", 0),
            type = obj.optString("type", ""),
            rule = obj.optString("rule", "")
        )
    }
}

object ProcessStrategyStore {

    private const val KEY = "ws_process_strategy"

    fun processStrategyJSONFlow(context: Context): Flow<String?> =
        context.strategyDataStore.data.map { prefs ->
            prefs[stringPreferencesKey(KEY)]
        }

    suspend fun processStrategyJSONString(context: Context): String? {
        ensureDefaultIfNeeded(context)
        return context.strategyDataStore.data.first()[stringPreferencesKey(KEY)]
    }

    suspend fun ensureDefaultIfNeeded(context: Context) {
        val existing = context.strategyDataStore.data.first()[stringPreferencesKey(KEY)]?.trim()
        if (!existing.isNullOrEmpty()) return
        val json = encodeRules(defaultRules())
        if (json != null) setStrategyJSONString(context, json)
    }

    suspend fun resetToDefault(context: Context) {
        val json = encodeRules(defaultRules()) ?: return
        context.strategyDataStore.edit { it.remove(stringPreferencesKey(KEY)) }
        setStrategyJSONString(context, json)
    }

    suspend fun loadRules(context: Context): List<ProcessStrategyRule> {
        val json = context.strategyDataStore.data.first()[stringPreferencesKey(KEY)] ?: return defaultRules()
        return decodeRules(json) ?: defaultRules()
    }

    suspend fun saveRules(context: Context, rules: List<ProcessStrategyRule>) {
        encodeRules(rules)?.let { setStrategyJSONString(context, it) }
    }

    /**
     * 与 iOS `ProcessStrategyStore.applyChanges` 对齐：按 type 匹配规则并 add/update/delete。
     */
    suspend fun applyChanges(context: Context, changes: List<ProcessStrategyChange>) {
        if (changes.isEmpty()) return
        var rules = loadRules(context).toMutableList()
        var nextId = (rules.maxOfOrNull { it.id } ?: 0) + 1
        for (change in changes) {
            val action = change.action.lowercase()
            if (action == "delete") {
                rules.removeAll { it.type == change.type }
                continue
            }
            val idx = rules.indexOfFirst { it.type == change.type }
            if (idx >= 0) {
                if (action == "update" || action == "add") {
                    val cur = rules[idx]
                    rules[idx] = ProcessStrategyRule(id = cur.id, type = change.type, rule = change.rule)
                }
            } else {
                if (action == "add" || action == "update") {
                    rules.add(ProcessStrategyRule(id = nextId, type = change.type, rule = change.rule))
                    nextId += 1
                }
            }
        }
        saveRules(context, rules)
    }

    suspend fun saveProcessStrategyJSONIfValid(context: Context, json: String): Boolean {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return false
        if (decodeRules(trimmed) == null) return false
        setStrategyJSONString(context, trimmed)
        return true
    }

    fun validateProcessStrategyJSON(json: String): Boolean {
        return decodeRules(json.trim()) != null
    }

    private suspend fun setStrategyJSONString(context: Context, json: String) {
        context.strategyDataStore.edit {
            it[stringPreferencesKey(KEY)] = json
        }
    }

    private fun encodeRules(rules: List<ProcessStrategyRule>): String? = try {
        JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString()
    } catch (_: Exception) { null }

    private fun decodeRules(json: String): List<ProcessStrategyRule>? = try {
        val arr = JSONArray(json)
        List(arr.length()) { i -> ProcessStrategyRule.fromJson(arr.getJSONObject(i)) }
    } catch (_: Exception) { null }

    private fun defaultRules(): List<ProcessStrategyRule> = listOf(
        ProcessStrategyRule(1, "外卖/骑手", "处理目标：在不额外暴露信息的前提下，完成交付指引。\n处理要点：1) 不主动提供地址信息 2) 若对方已准确说出地址，仅作确认 3) 给出统一、预设的放置方式"),
        ProcessStrategyRule(2, "快递/驿站/派件", "处理目标：只处理投递方式，不处理任何身份或隐私验证。\n可确认快递公司名称；告知统一投递方式；不提供身份证、验证码等信息。"),
        ProcessStrategyRule(3, "运营商（移动/联通/电信）", "处理目标：明确拒绝，不进入讨论。示例：「我这边不办理，谢谢。」"),
        ProcessStrategyRule(4, "银行/保险/贷款/理财", "处理目标：不确认、不核验、不办理。"),
        ProcessStrategyRule(5, "营销/推销/房产/课程/广告", "处理目标：明确拒绝 + 要求不再来电/删除外呼名单；若重复拨打，直接警告将投诉。"),
        ProcessStrategyRule(6, "熟人来电（系统已识别为有姓名的来电）", "处理目标：模拟真实人类代接熟人电话，自然、克制、以转达为主。"),
        ProcessStrategyRule(7, "未归类来电（兜底分流规则）", "处理目标：不尝试解决问题本身；只做信息确认与转达。")
    )
}
