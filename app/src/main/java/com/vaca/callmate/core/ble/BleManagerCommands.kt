package com.vaca.callmate.core.ble

import android.util.Log
import org.json.JSONObject

private const val CMD_LOG = "CallMateBLE"

/**
 * 与 iOS `CallMateBLEClient+Commands.swift` 对齐的对外命令 API。
 */
fun BleManager.requestDeviceInfo() {
    Log.i(CMD_LOG, "requestDeviceInfo → get_info (device management / handshake)")
    sendCommand("get_info", JSONObject(), expectAck = true)
}

fun BleManager.requestDeviceDiagnostics() {
    Log.i(CMD_LOG, "requestDeviceDiagnostics → diag_info")
    sendCommand("diag_info", JSONObject(), expectAck = false)
}

fun BleManager.requestMcuCrashLog() {
    setMcuCrashLogState(McuCrashLogState.Loading)
    sendCommand("crash_log_get", JSONObject(), expectAck = false)
}

fun BleManager.clearMcuCrashLog() {
    sendCommand("crash_log_clear", JSONObject(), expectAck = true)
    setMcuCrashLogState(McuCrashLogState.NotFound)
}

/**
 * 与 iOS `setRandomMacAddress()` 一致：随机本地管理单播 MAC，写入 MCU NVDS。
 * @return 展示用 `"XX:XX:XX:XX:XX:XX"`
 */
fun BleManager.setRandomMacAddress(): String {
    val bytes = ByteArray(6) { kotlin.random.Random.nextInt(256).toByte() }
    bytes[0] = (((bytes[0].toInt() and 0xFF) or 0x02) and 0xFE).toByte()
    val hex = bytes.joinToString("") { b -> "%02X".format(b.toInt() and 0xFF) }
    val display = bytes.joinToString(":") { b -> "%02X".format(b.toInt() and 0xFF) }
    sendCommand("set_mac", JSONObject().put("mac", hex), expectAck = true)
    return display
}

fun BleManager.requestRegDump() {
    sendCommand("reg_dump", JSONObject(), expectAck = false)
}

fun BleManager.flashdbInfo() {
    sendKvCommand(JSONObject().apply { put("cmd", "kv_info") })
}

fun BleManager.flashdbGet(key: String) {
    sendKvCommand(JSONObject().apply {
        put("cmd", "kv_get")
        put("key", key)
    })
}

fun BleManager.flashdbSet(key: String, value: String) {
    sendKvCommand(JSONObject().apply {
        put("cmd", "kv_set")
        put("key", key)
        put("value", value)
    })
}

fun BleManager.flashdbDelete(key: String) {
    sendKvCommand(JSONObject().apply {
        put("cmd", "kv_del")
        put("key", key)
    })
}

fun BleManager.dialPhoneNumber(number: String) {
    val n = number.trim()
    if (n.isEmpty()) return
    noteLastDialedNumber(n)
    sendCommand(
        "dial",
        JSONObject().apply { put("number", n) },
        expectAck = true
    )
}

fun BleManager.answerCall(uid: Int, sid: Long?) {
    markIncomingAiAnswerPending()
    Log.i(INCOMING_AI_CHAIN_TAG, "ble send answer uid=$uid sid=$sid")
    val p = JSONObject().apply {
        put("uid", uid)
        if (sid != null) put("sid", sid)
    }
    sendCommand("answer", p, expectAck = true)
}

fun BleManager.hangup(sid: Long?) {
    val p = JSONObject()
    if (sid != null) p.put("sid", sid)
    sendCommand("hangup", p, expectAck = true)
}

/** 与 iOS `sendCallCommand("ignore", uid:)` 对齐 */
fun BleManager.sendIgnoreIncomingCall(uid: Int, sid: Long?) {
    val p = JSONObject().apply {
        put("uid", uid)
        if (sid != null) put("sid", sid)
    }
    sendCommand("ignore", p, expectAck = true)
}

fun BleManager.audioStart(sid: Long?, codec: String?) {
    val p = JSONObject()
    if (sid != null) p.put("sid", sid)
    if (codec != null) p.put("codec", codec)
    sendCommand("audio_start", p, expectAck = true)
}

fun BleManager.audioStop(sid: Long?) {
    val p = JSONObject()
    if (sid != null) p.put("sid", sid)
    sendCommand("audio_stop", p, expectAck = true)
}

/** 与 iOS `sendCommand("hfp_connect")`（如 `LatencyTestRunner`）对齐：显式请求 MCU 连接经典蓝牙 HFP。 */
fun BleManager.hfpConnect() {
    Log.i(CMD_LOG, "hfp_connect → MCU (Hands-Free Profile)")
    sendCommand("hfp_connect", JSONObject(), expectAck = true)
}

/** 与 iOS `sendHFPDisconnectWithCooldown` / `sendCommand("hfp_disconnect")` 对齐：真人接听转交系统通话（并触发短时 [markHfpDisconnectCooldown]）。 */
fun BleManager.hfpDisconnect() {
    markHfpDisconnectCooldown()
    Log.i(CMD_LOG, "hfp_disconnect → MCU (passthrough / human handoff)")
    sendCommand("hfp_disconnect", JSONObject(), expectAck = false)
}

/** 与 iOS `LatencyTestRunner`：`latency_test_mode` 抑制 ANCS 等与延迟测试冲突的逻辑。 */
fun BleManager.latencyTestMode(enable: Boolean) {
    sendCommand("latency_test_mode", JSONObject().put("enable", enable), expectAck = true)
}

fun BleManager.latencyTestStart() {
    sendCommand("latency_test_start", JSONObject(), expectAck = true)
}

fun BleManager.latencyTestStop() {
    sendCommand("latency_test_stop", JSONObject(), expectAck = false)
}

fun BleManager.sendRebootCommand() {
    sendCommand("reboot", JSONObject(), expectAck = true)
}

fun BleManager.sendFactoryResetCommand() {
    sendCommand("factory_reset", JSONObject(), expectAck = true)
}

/** 与 iOS `setIndicatorLight`：`enable` / `brightness` 字段名对齐 Swift。 */
fun BleManager.setIndicatorLight(enabled: Boolean? = null, brightness: Int? = null) {
    val p = JSONObject()
    if (enabled != null) p.put("enable", enabled)
    if (brightness != null) p.put("brightness", brightness.coerceIn(0, 255))
    sendCommand("led_config", p, expectAck = true)
}

fun BleManager.setIndicatorColor(color: String) {
    val n = color.lowercase()
    if (n !in setOf("red", "green", "blue", "off")) return
    sendCommand("led_color", JSONObject().put("color", n), expectAck = true)
}

fun BleManager.setPA20Level(high: Boolean) {
    sendCommand("pa20_set", JSONObject().put("level", if (high) 1 else 0), expectAck = true)
}
