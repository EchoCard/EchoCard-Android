package com.vaca.callmate.features.outbound

import android.util.Log
import com.vaca.callmate.CallMateApplication
import com.vaca.callmate.core.network.BackendAuthManager
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.outbound.OutboundContact
import com.vaca.callmate.data.outbound.OutboundTaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 与 iOS [ControlChannelService] 对齐：**无长连接 WebSocket**；MCU device-id + JWT 就绪后
 * [activate] 上报 FCM 占位（[BackendAuthManager.syncPushRegistration]），远程指令经推送
 * `event=command` 下发，由 [handleRemoteNotificationPayload] 解析并调用 [OutboundTaskQueueService]。
 */
class ControlChannelService(
    private val app: CallMateApplication
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = AppPreferences(app)
    private val bleManager get() = app.bleManager
    private val queue get() = app.outboundTaskQueueService

    private val _foregroundBoostActive = MutableStateFlow(false)
    /** iOS 无控制通道前台抬升；恒为 false，仅保留类型与 [CallForegroundController] 兼容。 */
    val foregroundBoostActive: StateFlow<Boolean> = _foregroundBoostActive.asStateFlow()

    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val recentRequestIdOrder = ArrayDeque<String>()
    private val recentRequestIdSet = HashSet<String>()
    private val maxTrackedRequestIds = 200

    fun activate() {
        scope.launch(Dispatchers.IO) {
            activateAsync()
        }
    }

    fun deactivate() {
        _lastError.value = null
        _isRegistered.value = false
    }

    private suspend fun activateAsync() {
        val deviceId = bleManager.runtimeMCUDeviceID.value?.trim().orEmpty()
        if (deviceId.isEmpty()) {
            _lastError.value = "mcu_not_ready"
            _isRegistered.value = false
            Log.w(TAG, "activate skipped: MCU not connected or device-id not synced yet")
            return
        }
        val token = BackendAuthManager.ensureToken(preferences)
        if (token == null || !BackendAuthManager.looksLikeJWT(token)) {
            _lastError.value = "token_missing"
            _isRegistered.value = false
            Log.w(TAG, "activate skipped: no JWT token (bootstrap first)")
            return
        }
        val ok = BackendAuthManager.syncPushRegistration(preferences, fcmToken = null)
        if (ok) {
            _lastError.value = null
            _isRegistered.value = true
            Log.i(TAG, "activate OK Device-Id=$deviceId (MCU) + push registration synced")
        } else {
            _lastError.value = "push_register_failed"
            _isRegistered.value = false
        }
    }

    /**
     * 后台/前台收到数据消息时调用（`event` == `command`）。FCM 接入后由 MessagingService 转发至此。
     */
    fun handleRemoteNotificationPayload(userInfo: Map<String, Any?>) {
        scope.launch(Dispatchers.IO) {
            val merged = flattenPayload(userInfo)
            val event = merged["event"] as? String ?: return@launch
            if (event != "command") return@launch

            val requestId = (merged["request_id"] as? String).orEmpty()
            if (requestId.isNotEmpty()) {
                if (requestId in recentRequestIdSet) {
                    Log.i(TAG, "duplicate request_id=$requestId, skip")
                    return@launch
                }
                recentRequestIdSet.add(requestId)
                recentRequestIdOrder.addLast(requestId)
                while (recentRequestIdOrder.size > maxTrackedRequestIds) {
                    val old = recentRequestIdOrder.removeFirstOrNull() ?: break
                    recentRequestIdSet.remove(old)
                }
            }

            val action = (merged["action"] as? String)
                ?: (merged["type"] as? String)
                ?: return@launch

            val json = payloadToJson(merged)
            handleAction(action, requestId, json)
        }
    }

    private fun flattenPayload(userInfo: Map<String, Any?>): MutableMap<String, Any?> {
        val merged = LinkedHashMap<String, Any?>()
        userInfo.forEach { (k, v) -> merged[k] = v }
        @Suppress("UNCHECKED_CAST")
        val params = merged["params"] as? Map<String, Any?>
        params?.forEach { (k, v) -> merged[k] = v }
        return merged
    }

    private fun payloadToJson(merged: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in merged) {
            if (v == null) continue
            when (v) {
                is String -> {
                    when (k) {
                        "contacts" -> {
                            val t = v.trimStart()
                            if (t.startsWith("[")) {
                                try {
                                    o.put(k, JSONArray(v))
                                } catch (_: Exception) {
                                    o.put(k, v)
                                }
                            } else {
                                o.put(k, v)
                            }
                        }
                        else -> o.put(k, v)
                    }
                }
                is JSONArray -> o.put(k, v)
                is JSONObject -> o.put(k, v)
                is Number -> o.put(k, v)
                is Boolean -> o.put(k, v)
                else -> o.put(k, v.toString())
            }
        }
        return o
    }

    private suspend fun handleAction(action: String, requestId: String, json: JSONObject) {
        when (action) {
            "task.create" -> {
                val prompt = json.optString("prompt", "").trim()
                val contactsRaw = json.optJSONArray("contacts")
                if (prompt.isEmpty() || contactsRaw == null) return
                val contacts = mutableListOf<OutboundContact>()
                for (i in 0 until contactsRaw.length()) {
                    val c = contactsRaw.optJSONObject(i) ?: continue
                    val phone = c.optString("phone", "").trim()
                    if (phone.isEmpty()) continue
                    val name = c.optString("name", "").ifBlank { phone }
                    contacts.add(OutboundContact(phone = phone, name = name))
                }
                if (contacts.isEmpty()) return
                val promptType = json.optString("prompt_type", "apns").ifEmpty { "apns" }
                val scheduledAtStr = json.optString("scheduled_at", "").ifEmpty { null }
                val scheduledAtMillis = scheduledAtStr?.let { parseIsoToMillis(it) }
                val callFrequency = json.optInt("call_frequency", 30).coerceAtLeast(1)
                val redialMissed = json.optBoolean("redial_missed", false)
                queue.createTaskForControl(
                    promptType = promptType,
                    prompt = prompt,
                    contacts = contacts,
                    scheduledAtMillis = scheduledAtMillis,
                    callFrequency = callFrequency,
                    redialMissed = redialMissed
                )
            }
            "task.delete" -> {
                val taskIdStr = json.optString("task_id", "")
                val taskId = runCatching { UUID.fromString(taskIdStr) }.getOrNull() ?: return
                queue.deleteTask(taskId)
            }
            "task.update" -> {
                val taskIdStr = json.optString("task_id", "")
                val taskId = runCatching { UUID.fromString(taskIdStr) }.getOrNull() ?: return
                val scheduledAtStr = json.optString("scheduled_at", "").ifEmpty { null }
                val scheduledAtMillis = scheduledAtStr?.let { parseIsoToMillis(it) }
                var contacts: List<OutboundContact>? = null
                if (json.has("contacts")) {
                    val arr = json.optJSONArray("contacts")
                    if (arr != null && arr.length() > 0) {
                        val list = mutableListOf<OutboundContact>()
                        for (i in 0 until arr.length()) {
                            val c = arr.optJSONObject(i) ?: continue
                            val phone = c.optString("phone", "")
                            if (phone.isEmpty()) continue
                            val name = c.optString("name", "").ifBlank { phone }
                            list.add(OutboundContact(phone = phone, name = name))
                        }
                        contacts = if (list.isEmpty()) null else list
                    }
                }
                val prompt = json.optString("prompt", "").ifEmpty { null }
                val promptType = json.optString("prompt_type", "").ifEmpty { null }
                val callFrequency: Int? =
                    if (json.has("call_frequency")) json.optInt("call_frequency") else null
                val redialMissed: Boolean? =
                    if (json.has("redial_missed")) json.optBoolean("redial_missed") else null
                queue.updateTask(
                    taskId = taskId,
                    scheduledAtMillis = scheduledAtMillis,
                    contacts = contacts,
                    prompt = prompt,
                    promptType = promptType,
                    callFrequency = callFrequency,
                    redialMissed = redialMissed
                )
            }
            "task.list" -> {
                val statusStr = json.optString("status", "").ifEmpty { null }
                val status = statusStr?.let { OutboundTaskStatus.fromApiString(it) }
                val list = queue.listTasks(status)
                val arr = JSONArray()
                for (t in list) {
                    arr.put(queue.outboundTaskToJson(t))
                }
                BackendAuthManager.postControlCallback(
                    preferences,
                    requestId,
                    JSONObject().put("tasks", arr)
                )
            }
            "task.get" -> {
                val taskIdStr = json.optString("task_id", "")
                val taskId = runCatching { UUID.fromString(taskIdStr) }.getOrNull() ?: return
                val task = queue.getTask(taskId)
                val payload = if (task == null) {
                    JSONObject().put("task", JSONObject.NULL)
                } else {
                    JSONObject().put("task", queue.outboundTaskToJson(task))
                }
                BackendAuthManager.postControlCallback(preferences, requestId, payload)
            }
            "task.report" -> {
                val taskIdStr = json.optString("task_id", "")
                val taskId = runCatching { UUID.fromString(taskIdStr) }.getOrNull() ?: return
                if (queue.getTask(taskId) == null) {
                    BackendAuthManager.postControlCallback(
                        preferences,
                        requestId,
                        JSONObject().put("contacts", JSONArray())
                    )
                    return
                }
                val contactsPayload = queue.buildTaskReportContactsJson(taskId)
                BackendAuthManager.postControlCallback(
                    preferences,
                    requestId,
                    JSONObject().put("contacts", contactsPayload)
                )
            }
            "task.run" -> {
                val taskIdStr = json.optString("task_id", "")
                val taskId = runCatching { UUID.fromString(taskIdStr) }.getOrNull() ?: return
                queue.executeTask(taskId)
            }
            "task.cancel" -> {
                val taskIdStr = json.optString("task_id", "")
                val taskId = runCatching { UUID.fromString(taskIdStr) }.getOrNull() ?: return
                queue.cancelTask(taskId)
            }
            "dial" -> {
                val phone = json.optString("phone", "").trim()
                val prompt = json.optString("prompt", "")
                if (phone.isEmpty() || prompt.isEmpty()) return
                queue.dialOncePersisted(phone, prompt)
            }
            else -> Log.w(TAG, "unknown action: $action")
        }
    }

    private fun parseIsoToMillis(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return try {
            java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.Instant.parse(t).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        private const val TAG = "ControlPush"
    }
}
