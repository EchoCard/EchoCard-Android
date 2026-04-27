package com.vaca.callmate.core.audio

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.vaca.callmate.core.ble.BleFirmwarePackets
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.CallMateBleEvent
import com.vaca.callmate.core.ble.PreloadMissingRange
import com.vaca.callmate.core.firmware.CRC32MPEG2
import com.vaca.callmate.core.network.TTSFillerResponse
import com.vaca.callmate.core.network.TTSFillerService
import com.vaca.callmate.data.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * 与 iOS `TTSFillerSyncCoordinator.swift` 对齐：编排一次完整的 filler 预加载：
 *   1) HTTP 拉 6 条 filler（`TTSFillerService`）
 *   2) mp3 → mSBC（`TTSFillerEncoder`）
 *   3) BLE preload 会话（`preload_begin/_asset_begin/_asset_end/_end` + preload char chunks）
 *   4) 持久化 `lastPushedHash` 到 SharedPreferences
 *
 * 见 `docs/tts-filler-low-latency.md §4/§5/§6/§8`。
 */
object TTSFillerSyncCoordinator {

    sealed class State {
        data object Idle : State()
        data class FetchingMetadata(val voiceId: String) : State()
        data class Encoding(val done: Int, val total: Int) : State()
        data class Uploading(
            val assetIndex: Int,
            val assetCount: Int,
            val sentBytes: Int,
            val totalBytes: Int,
        ) : State()
        data class Success(val voiceId: String, val hash: String) : State()
        data class Failed(val message: String) : State()
    }

    private const val TAG = "TTSFillers"
    private const val PREFS_NAME = "callmate.filler.coordinator"
    private const val KEY_LAST_HASH = "last_pushed_hash"
    private const val KEY_LAST_VOICE = "last_pushed_voice_id"
    private const val MAX_RETRANSMITS_PER_ASSET = 3
    private const val FALLBACK_MIN_INTERVAL_MS = 60_000L

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Main.immediate)
    private val runMutex = Mutex()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var runningJob: Job? = null

    @Volatile
    private var runningVoiceId: String? = null

    @Volatile
    private var currentAsset: TTSFillerEncodedAsset? = null

    @Volatile
    private var currentChunkSize: Int = 0

    private val retransmitCount = HashMap<String, Int>()

    @Volatile
    private var bleManager: BleManager? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var appPreferences: AppPreferences? = null

    private var prefs: SharedPreferences? = null
    private var eventsJob: Job? = null
    private var lastFallbackTriggerAt: Long = 0L

    /**
     * 必须在 app 启动（如 `CallMateApplication.onCreate`）或首次命中前调用一次。
     * 不幂等安全 —— 重复注册会重订阅 BLE events。
     */
    fun attach(context: Context, bleManager: BleManager, preferences: AppPreferences) {
        val ctx = context.applicationContext
        this.appContext = ctx
        this.bleManager = bleManager
        this.appPreferences = preferences
        this.prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        eventsJob?.cancel()
        eventsJob = scope.launch {
            bleManager.bleEvents.collect { ev ->
                try {
                    handlePreloadMissing(ev)
                    handlePlayFillerMissFallback(ev)
                } catch (e: Exception) {
                    Log.w(TAG, "event handler error: ${e.message}")
                }
            }
        }
        Log.i(TAG, "coordinator attached")
    }

    val lastPushedHash: String?
        get() = prefs?.getString(KEY_LAST_HASH, null)

    val lastPushedVoiceId: String?
        get() = prefs?.getString(KEY_LAST_VOICE, null)

    /** 与 iOS `cancel()` 对齐。 */
    fun cancel() {
        runningJob?.cancel()
        bleManager?.resetPreloadDirectQueue()
    }

    /** 与 iOS `preload(voiceId:deviceId:force:)` 对齐：coalesce 同 voice；hash 相同且 !force 直接跳。 */
    fun preload(voiceId: String, deviceId: String, force: Boolean = false): Job {
        val ble = bleManager ?: run {
            Log.w(TAG, "preload skipped: coordinator not attached")
            return scope.launch { /* no-op */ }
        }
        val vId = voiceId.trim()
        val dId = deviceId.trim()
        if (vId.isEmpty() || dId.isEmpty()) {
            Log.w(TAG, "preload skipped: empty voiceId/deviceId")
            return scope.launch { /* no-op */ }
        }
        val existing = runningJob
        if (existing != null && existing.isActive && runningVoiceId == vId) {
            Log.i(TAG, "preload coalesced: voice=$vId already running")
            return existing
        }
        val job = scope.launch {
            runMutex.withLock {
                runningVoiceId = vId
                try {
                    runPreload(ble, vId, dId, force)
                } catch (e: CancellationException) {
                    Log.i(TAG, "preload cancelled voice=$vId")
                    _state.value = State.Idle
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "preload failed voice=$vId: ${e.message}")
                    _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                } finally {
                    runningVoiceId = null
                }
            }
        }
        runningJob = job
        return job
    }

    // ==================== Flow ====================

    private suspend fun runPreload(
        ble: BleManager,
        voiceId: String,
        deviceId: String,
        force: Boolean,
    ) {
        val ctx = appContext ?: throw IllegalStateException("no app context")
        val prefs = appPreferences ?: throw IllegalStateException("no preferences")

        if (ble.currentCallSid.value != null) {
            throw IOException("in call, refusing to preload")
        }
        if (!ble.isReady.value) throw IOException("BLE not ready")
        if (!ble.isPreloadReady()) throw IOException("preload characteristic not found")

        // 1) fetch
        _state.value = State.FetchingMetadata(voiceId)
        val resp: TTSFillerResponse = TTSFillerService.fetchFillers(voiceId, deviceId, prefs)
        if (resp.fillers.isEmpty()) throw IOException("server returned 0 fillers")

        // 2) encode
        _state.value = State.Encoding(0, resp.fillers.size)
        val encoded = ArrayList<TTSFillerEncodedAsset>(resp.fillers.size)
        for ((idx, item) in resp.fillers.withIndex()) {
            yield()
            val asset = TTSFillerEncoder.encode(item, ctx.cacheDir)
            if (asset.data.size > TTSFillerEncoder.MAX_ENCODED_BYTES) {
                throw IOException("asset ${asset.fillerId} is ${asset.data.size} B (> cap)")
            }
            encoded.add(asset)
            _state.value = State.Encoding(idx + 1, resp.fillers.size)
        }

        // 3) hash + short-circuit
        val hash = computeMetaHash(resp.voiceId, encoded)
        if (!force && hash == lastPushedHash && resp.voiceId == lastPushedVoiceId) {
            Log.i(TAG, "skip: hash unchanged ($hash)")
            _state.value = State.Success(resp.voiceId, hash)
            return
        }

        // 4) BLE preload session
        runBLEPreloadSession(ble, resp.voiceId, encoded, hash)

        // 5) persist
        prefs.let {}
        this.prefs?.edit()
            ?.putString(KEY_LAST_HASH, hash)
            ?.putString(KEY_LAST_VOICE, resp.voiceId)
            ?.apply()
        _state.value = State.Success(resp.voiceId, hash)
        Log.i(TAG, "success voice=${resp.voiceId} hash=$hash")
    }

    private suspend fun runBLEPreloadSession(
        ble: BleManager,
        voiceId: String,
        assets: List<TTSFillerEncodedAsset>,
        hash: String,
    ) {
        val totalBytes = assets.sumOf { it.data.size }
        val chunkSize = maxOf(60, ble.preloadMaxChunkPayloadBytes())
        val assetCount = assets.size

        // preload_begin — meta slim (voice_id + hash); count/total_bytes already in params root
        val meta = org.json.JSONObject().apply {
            put("voice_id", voiceId)
            put("hash", hash)
        }
        sendPreloadCommandAwaitAck(
            ble,
            "preload_begin",
            org.json.JSONObject().apply {
                put("scope", "filler")
                put("count", assetCount)
                put("total_bytes", totalBytes)
                put("meta", meta)
            },
            timeoutMs = 10_000L,
        )

        var sentAssets = 0
        var sentBytesTotal = 0
        currentChunkSize = chunkSize
        retransmitCount.clear()
        try {
            for (asset in assets) {
                yield()
                val packets = buildPackets(asset, chunkSize)
                _state.value = State.Uploading(
                    assetIndex = sentAssets,
                    assetCount = assetCount,
                    sentBytes = sentBytesTotal,
                    totalBytes = totalBytes,
                )

                // Mark current asset **before** first chunk goes out — MCU may emit
                // preload_missing the instant the last chunk lands, racing the ack.
                currentAsset = asset

                sendPreloadCommandAwaitAck(
                    ble,
                    "preload_asset_begin",
                    org.json.JSONObject().apply {
                        put("filler_id", asset.fillerId)
                        put("size", asset.data.size)
                        put("crc32", asset.crc32)
                        put("chunk", chunkSize)
                        put("total", packets.size)
                    },
                    timeoutMs = 10_000L,
                )

                // binary chunks via preload characteristic (reset queue to avoid stale packets)
                ble.resetPreloadDirectQueue()
                ble.loadPreloadPackets(packets)
                // poll queue depth until drained / aborted
                while (true) {
                    yield()
                    val remaining = ble.getPreloadQueueDepthOrAbort()
                    if (remaining < 0) throw IOException("BLE disconnected mid-preload")
                    if (remaining == 0) break
                    _state.value = State.Uploading(
                        assetIndex = sentAssets,
                        assetCount = assetCount,
                        sentBytes = sentBytesTotal + (asset.data.size - remaining * chunkSize).coerceAtLeast(0),
                        totalBytes = totalBytes,
                    )
                    delay(80)
                }

                // preload_asset_end (MCU may take longer — FlashDB erase/write)
                sendPreloadCommandAwaitAck(
                    ble,
                    "preload_asset_end",
                    org.json.JSONObject().apply {
                        put("filler_id", asset.fillerId)
                    },
                    timeoutMs = 20_000L,
                )

                sentAssets += 1
                sentBytesTotal += asset.data.size
            }
        } finally {
            currentAsset = null
            currentChunkSize = 0
        }

        sendPreloadCommandAwaitAck(
            ble,
            "preload_end",
            org.json.JSONObject().apply {
                put("scope", "filler")
            },
            timeoutMs = 15_000L,
        )
    }

    // ==================== preload_missing → retransmit ====================

    private fun handlePreloadMissing(ev: CallMateBleEvent) {
        if (ev !is CallMateBleEvent.PreloadMissing) return
        val asset = currentAsset ?: run {
            Log.i(TAG, "preload_missing ignored: no active asset (id=${ev.fillerId})")
            return
        }
        if (asset.fillerId != ev.fillerId) {
            Log.i(TAG, "preload_missing ignored: other asset active=${asset.fillerId} got=${ev.fillerId}")
            return
        }
        val count = (retransmitCount[ev.fillerId] ?: 0) + 1
        retransmitCount[ev.fillerId] = count
        if (count > MAX_RETRANSMITS_PER_ASSET) {
            Log.w(TAG, "preload_missing id=${ev.fillerId} retransmit cap reached ($count); let asset_end fail")
            return
        }
        val chunkSize = currentChunkSize
        if (chunkSize <= 0) return
        val ble = bleManager ?: return
        Log.i(TAG, "preload_missing id=${ev.fillerId} ranges=${ev.ranges.size} attempt=$count")
        scope.launch {
            try {
                val data = asset.data
                val total = data.size
                for (range in ev.ranges) {
                    val start = range.start.coerceAtLeast(0).coerceAtMost(total)
                    val end = range.end.coerceAtLeast(start).coerceAtMost(total)
                    if (end <= start) continue
                    var offset = start
                    while (offset < end) {
                        val n = minOf(chunkSize, end - offset)
                        val slice = data.copyOfRange(offset, offset + n)
                        val crc = CRC32MPEG2.checksum(slice)
                        val idx = (offset / chunkSize).toUInt()
                        val packet = BleFirmwarePackets.buildOtaChunkPacket(idx, offset.toUInt(), slice, crc)
                        ble.loadPreloadPackets(listOf(packet))
                        // small yield so we don't swamp the single-burst queue
                        delay(5)
                        offset += n
                    }
                }
                Log.i(TAG, "preload_missing retransmit complete id=${ev.fillerId}")
            } catch (e: Exception) {
                Log.w(TAG, "preload_missing retransmit failed id=${ev.fillerId}: ${e.message}")
            }
        }
    }

    // ==================== play_filler ack=-1 fallback (MCU stale table) ====================

    private fun handlePlayFillerMissFallback(ev: CallMateBleEvent) {
        if (ev !is CallMateBleEvent.Ack) return
        if (ev.cmd != "play_filler" || ev.result != -1) return
        val ble = bleManager ?: return
        if (ble.currentCallSid.value != null) {
            Log.i(TAG, "play_filler miss during call; skip fallback")
            return
        }
        val voiceId = lastPushedVoiceId ?: return
        val deviceId = ble.runtimeMCUDeviceID.value?.trim().orEmpty()
        if (deviceId.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastFallbackTriggerAt < FALLBACK_MIN_INTERVAL_MS) {
            Log.i(TAG, "play_filler miss fallback throttled")
            return
        }
        lastFallbackTriggerAt = now
        Log.i(TAG, "play_filler miss → background re-push voice=$voiceId")
        preload(voiceId, deviceId, force = true)
    }

    // ==================== Helpers ====================

    private fun buildPackets(asset: TTSFillerEncodedAsset, chunkSize: Int): List<ByteArray> {
        require(chunkSize > 0)
        val total = asset.data.size
        val out = ArrayList<ByteArray>((total + chunkSize - 1) / chunkSize)
        var offset = 0
        var index = 0u
        while (offset < total) {
            val n = minOf(chunkSize, total - offset)
            val slice = asset.data.copyOfRange(offset, offset + n)
            val crc = CRC32MPEG2.checksum(slice)
            out.add(BleFirmwarePackets.buildOtaChunkPacket(index, offset.toUInt(), slice, crc))
            offset += n
            index++
        }
        return out
    }

    /** 16 位小写 hex hash：sha256(voiceId ‖ "\n" ‖ 每 asset `<id>:<size_u32_le>` ‖ "\n") 前 16 位。 */
    private fun computeMetaHash(voiceId: String, assets: List<TTSFillerEncodedAsset>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(voiceId.toByteArray(Charsets.UTF_8))
        md.update("\n".toByteArray(Charsets.UTF_8))
        val sizeBytes = ByteArray(4)
        for (asset in assets) {
            md.update(asset.fillerId.toByteArray(Charsets.UTF_8))
            md.update(":".toByteArray(Charsets.UTF_8))
            val size = asset.data.size
            sizeBytes[0] = (size and 0xFF).toByte()
            sizeBytes[1] = ((size shr 8) and 0xFF).toByte()
            sizeBytes[2] = ((size shr 16) and 0xFF).toByte()
            sizeBytes[3] = ((size shr 24) and 0xFF).toByte()
            md.update(sizeBytes)
            md.update("\n".toByteArray(Charsets.UTF_8))
        }
        val digest = md.digest()
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) hex.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        return hex.substring(0, 16)
    }

    private suspend fun sendPreloadCommandAwaitAck(
        ble: BleManager,
        cmd: String,
        params: org.json.JSONObject,
        timeoutMs: Long,
    ) {
        val result: Int = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val ackDeferred = async {
                    ble.bleEvents.first {
                        it is CallMateBleEvent.Ack && (it as CallMateBleEvent.Ack).cmd == cmd
                    } as CallMateBleEvent.Ack
                }
                yield()
                ble.sendCommand(cmd, params, expectAck = true)
                ackDeferred.await().result
            }
        } ?: throw IOException("coord: ack timeout for $cmd")
        if (result != 0) {
            throw IOException("coord: $cmd ack=$result")
        }
    }
}
