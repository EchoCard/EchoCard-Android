package com.vaca.callmate.core.ble

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vaca.callmate.data.ProcessStrategyStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * 与 iOS `CallMateBLEClient+Strategy.swift` 对齐：KV `strategy` 分块、`device-id` 同步。
 */
class BleKvSyncCoordinator(
    private val appContext: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val isKvReady: () -> Boolean,
    private val sendKv: (JSONObject, Boolean) -> Unit,
    private val sendKvChunkedSet: (String, ByteArray) -> Unit,
    private val setRuntimeDeviceId: (String?) -> Unit,
    private val setPendingStrategy: (String?) -> Unit,
    private val emitFlashdb: (CallMateBleEvent.FlashdbResponse) -> Unit,
    private val setFlashdbUsage: (FlashDBUsage?) -> Unit,
    private val setFlashdbLast: (Int, String) -> Unit
) {
    companion object {
        private const val TAG = "CallMateBLE"
        private const val KEY_DEVICE_ID = "device-id"
        private const val KEY_STRATEGY = "strategy"
        /** duplicate chunk 头时若 recv=0，防抖重发 chunk0 */
        private const val DUPLICATE_HEADER_RETRY_MS = 400L
    }

    private var strategySyncPendingGet = false
    private val strategyChunkBuffer = java.io.ByteArrayOutputStream()
    private var strategyChunkExpectedLength = 0
    private var strategyChunkExpectedCount = 0
    private var strategyChunkReceivedCount = 0
    /** 首轮 chunked meta 的 JSON-RPC `id`；用于区分「同一条 notify 重放」与「新的 kv_get 响应」(MCU 已 kv_download_reset)。 */
    private var strategyChunkMetaRpcId: Int? = null
    private var strategyChunkWatchdog: Job? = null

    private var deviceIDSyncInProgress = false
    private var deviceIDSyncPendingSet = false
    private var deviceIDSyncRetryCount = 0
    private var deviceIDSyncWatchdog: Job? = null

    private var strategyInitialSyncTriggered = false
    private var deviceIDInitialSyncTriggered = false
    /** MCU 重复发 chunked 头且 recv=0 时，防抖重发 kv_get_chunk(0)（首个写可能因 GATT busy 未入队）。 */
    private var duplicateChunkHeaderRetryJob: Job? = null

    var strategySuppressLocalPush = false
        private set

    /**
     * 远端 GATT 表变更（如 [BluetoothGattCallback.onServiceChanged]）后调用：
     * 取消进行中的 KV 看门狗并重置首轮同步标记，便于重新 discover + notify 后再拉 device-id/strategy。
     */
    fun onGattServiceTableChanged() {
        deviceIDSyncWatchdog?.cancel()
        strategyChunkWatchdog?.cancel()
        duplicateChunkHeaderRetryJob?.cancel()
        duplicateChunkHeaderRetryJob = null
        strategyChunkBuffer.reset()
        strategyChunkExpectedLength = 0
        strategyChunkExpectedCount = 0
        strategyChunkReceivedCount = 0
        strategyChunkMetaRpcId = null
        strategySyncPendingGet = false
        deviceIDSyncInProgress = false
        deviceIDSyncPendingSet = false
        deviceIDSyncRetryCount = 0
        strategyInitialSyncTriggered = false
        deviceIDInitialSyncTriggered = false
        Log.i(TAG, "[BLE] KV state reset for GATT service table change")
    }

    /** 断开 BLE 时重置，下次连接会重新跑 device-id / strategy 首轮同步。 */
    fun resetOnDisconnect() {
        strategyChunkWatchdog?.cancel()
        deviceIDSyncWatchdog?.cancel()
        strategyChunkBuffer.reset()
        strategyChunkExpectedLength = 0
        strategyChunkExpectedCount = 0
        strategyChunkReceivedCount = 0
        strategyChunkMetaRpcId = null
        strategySyncPendingGet = false
        deviceIDSyncInProgress = false
        deviceIDSyncPendingSet = false
        deviceIDSyncRetryCount = 0
        strategyInitialSyncTriggered = false
        deviceIDInitialSyncTriggered = false
        strategySuppressLocalPush = false
        duplicateChunkHeaderRetryJob?.cancel()
        duplicateChunkHeaderRetryJob = null
    }

    fun onKvChannelReadyAfterHandshake() {
        if (deviceIDInitialSyncTriggered) return
        deviceIDInitialSyncTriggered = true
        syncDeviceIdWithDeviceOnConnect()
    }

    fun syncStrategyWithDeviceIfNeeded() {
        if (!isKvReady()) return
        if (strategySyncPendingGet) return
        strategySyncPendingGet = true
        Log.i(TAG, "[STRATEGY] ble->local sync start: kv_get(strategy)")
        sendKv(JSONObject().apply { put("cmd", "kv_get"); put("key", KEY_STRATEGY) }, true)
    }

    fun pushLocalStrategyToDevice() {
        if (!isKvReady()) {
            Log.i(TAG, "[STRATEGY] pushLocalStrategyToDevice: KV not ready")
            return
        }
        scope.launch {
            val json = ProcessStrategyStore.processStrategyJSONString(appContext).orEmpty().trim()
            if (json.isEmpty()) return@launch
            Log.i(TAG, "[STRATEGY] explicit push local->device len=${json.length}")
            flashdbSet(KEY_STRATEGY, json)
        }
    }

    fun adoptDeviceStrategy(json: String) {
        scope.launch {
            strategySuppressLocalPush = true
            try {
                if (ProcessStrategyStore.saveProcessStrategyJSONIfValid(appContext, json)) {
                    setPendingStrategy(null)
                }
            } finally {
                strategySuppressLocalPush = false
            }
        }
    }

    fun clearPendingDeviceStrategy() {
        setPendingStrategy(null)
    }

    fun onLocalStrategyJsonChanged(json: String?) {
        if (strategySuppressLocalPush) return
        if (!isKvReady()) return
        val trimmed = json?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        Log.i(TAG, "[STRATEGY] local->ble set strategy len=${trimmed.length}")
        flashdbSet(KEY_STRATEGY, trimmed)
    }

    fun suppressLocalStrategyPush(suppress: Boolean) {
        strategySuppressLocalPush = suppress
    }

    private fun syncDeviceIdWithDeviceOnConnect() {
        if (!isKvReady()) return
        deviceIDSyncInProgress = true
        deviceIDSyncPendingSet = false
        deviceIDSyncRetryCount = 0
        Log.i(TAG, "[DEVICE_ID] sync start: kv_get($KEY_DEVICE_ID)")
        armDeviceIdWatchdog()
        sendKv(JSONObject().apply { put("cmd", "kv_get"); put("key", KEY_DEVICE_ID) }, true)
    }

    private fun armDeviceIdWatchdog() {
        deviceIDSyncWatchdog?.cancel()
        deviceIDSyncWatchdog = scope.launch {
            delay(2000)
            if (!deviceIDSyncInProgress) return@launch
            if (deviceIDSyncRetryCount < 1) {
                deviceIDSyncRetryCount++
                Log.w(TAG, "[DEVICE_ID] sync timeout, retry kv_get($KEY_DEVICE_ID)")
                armDeviceIdWatchdog()
                sendKv(JSONObject().apply { put("cmd", "kv_get"); put("key", KEY_DEVICE_ID) }, true)
            } else {
                deviceIDSyncInProgress = false
                deviceIDSyncPendingSet = false
                Log.w(TAG, "[DEVICE_ID] sync timeout (final)")
                syncStrategyAfterDeviceIdIfNeeded("device-id: timeout (no kv response after retry)")
            }
        }
    }

    private fun armStrategyChunkWatchdog() {
        strategyChunkWatchdog?.cancel()
        strategyChunkWatchdog = scope.launch {
            delay(2000)
            if (!strategySyncPendingGet) return@launch
            if (strategyChunkExpectedCount <= 0) return@launch
            if (strategyChunkReceivedCount < strategyChunkExpectedCount) {
                Log.w(TAG, "[STRATEGY] chunk timeout, retry kv_get(strategy)")
                strategyChunkBuffer.reset()
                strategyChunkExpectedLength = 0
                strategyChunkExpectedCount = 0
                strategyChunkReceivedCount = 0
                strategyChunkMetaRpcId = null
                sendKv(JSONObject().apply { put("cmd", "kv_get"); put("key", KEY_STRATEGY) }, true)
            }
        }
    }

    private fun syncStrategyAfterDeviceIdIfNeeded(reason: String) {
        if (!isKvReady()) return
        if (strategyInitialSyncTriggered) return
        strategyInitialSyncTriggered = true
        Log.i(TAG, "[STRATEGY] start after device-id sync — $reason")
        syncStrategyWithDeviceIfNeeded()
    }

    fun handleKvChunk(merged: JSONObject) {
        val cmd = merged.optString("cmd", "")
        val key = merged.optString("key", "")
        val index = merged.optInt("index", -1)
        val last = merged.optInt("last", 0) != 0
        if (cmd != "kv_get" || !strategySyncPendingGet) return
        if (key.isNotEmpty() && key != KEY_STRATEGY) {
            Log.i(TAG, "[STRATEGY] kv_chunk key=$key")
        }
        val b64 = merged.optString("data_b64", "")
        val chunk = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Exception) {
            Log.e(TAG, "[STRATEGY] invalid base64 chunk")
            return
        }
        if (index < 0) return
        if (index == 0 && strategyChunkReceivedCount > 0) {
            strategyChunkBuffer.reset()
            strategyChunkReceivedCount = 0
        }
        if (index < strategyChunkReceivedCount) return
        if (index > strategyChunkReceivedCount) {
            requestStrategyChunk(strategyChunkReceivedCount)
            armStrategyChunkWatchdog()
            return
        }
        strategyChunkBuffer.write(chunk)
        strategyChunkReceivedCount++
        armStrategyChunkWatchdog()
        val done = last || (strategyChunkExpectedLength > 0 && strategyChunkBuffer.size() >= strategyChunkExpectedLength)
        if (done) {
            val bytes = strategyChunkBuffer.toByteArray()
            strategyChunkBuffer.reset()
            strategyChunkExpectedCount = 0
            strategyChunkExpectedLength = 0
            strategyChunkReceivedCount = 0
            strategyChunkMetaRpcId = null
            strategyChunkWatchdog?.cancel()
            val remote = String(bytes, Charsets.UTF_8).trim()
            strategySyncPendingGet = false
            Log.i(TAG, "[KV] kv_get result=0 key=$KEY_STRATEGY valueLen=${remote.length} chunked=true")
            if (remote.isNotEmpty() && ProcessStrategyStore.validateProcessStrategyJSON(remote)) {
                Log.i(TAG, "[STRATEGY] device has chunked strategy len=${remote.length}, storing as pendingDeviceStrategy")
                setPendingStrategy(remote)
            } else {
                Log.i(TAG, "[STRATEGY] device has no valid chunked strategy, pendingDeviceStrategy remains nil")
            }
            setFlashdbLast(0, remote)
            emitFlashdb(CallMateBleEvent.FlashdbResponse("kv_get", 0, KEY_STRATEGY, remote, null))
        } else {
            requestStrategyChunk(strategyChunkReceivedCount)
        }
    }

    /** 与 iOS `requestStrategyChunk` 一致：立即发 `kv_get_chunk`（写队列已串行）。 */
    private fun requestStrategyChunk(index: Int) {
        sendKv(
            JSONObject().apply {
                put("cmd", "kv_get_chunk")
                put("key", KEY_STRATEGY)
                put("index", index)
            },
            true
        )
    }

    fun handleKvRsp(merged: JSONObject) {
        val cmd = merged.optString("cmd", "")
        val result = merged.optInt("result", -999)
        val key = merged.optString("key", "").trim()
        val value = merged.optString("value", "").takeIf { it.isNotEmpty() }
        val chunked = merged.optInt("chunked", 0) != 0

        handleDeviceIdKv(cmd, result, key, value, chunked)
        handleStrategyKvRsp(merged, cmd, result, key, value, chunked)

        val usage = if (merged.has("total_bytes")) {
            FlashDBUsage(
                keys = merged.optInt("keys", 0),
                usedBytes = merged.optInt("used_bytes", 0),
                freeBytes = merged.optInt("free_bytes", 0),
                valueBytes = merged.optInt("value_bytes", 0),
                totalBytes = merged.optInt("total_bytes", 0)
            ).takeIf { it.totalBytes > 0 }
        } else null
        if (usage != null) setFlashdbUsage(usage)

        val msg = when {
            result == 0 && cmd == "kv_get" -> value ?: ""
            result == 0 -> "$cmd ok"
            else -> "$cmd failed($result)"
        }
        setFlashdbLast(result, msg)
        emitFlashdb(CallMateBleEvent.FlashdbResponse(cmd, result, key.ifEmpty { null }, value, usage))

        if (cmd == "kv_set_end" && result == 0) {
            val nk = key.ifEmpty { "" }
            if (nk.isEmpty() || nk == KEY_STRATEGY) {
                strategySyncPendingGet = true
                sendKv(JSONObject().apply { put("cmd", "kv_get"); put("key", KEY_STRATEGY) }, true)
            }
        }
    }

    private fun handleDeviceIdKv(
        cmd: String,
        result: Int,
        key: String,
        value: String?,
        chunked: Boolean
    ) {
        val nk = key.ifEmpty { "" }
        if (nk.isNotEmpty() && nk != KEY_DEVICE_ID) return
        if (chunked) {
            Log.i(TAG, "[DEVICE_ID] ignore chunked for $KEY_DEVICE_ID")
            return
        }
        when (cmd) {
            "kv_get" -> {
                val resolved = normalizedUuid(value)
                if (result == 0 && resolved != null) {
                    // 空 key 且已结束首轮 device-id 同步：多为 strategy/KV 噪声，勿当 device-id（会误打日志、抢 strategy）
                    if (nk.isEmpty() && !deviceIDSyncInProgress) {
                        return
                    }
                    val awaitingInitialDeviceId = deviceIDSyncInProgress
                    deviceIDSyncWatchdog?.cancel()
                    deviceIDSyncInProgress = false
                    deviceIDSyncPendingSet = false
                    setRuntimeDeviceId(resolved)
                    if (awaitingInitialDeviceId) {
                        Log.i(TAG, "[DEVICE_ID] synced from MCU: $resolved")
                        syncStrategyAfterDeviceIdIfNeeded("device-id: ok uuid=$resolved")
                    }
                    return
                }
                if (!deviceIDSyncInProgress) return
                val newId = UUID.randomUUID().toString()
                deviceIDSyncPendingSet = true
                Log.i(TAG, "[DEVICE_ID] kv_set new uuid=$newId")
                armDeviceIdWatchdog()
                flashdbSet(KEY_DEVICE_ID, newId)
            }
            "kv_set" -> {
                if (!deviceIDSyncInProgress || !deviceIDSyncPendingSet) return
                deviceIDSyncPendingSet = false
                if (result == 0) {
                    armDeviceIdWatchdog()
                    sendKv(JSONObject().apply { put("cmd", "kv_get"); put("key", KEY_DEVICE_ID) }, true)
                } else {
                    deviceIDSyncWatchdog?.cancel()
                    deviceIDSyncInProgress = false
                    syncStrategyAfterDeviceIdIfNeeded("device-id: kv_set failed result=$result")
                }
            }
        }
    }

    private fun handleStrategyKvRsp(
        merged: JSONObject,
        cmd: String,
        result: Int,
        key: String,
        value: String?,
        chunked: Boolean
    ) {
        if (cmd != "kv_get" || !strategySyncPendingGet) return
        val nk = key.trim()
        if (nk.isNotEmpty() && nk != KEY_STRATEGY) return
        if (nk.isEmpty()) {
            Log.i(TAG, "[STRATEGY] kv_get response missing key, treat as strategy for pending sync")
        }
        if (chunked) {
            if (merged.optString("data_b64", "").isNotEmpty() && merged.optInt("index", -1) >= 0) {
                handleKvChunk(merged)
                return
            }
            if (strategyChunkExpectedCount > 0) {
                if (merged.optString("data_b64", "").isEmpty() &&
                    merged.optInt("total_len", -1) == strategyChunkExpectedLength &&
                    merged.optInt("chunks", -1) == strategyChunkExpectedCount
                ) {
                    val incomingId = BleJsonHelpers.parseJsonRpcId(merged)
                    val prevId = strategyChunkMetaRpcId
                    if (incomingId != null && prevId != null && incomingId != prevId) {
                        strategyChunkBuffer.reset()
                        strategyChunkReceivedCount = 0
                        strategyChunkMetaRpcId = incomingId
                        Log.i(
                            TAG,
                            "[STRATEGY] new kv_get chunked meta (rpc id $prevId -> $incomingId), restart from chunk 0"
                        )
                        armStrategyChunkWatchdog()
                        requestStrategyChunk(0)
                        return
                    }
                    Log.i(
                        TAG,
                        "[STRATEGY] duplicate chunk meta (same totals, id=$incomingId), re-request chunk($strategyChunkReceivedCount)"
                    )
                    requestStrategyChunk(strategyChunkReceivedCount)
                    armStrategyChunkWatchdog()
                    return
                }
                Log.i(TAG, "[STRATEGY] duplicate chunk header")
                armStrategyChunkWatchdog()
                if (strategyChunkReceivedCount == 0) {
                    duplicateChunkHeaderRetryJob?.cancel()
                    duplicateChunkHeaderRetryJob = scope.launch {
                        delay(DUPLICATE_HEADER_RETRY_MS)
                        if (strategySyncPendingGet &&
                            strategyChunkReceivedCount == 0 &&
                            strategyChunkExpectedCount > 0
                        ) {
                            Log.i(TAG, "[STRATEGY] duplicate header & recv=0: re-request kv_get_chunk(0)")
                            requestStrategyChunk(0)
                        }
                    }
                }
            } else {
                strategyChunkExpectedLength = merged.optInt("total_len", 0)
                strategyChunkExpectedCount = merged.optInt("chunks", 0)
                strategyChunkReceivedCount = 0
                strategyChunkBuffer.reset()
                strategyChunkMetaRpcId = BleJsonHelpers.parseJsonRpcId(merged)
                Log.i(TAG, "[STRATEGY] ble->local strategy is chunked, waiting chunks... (total=$strategyChunkExpectedLength chunks=$strategyChunkExpectedCount)")
                armStrategyChunkWatchdog()
                requestStrategyChunk(0)
            }
            return
        }
        strategySyncPendingGet = false
        val valueLen = value?.length ?: 0
        Log.i(TAG, "[KV] kv_get result=$result key=${nk.ifEmpty { "<empty>" }} valueLen=$valueLen")
        if (result == 0) {
            val remote = value?.trim().orEmpty()
            if (remote.isNotEmpty() && ProcessStrategyStore.validateProcessStrategyJSON(remote)) {
                Log.i(TAG, "[STRATEGY] device has strategy len=${remote.length}, storing as pendingDeviceStrategy")
                setPendingStrategy(remote)
            } else {
                Log.i(TAG, "[STRATEGY] device has no valid strategy, pendingDeviceStrategy remains nil")
            }
        } else {
            Log.w(TAG, "[STRATEGY] ble->local sync result: kv_get(strategy) failed result=$result")
        }
    }

    private fun normalizedUuid(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return try {
            UUID.fromString(trimmed).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun flashdbSet(key: String, value: String) {
        if (key == KEY_STRATEGY && value.toByteArray(Charsets.UTF_8).size > 120) {
            sendKvChunkedSet(key, value.toByteArray(Charsets.UTF_8))
        } else {
            sendKv(
                JSONObject().apply {
                    put("cmd", "kv_set")
                    put("key", key)
                    put("value", value)
                },
                true
            )
        }
    }
}
