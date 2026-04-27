package com.vaca.callmate.data.outbound

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
            "running" -> OutboundTaskStatus.Running
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
            createdAt = o.getLong("createdAt")
        )
    }
}
