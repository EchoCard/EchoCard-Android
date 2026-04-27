package com.vaca.callmate.core.ble

import android.util.Log
import com.vaca.callmate.data.IncomingCall
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

/**
 * 与 iOS `CallMateBLEClient+ControlJSON.swift` 对齐：解析合并后的 JSON 并驱动 UI / ACK。
 */
interface BleControlHost {
    val scope: CoroutineScope
    var callSid: Long?
    var activeCallSession: CallSessionToken?
    fun emitEvent(event: CallMateBleEvent)
    fun emitIncomingCall(call: IncomingCall)
    fun emitCallState(state: String)
    fun sendJsonRpc(method: String, params: JSONObject, expectAck: Boolean)
    fun onAckHandled(cmd: String, sid: Long?, id: Int?)
    fun applyAckSideEffects(cmd: String, result: Int)
    fun onResolveCmdForJsonRpcId(id: Int): String
    fun setDeviceBattery(value: Int?)
    fun setDeviceCharging(value: Boolean?)
    fun setDeviceInfoCounter(value: Int?)
    fun setDeviceFirmware(value: String?)
    fun setDeviceHfp(value: String?)
    fun setDeviceBleBond(value: String?)
    fun setDeviceChipName(value: String?)
    fun setDeviceLedFromInfo(enabled: Boolean?, brightness: Int?, pa20High: Boolean?)
    fun setDeviceAncsFromDeviceInfo(enabled: Boolean)
    fun setAncsStatus(enabled: Boolean, incrementVerify: Boolean)
    fun setFlashdbUsage(usage: FlashDBUsage?)
    fun setFlashdbLast(result: Int, message: String)
    fun setDeviceDiagnostics(d: DeviceDiagnostics)
    fun setMcuCrashLogState(state: McuCrashLogState)
    fun setHfpPairingNeeded(needed: Boolean)

    /** 与 iOS `reg_dump_*` 对齐；默认空实现，可在 [BleManager] 中覆盖。 */
    fun onRegDumpMeta(json: JSONObject) = Unit
    fun onRegDumpChunk(json: JSONObject) = Unit

    /** `kv_rsp` / `kv_chunk`：由 [BleKvSyncCoordinator] 处理（避免与旧版 handleKv 重复 emit）。 */
    fun onKvResponse(json: JSONObject) = Unit
    fun onKvChunkMessage(json: JSONObject) = Unit

    /** 与 iOS `call_state` active 时停 speed test 对齐。 */
    fun onCallStateBecameActive() = Unit

    /** `ended` / `rejected` / `phone_handled`：停 call rate monitor（与 iOS 一致）。 */
    fun onCallStateTerminated(state: String) = Unit

    /** MCU `call_state(outgoing_answered)`：对方接听，与 iOS `handleBLECallStateOutgoingAnswered` 对齐。 */
    fun onOutgoingAnswered() = Unit

    /** MCU 在 SCO/编码链就绪后上报，晚于 `audio_start` JSON-RPC ACK（后者可能在 SCO 前返回）。 */
    fun onCallStateAudioStreamingReady() = Unit

    /**
     * 与 iOS `handleBLEIncomingCall` 门禁一致：返回 false 时已发送 `ignore` 且不应再展示来电 UI。
     */
    fun onIncomingCallGate(call: IncomingCall): Boolean = true

    /** PBAP 解析出联系人后：取消已排队的自动接听 / WS / 实时转写（与门禁一致）。 */
    fun cancelIncomingAiTakeover(reason: String) = Unit

    /** 收起来电全屏 Live UI（与 [BleManager.requestDismissIncomingCallUi] 对齐）。 */
    fun requestDismissIncomingCallUi() = Unit

    /** 延迟测试环回时 MCU `call_state(active)` 可能与缓存 sid 不一致，与 iOS `latencyTestEchoMode` 对齐。 */
    fun isLatencyTestEchoMode(): Boolean = false
}

class BleControlDispatch(private val host: BleControlHost) {

    companion object {
        private const val TAG = "CallMateBLE"
    }

    fun dispatchLine(merged: JSONObject) {
        val isJsonRpc = merged.optString("jsonrpc", "") == "2.0"

        if (isJsonRpc &&
            !merged.has("method") &&
            (merged.has("result") || merged.has("error"))
        ) {
            val id = BleJsonHelpers.parseJsonRpcId(merged) ?: return
            val cmd = host.onResolveCmdForJsonRpcId(id)
            val result = BleJsonHelpers.parseJsonRpcResult(merged)
            host.applyAckSideEffects(cmd, result)
            host.onAckHandled(cmd, null, id)
            host.emitEvent(CallMateBleEvent.Ack(cmd, result))
            return
        }

        // Chunk data may arrive as merged kv_rsp-shaped JSON (e.g. root type kv_rsp + params.data_b64
        // without params.type), which would otherwise hit kv_rsp and be treated as a duplicate header.
        val cmdEarly = merged.optString("cmd", "")
        if (cmdEarly == "kv_get" &&
            merged.optInt("index", -1) >= 0 &&
            merged.optString("data_b64", "").isNotEmpty()
        ) {
            host.onKvChunkMessage(merged)
            return
        }

        val type = merged.optString("type", "")
            .ifEmpty { merged.optString("method", "") }
            .ifEmpty { merged.optString("t", "") }

        when (type) {
            "device_info" -> handleDeviceInfo(merged)
            "ancs_status" -> {
                val enabled = merged.optInt("enabled", 1) != 0
                host.setAncsStatus(enabled, incrementVerify = true)
            }
            "diag_info", "diag" -> handleDiag(merged)
            "fw_chunk_ack" -> {
                val index = merged.optInt("index", -1)
                val result = merged.optInt("result", -999)
                val received = merged.optInt("received", 0)
                val total = merged.optInt("total", 0)
                host.emitEvent(
                    CallMateBleEvent.FirmwareChunkAck(index, result, received, total)
                )
            }
            "fw_status" -> {
                val active = merged.optInt("active", 0) != 0
                val received = merged.optInt("received", 0)
                val total = merged.optInt("total", 0)
                val version = merged.optString("version", "").takeIf { it.isNotEmpty() }
                host.emitEvent(
                    CallMateBleEvent.FirmwareStatus(active, received, total, version)
                )
            }
            "fw_missing" -> handleFwMissing(merged)
            "preload_missing" -> handlePreloadMissing(merged)
            "incoming_call" -> handleIncomingCall(merged)
            "caller_resolved" -> handleCallerResolved(merged)
            "call_state" -> handleCallState(merged)
            "ack" -> handleLegacyAck(merged)
            "kv_rsp" -> host.onKvResponse(merged)
            "kv_chunk" -> host.onKvChunkMessage(merged)
            "crash_log_rsp" -> handleCrashLogRsp(merged)
            "reg_dump_meta" -> host.onRegDumpMeta(merged)
            "reg_dump_chunk" -> host.onRegDumpChunk(merged)
            else -> {}
        }
    }

    private fun handleDeviceInfo(json: JSONObject) {
        val battery = if (json.has("battery")) json.optInt("battery") else null
        val charging = json.optInt("charging", -1).takeIf { it >= 0 }?.let { it != 0 }
        val hfp = json.optString("hfp", "").takeIf { it.isNotEmpty() }
        val ble = json.optString("ble", "").takeIf { it.isNotEmpty() }
        val cnt = if (json.has("cnt")) json.optInt("cnt") else null
        val fw = json.optString("fw", "").takeIf { it.isNotEmpty() }
        val chip = json.optString("chip", "").takeIf { it.isNotEmpty() }
        val ledEnabled = if (json.has("led_enabled")) json.optInt("led_enabled") != 0 else null
        val ledBrightness = if (json.has("led_brightness")) json.optInt("led_brightness") else null
        val pa20High = if (json.has("pa20_level")) json.optInt("pa20_level") != 0 else null
        host.setDeviceBattery(battery)
        host.setDeviceCharging(charging)
        host.setDeviceInfoCounter(cnt)
        host.setDeviceFirmware(fw)
        host.setDeviceHfp(hfp)
        host.setDeviceChipName(chip)
        host.setDeviceLedFromInfo(ledEnabled, ledBrightness, pa20High)
        ble?.let { host.setDeviceBleBond(it) }
        Log.i(
            TAG,
            "device_info: battery=$battery fw=$fw ble=$ble hfp=$hfp chip=$chip led=$ledEnabled/$ledBrightness pa20=$pa20High"
        )
        if (json.has("ancs")) {
            host.setDeviceAncsFromDeviceInfo(json.optInt("ancs", 0) != 0)
        }
        if (hfp != null && hfp.uppercase() in listOf("CONNECTED", "ANSWERING", "IN_CALL")) {
            host.setHfpPairingNeeded(false)
        }
        host.emitEvent(CallMateBleEvent.DeviceInfo(battery, hfp, ble, cnt))
    }

    private fun handleDiag(json: JSONObject) {
        fun intVal(vararg keys: String): Int? {
            for (k in keys) {
                if (!json.has(k)) continue
                if (json.isNull(k)) continue
                return json.optInt(k, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                    ?: json.optLong(k).toInt()
            }
            return null
        }
        fun doubleVal(vararg keys: String): Double? {
            for (k in keys) {
                if (!json.has(k)) continue
                if (json.isNull(k)) continue
                return json.optDouble(k, Double.NaN).takeIf { !it.isNaN() }
            }
            return null
        }
        val d = DeviceDiagnostics(
            cpuUsage = doubleVal("cpu_usage", "cpu"),
            uptimeMs = intVal("uptime_ms", "up"),
            cpuFreqMhz = intVal("cpu_freq_mhz", "cf"),
            heapUsedBytes = intVal("heap_used_bytes", "hu"),
            heapTotalBytes = intVal("heap_total_bytes", "ht"),
            heapPeakBytes = intVal("heap_peak_bytes", "hp"),
            sramUsedBytes = intVal("sram_used_bytes", "su"),
            sramTotalBytes = intVal("sram_total_bytes", "st"),
            psramUsedBytes = intVal("psram_used_bytes", "pu"),
            psramTotalBytes = intVal("psram_total_bytes", "pt"),
            flashdbKeys = intVal("flashdb_keys", "fk"),
            flashdbUsedBytes = intVal("flashdb_used_bytes", "fu"),
            flashdbTotalBytes = intVal("flashdb_total_bytes", "ft"),
            activeSlot = intVal("active_slot", "as"),
            activeSlotName = json.optString("active_slot_name", "")
                .ifEmpty { json.optString("asn", "") }.takeIf { it.isNotEmpty() },
            otaState = intVal("ota_state", "os"),
            bleAdvIntervalMs = intVal("adv_ms"),
            bleAdvIntervalUnits = intVal("adv_units"),
            bleConnIntervalUnits = intVal("conn_units"),
            bleTxPowerDbm = intVal("tx_pwr")?.takeIf { it != -128 },
            deepSleepAllowed = intVal("ds")
        )
        host.setDeviceDiagnostics(d)
    }

    private fun handleFwMissing(json: JSONObject) {
        val complete = json.optInt("complete", 0) != 0
        val missingChunks = json.optInt("missing_chunks", 0)
        val totalChunks = json.optInt("total_chunks", 0)
        val ranges = mutableListOf<FirmwareMissingRange>()
        val raw = json.optJSONArray("ranges")
        if (raw != null) {
            for (i in 0 until raw.length()) {
                val pair = raw.optJSONArray(i) ?: continue
                if (pair.length() >= 2) {
                    ranges.add(
                        FirmwareMissingRange(
                            pair.optInt(0, 0),
                            pair.optInt(1, 0)
                        )
                    )
                }
            }
        }
        host.emitEvent(
            CallMateBleEvent.FirmwareMissing(complete, missingChunks, totalChunks, ranges)
        )
    }

    /**
     * 与 iOS `CallMateBLEClient+ControlJSON.swift` 中 `preload_missing` 解析对齐：
     * 优先读业务字段 `filler_id`，兼容 legacy `id`（iOS 也做过同样兼容）；ranges 按
     * 半开区间 `[start, end)` 解析，非法条目静默跳过。
     */
    private fun handlePreloadMissing(json: JSONObject) {
        val fillerId = json.optString("filler_id", "").ifEmpty { json.optString("id", "") }
        if (fillerId.isBlank()) return
        val raw = json.optJSONArray("ranges") ?: return
        val ranges = mutableListOf<PreloadMissingRange>()
        for (i in 0 until raw.length()) {
            val pair = raw.optJSONArray(i) ?: continue
            if (pair.length() < 2) continue
            val start = pair.optInt(0, -1)
            val end = pair.optInt(1, -1)
            if (start < 0 || end <= start) continue
            ranges.add(PreloadMissingRange(start, end))
        }
        host.emitEvent(CallMateBleEvent.PreloadMissing(fillerId, ranges))
    }

    private fun handleIncomingCall(json: JSONObject) {
        val uid = json.optInt("uid", 0)
        val sid = BleJsonHelpers.parseSid(json)
        val title = json.optString("title", "")
        val caller = json.optString("caller", "")
        val rawNumber = json.optString("number", "")
        val resolvedTitle = if (title.isBlank()) caller else title
        val resolvedNumber = BleJsonHelpers.resolveIncomingNumber(rawNumber, caller, resolvedTitle)
        val isContact = BleJsonHelpers.isLikelyContact(caller, resolvedTitle, resolvedNumber)

        if (sid == null) {
            Log.w(INCOMING_AI_CHAIN_TAG, "incoming_call without SID — drop (MCU should always provide non-zero SID)")
            return
        }

        val call = IncomingCall(
            callId = "ble-$uid-$sid",
            caller = resolvedTitle,
            number = resolvedNumber,
            title = resolvedTitle,
            bleUid = uid,
            bleSid = sid,
            isContact = isContact
        )
        Log.i(
            INCOMING_AI_CHAIN_TAG,
            "incoming_call uid=$uid sid=$sid number=$resolvedNumber title=$resolvedTitle heuristicContact=$isContact"
        )
        if (!host.onIncomingCallGate(call)) {
            Log.i(INCOMING_AI_CHAIN_TAG, "incoming_call gate denied -> incoming_call_ack only uid=$uid sid=$sid")
            val ackParams = JSONObject().apply {
                put("uid", uid)
                put("sid", sid)
            }
            host.sendJsonRpc("incoming_call_ack", ackParams, expectAck = false)
            return
        }
        host.callSid = sid
        host.activeCallSession = CallSessionToken(sid = sid, bleUid = uid, number = resolvedNumber)
        host.emitEvent(
            CallMateBleEvent.IncomingCall(
                uid,
                resolvedTitle,
                caller,
                resolvedNumber,
                isContact,
                sid
            )
        )
        Log.i(INCOMING_AI_CHAIN_TAG, "incoming_call gate ok -> emitIncomingCall uid=$uid sid=$sid")
        host.emitIncomingCall(call)
        val ackParams = JSONObject().apply {
            put("uid", uid)
            put("sid", sid)
        }
        host.sendJsonRpc("incoming_call_ack", ackParams, expectAck = false)
    }

    /**
     * MCU PBAP：`is_contact` 1=通讯录有匹配；0=PBAP 搜完无匹配（陌生人）。
     * 与 iOS `IncomingCallContext.callerType`（contact / stranger）及 [LiveCallIncomingWebSocket] hello 里
     * `callerType` + Opus `audio_params` 对齐：陌生人走完整 AI 代接（协调器发 answer → audio_start）。
     */
    private fun handleCallerResolved(json: JSONObject) {
        val uid = json.optInt("uid", 0)
        val sid = BleJsonHelpers.parseSid(json)
        val title = json.optString("title", "")
        val caller = json.optString("caller", "")
        val rawNumber = json.optString("number", "")
        val resolvedTitle = if (title.isBlank()) caller else title
        val resolvedNumber = BleJsonHelpers.resolveIncomingNumber(rawNumber, caller, resolvedTitle)
        val isPbapContact = if (json.has("is_contact")) {
            json.optInt("is_contact", 0) != 0
        } else {
            true
        }
        val call = IncomingCall(
            callId = "ble-$uid-${sid ?: 0}",
            caller = resolvedTitle,
            number = resolvedNumber,
            title = resolvedTitle,
            bleUid = uid,
            bleSid = sid,
            isContact = isPbapContact
        )
        if (sid != null && host.activeCallSession == null) {
            host.activeCallSession = CallSessionToken(sid = sid, bleUid = uid, number = resolvedNumber)
            host.callSid = sid
        }
        Log.i(
            INCOMING_AI_CHAIN_TAG,
            "caller_resolved uid=$uid sid=$sid number=$resolvedNumber pbapField=${json.has("is_contact")} is_contact=$isPbapContact"
        )
        if (isPbapContact) {
            if (!host.onIncomingCallGate(call)) {
                Log.i(
                    INCOMING_AI_CHAIN_TAG,
                    "caller_resolved contact gate denied -> cancelAiTakeover uid=$uid"
                )
                host.cancelIncomingAiTakeover("caller_resolved_contact_passthrough")
                return
            }
        }
        host.emitEvent(
            CallMateBleEvent.IncomingCall(
                uid,
                resolvedTitle,
                caller,
                resolvedNumber,
                isPbapContact,
                sid
            )
        )
        Log.i(
            INCOMING_AI_CHAIN_TAG,
            "caller_resolved -> emitIncomingCall uid=$uid is_contact=$isPbapContact"
        )
        host.emitIncomingCall(call)
    }

    private fun handleCallState(json: JSONObject) {
        val state = json.optString("state", "").trim().lowercase()
        val sid = BleJsonHelpers.parseSid(json)
        val current = host.callSid
        val isTerminal = state == "ended" || state == "rejected" || state == "phone_handled"

        /*
         * SID-strict gating (aligned with iOS `handleCallState`):
         * - Terminal states always pass through so Live UI can dismiss properly.
         * - `active`, `audio_streaming`, `outgoing_answered` also pass through
         *   because they may arrive before the App has cached the SID (outbound dial).
         * - Latency test echo may send `active` with a mismatched SID.
         * - All other states with a mismatched SID are dropped as stale.
         */
        val allowStaleSidForLatency =
            host.isLatencyTestEchoMode() && state == "active"
        if (!isTerminal &&
            !allowStaleSidForLatency &&
            sid != null && current != null && sid != current &&
            state != "active" && state != "audio_streaming" &&
            state != "outgoing_answered"
        ) {
            Log.w(
                TAG,
                "call_state($state) SID mismatch: got=$sid active=$current -> drop stale"
            )
            return
        }
        if (sid != null) host.callSid = sid
        host.emitCallState(state)
        host.emitEvent(CallMateBleEvent.CallState(state))
        if (state == "active") {
            host.onCallStateBecameActive()
        }
        if (state == "outgoing_answered") {
            host.onOutgoingAnswered()
        }
        if (state == "audio_streaming") {
            host.onCallStateAudioStreamingReady()
        }
        if (isTerminal) {
            host.onCallStateTerminated(state)
            host.callSid = null
            host.activeCallSession = null
        }
    }

    private fun handleLegacyAck(json: JSONObject) {
        val cmd = json.optString("cmd", "")
        val result = json.optInt("result", -999)
        val sid = BleJsonHelpers.parseSid(json)
        if (sid != null && host.callSid != null && sid != host.callSid &&
            BleJsonHelpers.isSidBoundCommand(cmd)
        ) {
            return
        }
        if (sid != null) host.callSid = sid
        host.applyAckSideEffects(cmd, result)
        host.onAckHandled(cmd, sid, null)
        host.emitEvent(CallMateBleEvent.Ack(cmd, result))
    }

    private fun handleCrashLogRsp(payload: JSONObject) {
        val found = payload.optInt("found", 0) != 0
        if (!found) {
            host.setMcuCrashLogState(McuCrashLogState.NotFound)
            return
        }
        fun longVal(k: String) = payload.optLong(k, 0L)
        val bt = payload.optJSONArray("backtrace")
        val backtrace = mutableListOf<Long>()
        if (bt != null) {
            for (i in 0 until bt.length()) {
                backtrace.add(bt.optLong(i, 0L))
            }
        }
        val log = McuCrashLog(
            crashType = payload.optInt("crash_type", 0),
            uptimeMs = payload.optInt("uptime_ms", 0),
            thread = payload.optString("thread", ""),
            pc = longVal("pc"),
            lr = longVal("lr"),
            cfsr = longVal("cfsr"),
            hfsr = longVal("hfsr"),
            detail = payload.optString("detail", ""),
            backtrace = backtrace
        )
        host.setMcuCrashLogState(McuCrashLogState.Found(log))
    }
}
