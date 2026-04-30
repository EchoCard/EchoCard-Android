package com.vaca.callmate.data.outbound

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private fun JSONObject.optSummaryString(): String? =
    if (!has("summary") || isNull("summary")) null
    else optString("summary", "").trim().takeIf { it.isNotEmpty() }

private const val FILE_NAME = "outbound_tasks.json"

object OutboundTaskJsonStore {

    fun clearAll(context: Context) {
        context.filesDir.resolve(FILE_NAME).delete()
    }

    fun save(context: Context, tasks: List<OutboundTask>) {
        val arr = JSONArray()
        for (t in tasks) arr.put(taskToJson(t))
        context.filesDir.resolve(FILE_NAME).writeText(arr.toString())
    }

    /** 通话结束后写入服务端 summary JSON（plan §5、§6.1） */
    fun mergeTaskSummary(context: Context, taskId: UUID, summaryJson: String): Boolean {
        val trimmed = summaryJson.trim()
        if (trimmed.isEmpty()) return false
        val list = load(context).toMutableList()
        val i = list.indexOfFirst { it.id == taskId }
        if (i < 0) return false
        list[i] = list[i].copy(summary = trimmed)
        save(context, list)
        return true
    }

    fun load(context: Context): List<OutboundTask> {
        val f = context.filesDir.resolve(FILE_NAME)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val list = ArrayList<OutboundTask>(arr.length())
            for (i in 0 until arr.length()) {
                list.add(jsonToTask(arr.getJSONObject(i)))
            }
            for (task in list) {
                if (task.status == OutboundTaskStatus.Running) {
                    task.status = OutboundTaskStatus.Scheduled
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun taskToJson(t: OutboundTask): JSONObject = JSONObject().apply {
        put("id", t.id.toString())
        put("promptType", t.promptType)
        put("promptRule", t.promptRule)
        put("contacts", JSONArray().apply {
            for (c in t.contacts) {
                put(JSONObject().apply {
                    put("id", c.id.toString())
                    put("phone", c.phone)
                    put("name", c.name)
                })
            }
        })
        put("scheduledAt", t.scheduledAt)
        put("status", t.status.name.lowercase())
        put("dialSuccessCount", t.dialSuccessCount)
        put("dialFailureCount", t.dialFailureCount)
        put("callFrequency", t.callFrequency)
        put("redialMissed", t.redialMissed)
        put("createdAt", t.createdAt)
        t.summary?.let { put("summary", it) }
    }

    private fun jsonToTask(o: JSONObject): OutboundTask {
        val contactsArr = o.getJSONArray("contacts")
        val contacts = ArrayList<OutboundContact>(contactsArr.length())
        for (i in 0 until contactsArr.length()) {
            val c = contactsArr.getJSONObject(i)
            contacts.add(
                OutboundContact(
                    id = UUID.fromString(c.getString("id")),
                    phone = c.getString("phone"),
                    name = c.getString("name")
                )
            )
        }
        val sched = if (o.isNull("scheduledAt")) null else o.getLong("scheduledAt")
        val statusRaw = o.optString("status", "scheduled")
        val status = when (statusRaw.lowercase()) {
            "scheduled" -> OutboundTaskStatus.Scheduled
            "pending" -> OutboundTaskStatus.Pending
            "running" -> OutboundTaskStatus.Running
            "not_connected" -> OutboundTaskStatus.NotConnected
            "completed" -> OutboundTaskStatus.Completed
            "partial" -> OutboundTaskStatus.Partial
            "failed" -> OutboundTaskStatus.Failed
            else -> OutboundTaskStatus.Scheduled
        }
        return OutboundTask(
            id = UUID.fromString(o.getString("id")),
            promptType = o.getString("promptType"),
            promptRule = o.getString("promptRule"),
            contacts = contacts,
            scheduledAt = sched,
            status = status,
            dialSuccessCount = o.optInt("dialSuccessCount", 0),
            dialFailureCount = o.optInt("dialFailureCount", 0),
            callFrequency = o.optInt("callFrequency", 30),
            redialMissed = o.optBoolean("redialMissed", false),
            summary = o.optSummaryString(),
            createdAt = o.getLong("createdAt")
        )
    }
}
