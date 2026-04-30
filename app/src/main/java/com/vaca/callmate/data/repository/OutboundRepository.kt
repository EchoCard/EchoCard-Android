package com.vaca.callmate.data.repository

import com.vaca.callmate.data.local.OutboundContactBookDao
import com.vaca.callmate.data.local.OutboundContactBookEntity
import com.vaca.callmate.data.local.OutboundPromptTemplateDao
import com.vaca.callmate.data.local.OutboundPromptTemplateEntity
import com.vaca.callmate.data.outbound.OutboundDefaultTemplates
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** `load_template`：精确 + 模糊（名称包含查询串） */
sealed class OutboundTemplateLookup {
    data class Single(val entity: OutboundPromptTemplateEntity) : OutboundTemplateLookup()
    data object NotFound : OutboundTemplateLookup()
    data class Ambiguous(val names: List<String>) : OutboundTemplateLookup()
}

class OutboundRepository(
    private val promptDao: OutboundPromptTemplateDao,
    private val contactDao: OutboundContactBookDao
) {
    val templatesFlow: Flow<List<OutboundPromptTemplateEntity>> = promptDao.observeAll()
    val contactsFlow: Flow<List<OutboundContactBookEntity>> = contactDao.observeAll()

    suspend fun seedTemplatesIfEmpty() {
        if (promptDao.getAll().isNotEmpty()) return
        val now = System.currentTimeMillis()
        for (item in OutboundDefaultTemplates.defaults()) {
            promptDao.insert(
                OutboundPromptTemplateEntity(
                    id = UUID.randomUUID().toString(),
                    name = item.name,
                    content = item.content,
                    createdAtMillis = now,
                    updatedAtMillis = now
                )
            )
        }
    }

    suspend fun insertOrUpdateTemplate(entity: OutboundPromptTemplateEntity): OutboundPromptTemplateEntity {
        val trimmedName = entity.name.trim()
        if (trimmedName.isEmpty()) return entity
        val existing = findTemplateByName(trimmedName)
        val now = System.currentTimeMillis()
        val toSave = if (existing != null) {
            OutboundPromptTemplateEntity(
                id = existing.id,
                name = trimmedName,
                content = entity.content.trim(),
                createdAtMillis = existing.createdAtMillis,
                updatedAtMillis = now,
            )
        } else {
            val id = entity.id.ifBlank { UUID.randomUUID().toString() }
            OutboundPromptTemplateEntity(
                id = id,
                name = trimmedName,
                content = entity.content.trim(),
                createdAtMillis = if (entity.createdAtMillis > 0L) entity.createdAtMillis else now,
                updatedAtMillis = now,
            )
        }
        promptDao.insert(toSave)
        return toSave
    }

    suspend fun deleteTemplate(id: String) {
        promptDao.deleteById(id)
    }

    suspend fun insertOrUpdateContact(entity: OutboundContactBookEntity) {
        contactDao.insert(entity)
    }

    suspend fun deleteContact(id: String) {
        contactDao.deleteById(id)
    }

    suspend fun findTemplateByName(name: String): OutboundPromptTemplateEntity? {
        val n = name.trim()
        if (n.isEmpty()) return null
        return promptDao.getAll().firstOrNull { it.name.trim() == n }
    }

    suspend fun lookupTemplateByName(nameQuery: String): OutboundTemplateLookup {
        val q = nameQuery.trim()
        if (q.isEmpty()) return OutboundTemplateLookup.NotFound
        val all = promptDao.getAll()
        val exact = all.firstOrNull { it.name.trim() == q }
        if (exact != null) return OutboundTemplateLookup.Single(exact)
        val fuzzy = all.filter { tmpl ->
            tmpl.name.contains(q, ignoreCase = true)
        }
        return when {
            fuzzy.isEmpty() -> OutboundTemplateLookup.NotFound
            fuzzy.size == 1 -> OutboundTemplateLookup.Single(fuzzy.first())
            else -> OutboundTemplateLookup.Ambiguous(fuzzy.map { it.name.trim() }.sorted())
        }
    }

    /** update_config hello：`templateManifest` 数组 */
    suspend fun buildTemplateManifestJsonArray(): JSONArray {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val arr = JSONArray()
        for (e in promptDao.getAll()) {
            arr.put(
                JSONObject().apply {
                    put("name", e.name)
                    put("task_type", inferTaskTypeFromTemplateContent(e.content))
                    put("updated_at", sdf.format(Date(e.updatedAtMillis)))
                },
            )
        }
        return arr
    }

    companion object {
        fun inferTaskTypeFromTemplateContent(content: String): String {
            val first = content.lineSequence().firstOrNull { it.isNotBlank() } ?: return "General"
            val typeFromJson = runCatching {
                JSONObject(first).let { jo ->
                    jo.optString("task_type", "").takeIf { it.isNotEmpty() }
                        ?: jo.optString("taskType", "").takeIf { it.isNotEmpty() }
                }
            }.getOrNull()
            if (!typeFromJson.isNullOrBlank()) return typeFromJson
            return "General"
        }

        /**
         * 模板头部 JSON 中的 `business_variables`，供 [extractBusinessPrompt] 做 `&{key}` 替换（plan §3.3）。
         */
        fun parseBusinessVariables(templateContent: String): Map<String, String> {
            val marker = templateContent.indexOf("#### ")
            val header = (if (marker >= 0) templateContent.substring(0, marker) else "").trim()
            if (header.isEmpty() || !header.startsWith("{")) return emptyMap()
            return runCatching {
                val jo = JSONObject(header)
                val bv = jo.optJSONObject("business_variables") ?: return emptyMap()
                buildMap {
                    val keys = bv.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        when (val v = bv.opt(k)) {
                            JSONObject.NULL -> put(k, "")
                            is String -> put(k, v)
                            else -> put(k, v?.toString() ?: "")
                        }
                    }
                }
            }.getOrElse { emptyMap() }
        }

        /**
         * 与 iOS `OutboundTemplateStore.extractBusinessPrompt` 对齐（plan spec §3.3）：
         * 跳过 JSON schema 头，取 `#### ` 章节开始的正文，并做 `&{key}` 变量替换。
         */
        fun extractBusinessPrompt(
            templateContent: String,
            businessVariables: Map<String, String>? = null,
        ): String {
            val idx = templateContent.indexOf("#### ")
            val body = if (idx >= 0) templateContent.substring(idx) else templateContent
            if (businessVariables.isNullOrEmpty()) return body
            var result = body
            for ((key, value) in businessVariables) {
                result = result.replace("&{$key}", value)
            }
            return result
        }
    }
}
