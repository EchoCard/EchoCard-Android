package com.vaca.callmate.features.calls

import com.vaca.callmate.data.IncomingCall
import java.util.concurrent.ConcurrentHashMap

/**
 * 与 iOS `CallSessionController.wsSessionId` 对齐：WS `hello` 后写入 `session_id`。
 *
 * Keyed by MCU BLE `sid` (non-zero, unique per call) — the single source of truth
 * that replaces the old composite `uid|sid|number` key.  This prevents collisions
 * when the same number calls back-to-back with different SIDs.
 */
object LiveCallWsSessionHolder {

    private val bySid = ConcurrentHashMap<Long, String>()

    @Deprecated("Use SID-based API", ReplaceWith("sidKey(call)"))
    fun sessionKey(call: IncomingCall): String {
        val sid = call.bleSid ?: return "${call.bleUid}|0|${call.number}"
        return "sid:$sid"
    }

    fun put(call: IncomingCall, sessionId: String?) {
        val sid = call.bleSid ?: return
        if (sessionId.isNullOrBlank()) {
            bySid.remove(sid)
        } else {
            bySid[sid] = sessionId
        }
    }

    fun putBySid(sid: Long, sessionId: String?) {
        if (sessionId.isNullOrBlank()) {
            bySid.remove(sid)
        } else {
            bySid[sid] = sessionId
        }
    }

    fun get(call: IncomingCall): String {
        val sid = call.bleSid ?: return ""
        return bySid[sid].orEmpty()
    }

    fun getBySid(sid: Long): String = bySid[sid].orEmpty()

    fun clear(call: IncomingCall) {
        val sid = call.bleSid ?: return
        bySid.remove(sid)
    }

    fun clearBySid(sid: Long) {
        bySid.remove(sid)
    }
}
