package com.vaca.callmate.features.outbound

import android.content.Context
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.dialPhoneNumber
import com.vaca.callmate.data.outbound.OutboundContact
import com.vaca.callmate.data.outbound.OutboundCreateTaskSubmission
import com.vaca.callmate.data.outbound.OutboundDialRiskControl
import com.vaca.callmate.data.outbound.OutboundDialRiskReason
import com.vaca.callmate.data.outbound.OutboundTask
import com.vaca.callmate.data.outbound.OutboundTaskJsonStore
import com.vaca.callmate.data.outbound.OutboundTaskStatus
import com.vaca.callmate.data.repository.CallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * 与 iOS `OutboundTaskQueueService` 对齐：任务 JSON 持久化 + 执行循环（拨号侧为简化版，会话级等待后续与 Telephony 对齐）。
 */
class OutboundTaskQueueService(
    private val appContext: Context,
    private val callRepository: CallRepository,
    private val bleManager: BleManager,
    private val languageRaw: () -> String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _runningTaskIds = MutableStateFlow<Set<UUID>>(emptySet())
    val runningTaskIds: StateFlow<Set<UUID>> = _runningTaskIds.asStateFlow()
    private var runJob: Job? = null

    private val outboundSummaryPrefix = "[OUTBOUND_TASK]"

    fun createTask(submission: OutboundCreateTaskSubmission): UUID? {
        if (submission.contacts.isEmpty()) return null
        val task = OutboundTask(
            id = UUID.randomUUID(),
            promptType = submission.promptName,
            promptRule = submission.promptContent,
            contacts = submission.contacts,
            scheduledAt = submission.scheduledAtMillis,
            status = if (submission.scheduledAtMillis == null) {
                OutboundTaskStatus.Running
            } else {
                OutboundTaskStatus.Scheduled
            },
            dialSuccessCount = 0,
            dialFailureCount = 0,
            callFrequency = submission.callFrequency.coerceAtLeast(1),
            redialMissed = submission.redialMissed,
            createdAt = System.currentTimeMillis()
        )
        val list = OutboundTaskJsonStore.load(appContext).toMutableList()
        list.add(task)
        OutboundTaskJsonStore.save(appContext, list)
        if (submission.scheduledAtMillis == null) {
            executeTask(task.id)
        }
        return task.id
    }

    fun deleteTask(taskId: UUID): Boolean {
        if (_runningTaskIds.value.contains(taskId)) {
            runJob?.cancel()
            runJob = null
        }
        val list = OutboundTaskJsonStore.load(appContext).toMutableList()
        val removed = list.removeAll { it.id == taskId }
        if (removed) OutboundTaskJsonStore.save(appContext, list)
        return removed
    }

    fun executeDueScheduledTasks() {
        val now = System.currentTimeMillis()
        val tasks = OutboundTaskJsonStore.load(appContext)
        for (t in tasks) {
            if (t.status == OutboundTaskStatus.Scheduled && t.scheduledAt != null && t.scheduledAt <= now) {
                executeTask(t.id)
            }
        }
    }

    fun executeTask(taskID: UUID) {
        if (runJob?.isActive == true) return
        if (_runningTaskIds.value.contains(taskID)) return
        val list = OutboundTaskJsonStore.load(appContext).toMutableList()
        val idx = list.indexOfFirst { it.id == taskID }
        if (idx < 0) return
        if (list[idx].status == OutboundTaskStatus.Completed) return
        list[idx].status = OutboundTaskStatus.Running
        list[idx].dialSuccessCount = 0
        list[idx].dialFailureCount = 0
        OutboundTaskJsonStore.save(appContext, list)
        val snapshot = list[idx]
        _runningTaskIds.value = _runningTaskIds.value + taskID
        runJob = scope.launch {
            try {
                runTaskLoop(taskID, snapshot)
            } finally {
                _runningTaskIds.value = _runningTaskIds.value - taskID
                runJob = null
            }
        }
    }

    private suspend fun runTaskLoop(taskID: UUID, taskSnapshot: OutboundTask) {
        val dialIntervalMs = (3600_000L / taskSnapshot.callFrequency.coerceAtLeast(1)).coerceAtLeast(1000L)
        var lastDialAt = 0L
        val finalResults = mutableMapOf<UUID, Boolean>()
        val lang = languageRaw()

        suspend fun waitSlot() {
            if (lastDialAt > 0L) {
                val elapsed = System.currentTimeMillis() - lastDialAt
                val w = dialIntervalMs - elapsed
                if (w > 0) delay(w)
            }
        }

        suspend fun dialContact(contact: OutboundContact): Boolean {
            val riskReason = OutboundDialRiskControl.evaluate(contact.phone, System.currentTimeMillis())
            if (riskReason != null) {
                callRepository.insertOutboundCallLog(
                    startedAtMillis = System.currentTimeMillis(),
                    durationSeconds = 0,
                    statusRaw = "blocked",
                    phone = contact.phone,
                    label = contact.name,
                    summary = "$outboundSummaryPrefix ${taskSnapshot.promptType} BLOCKED",
                    fullSummary = taskSnapshot.promptRule,
                    languageRaw = lang,
                    outboundTaskId = taskID.toString(),
                    errorMessage = riskReason.name
                )
                return false
            }
            waitSlot()
            lastDialAt = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                bleManager.prepareOutboundDialContext(taskID, taskSnapshot.promptRule)
                bleManager.dialPhoneNumber(contact.phone)
            }
            delay(2500)
            callRepository.insertOutboundCallLog(
                startedAtMillis = System.currentTimeMillis(),
                durationSeconds = 0,
                statusRaw = "handled",
                phone = contact.phone,
                label = contact.name,
                summary = "$outboundSummaryPrefix ${taskSnapshot.promptType}",
                fullSummary = taskSnapshot.promptRule,
                languageRaw = lang,
                outboundTaskId = taskID.toString(),
                errorMessage = null
            )
            return true
        }

        fun persistProgress() {
            val successes = taskSnapshot.contacts.count { finalResults[it.id] == true }
            val fails = taskSnapshot.contacts.count { (finalResults[it.id] != true) }
            val cur = OutboundTaskJsonStore.load(appContext).toMutableList()
            val i = cur.indexOfFirst { it.id == taskID }
            if (i >= 0) {
                cur[i].dialSuccessCount = successes
                cur[i].dialFailureCount = fails
                OutboundTaskJsonStore.save(appContext, cur)
            }
        }

        for (contact in taskSnapshot.contacts) {
            val ok = dialContact(contact)
            finalResults[contact.id] = ok
            persistProgress()
        }

        if (taskSnapshot.redialMissed) {
            val pending = taskSnapshot.contacts.filter { finalResults[it.id] != true }
            for (contact in pending) {
                val ok = dialContact(contact)
                finalResults[contact.id] = ok
                persistProgress()
            }
        }

        val success = taskSnapshot.contacts.count { finalResults[it.id] == true }
        val failure = taskSnapshot.contacts.size - success
        val cur = OutboundTaskJsonStore.load(appContext).toMutableList()
        val i = cur.indexOfFirst { it.id == taskID }
        if (i < 0) return
        cur[i].dialSuccessCount = success
        cur[i].dialFailureCount = failure
        cur[i].status = when {
            failure == 0 -> OutboundTaskStatus.Completed
            success == 0 -> OutboundTaskStatus.Failed
            else -> OutboundTaskStatus.Partial
        }
        OutboundTaskJsonStore.save(appContext, cur)
    }

    // --- 与 iOS `OutboundTaskQueueService` 对齐，供推送 [ControlChannelService] 使用 ---

    fun createTaskForControl(
        promptType: String,
        prompt: String,
        contacts: List<OutboundContact>,
        scheduledAtMillis: Long?,
        callFrequency: Int,
        redialMissed: Boolean
    ): UUID? {
        if (contacts.isEmpty()) return null
        val sub = OutboundCreateTaskSubmission(
            promptName = promptType,
            promptContent = prompt,
            contacts = contacts,
            scheduledAtMillis = scheduledAtMillis,
            status = if (scheduledAtMillis == null) OutboundTaskStatus.Running else OutboundTaskStatus.Scheduled,
            callFrequency = callFrequency.coerceAtLeast(1),
            redialMissed = redialMissed
        )
        return createTask(sub)
    }

    fun updateTask(
        taskId: UUID,
        scheduledAtMillis: Long? = null,
        contacts: List<OutboundContact>? = null,
        prompt: String? = null,
        promptType: String? = null,
        callFrequency: Int? = null,
        redialMissed: Boolean? = null
    ): Boolean {
        val list = OutboundTaskJsonStore.load(appContext).toMutableList()
        val idx = list.indexOfFirst { it.id == taskId }
        if (idx < 0) return false
        val t = list[idx]
        if (t.status != OutboundTaskStatus.Scheduled) return false
        val updated = OutboundTask(
            id = t.id,
            promptType = promptType ?: t.promptType,
            promptRule = prompt ?: t.promptRule,
            contacts = contacts ?: t.contacts,
            scheduledAt = scheduledAtMillis ?: t.scheduledAt,
            status = t.status,
            dialSuccessCount = t.dialSuccessCount,
            dialFailureCount = t.dialFailureCount,
            callFrequency = callFrequency?.coerceAtLeast(1) ?: t.callFrequency,
            redialMissed = redialMissed ?: t.redialMissed,
            createdAt = t.createdAt
        )
        list[idx] = updated
        OutboundTaskJsonStore.save(appContext, list)
        return true
    }

    fun listTasks(status: OutboundTaskStatus?): List<OutboundTask> {
        var list = OutboundTaskJsonStore.load(appContext)
        if (status != null) {
            list = list.filter { it.status == status }
        }
        return list
    }

    fun getTask(taskId: UUID): OutboundTask? =
        OutboundTaskJsonStore.load(appContext).firstOrNull { it.id == taskId }

    fun cancelTask(taskId: UUID): Boolean {
        if (!_runningTaskIds.value.contains(taskId)) return false
        runJob?.cancel()
        runJob = null
        _runningTaskIds.value = _runningTaskIds.value - taskId
        val list = OutboundTaskJsonStore.load(appContext).toMutableList()
        val idx = list.indexOfFirst { it.id == taskId }
        if (idx >= 0 && list[idx].status == OutboundTaskStatus.Running) {
            list[idx].status = OutboundTaskStatus.Partial
            OutboundTaskJsonStore.save(appContext, list)
        }
        return true
    }

    fun dialOncePersisted(phone: String, prompt: String): Triple<Boolean, String, UUID?> {
        val risk = OutboundDialRiskControl.evaluate(phone, System.currentTimeMillis())
        if (risk != null) {
            return Triple(false, riskMessage(risk), null)
        }
        val contact = OutboundContact(phone = phone, name = phone)
        val id = createTaskForControl(
            promptType = "ws",
            prompt = prompt,
            contacts = listOf(contact),
            scheduledAtMillis = null,
            callFrequency = 30,
            redialMissed = false
        )
        return if (id != null) Triple(true, "dial_sent", id) else Triple(false, "create failed", null)
    }

    private fun riskMessage(reason: OutboundDialRiskReason): String {
        val zh = languageRaw() == "zh"
        return when (reason) {
            OutboundDialRiskReason.EmergencyNumber ->
                if (zh) "命中紧急号码风控" else "Blocked by emergency-number risk control."
            OutboundDialRiskReason.DeepNight ->
                if (zh) "当前处于当地深夜时段，禁止外呼" else "Blocked by local deep-night window."
        }
    }

    suspend fun buildTaskReportContactsJson(taskId: UUID): JSONArray {
        val task = OutboundTaskJsonStore.load(appContext).firstOrNull { it.id == taskId }
            ?: return JSONArray()
        val logs = callRepository.getCallLogsForOutboundTask(taskId.toString())
        val contactsJson = JSONArray()
        for (contact in task.contacts) {
            val phone = contact.phone
            val name = contact.name.ifBlank { phone }
            val logsForPhone = logs.filter { it.call.phone == phone }
            val best = logsForPhone.firstOrNull { it.call.statusRaw.equals("handled", ignoreCase = true) }
                ?: logsForPhone.firstOrNull()
            val obj = JSONObject()
            obj.put("phone", phone)
            obj.put("name", name)
            if (best == null) {
                obj.put("status", "pending")
            } else {
                val status = when (best.call.statusRaw.lowercase()) {
                    "handled" -> "connected"
                    "blocked" -> "blocked"
                    "missed" -> "missed"
                    else -> "failed"
                }
                obj.put("status", status)
                best.call.errorMessage?.takeIf { it.isNotBlank() }?.let { obj.put("message", it) }
                if (status == "connected" && best.transcript.isNotEmpty()) {
                    val chat = JSONArray()
                    for (line in best.transcript.sortedBy { it.index }) {
                        val row = JSONObject()
                        row.put("sender", line.senderRaw)
                        row.put("text", line.text)
                        line.startOffsetMs?.let { row.put("start_offset_ms", it) }
                        chat.put(row)
                    }
                    obj.put("chat_record", chat)
                }
            }
            contactsJson.put(obj)
        }
        return contactsJson
    }

    fun outboundTaskToJson(task: OutboundTask): JSONObject {
        val o = JSONObject()
        o.put("task_id", task.id.toString())
        o.put("prompt_type", task.promptType)
        o.put("prompt", task.promptRule)
        val arr = JSONArray()
        for (c in task.contacts) {
            arr.put(JSONObject().put("phone", c.phone).put("name", c.name))
        }
        o.put("contacts", arr)
        task.scheduledAt?.let { o.put("scheduled_at", Instant.ofEpochMilli(it).toString()) }
        o.put("status", task.status.toApiString())
        o.put("dial_success_count", task.dialSuccessCount)
        o.put("dial_failure_count", task.dialFailureCount)
        o.put("call_frequency", task.callFrequency)
        o.put("redial_missed", task.redialMissed)
        o.put("created_at", Instant.ofEpochMilli(task.createdAt).toString())
        return o
    }
}
