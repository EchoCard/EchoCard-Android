package com.vaca.callmate.core.firmware

import android.util.Log
import com.vaca.callmate.BuildConfig
import com.vaca.callmate.core.ble.BleFirmwarePackets
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.CallMateBleEvent
import com.vaca.callmate.core.ble.FirmwareMissingRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 与 iOS `FirmwareUpdateService` 对齐：检查版本、下载、CRC、`fw_begin`、盲发、`fw_verify`、
 * `fw_end` 及重启后状态清理。
 */
object FirmwareUpdateService {

    private const val TAG = "FirmwareUpdate"

    /** 与 iOS `@AppStorage("fw_server_base_url")` 默认一致 */
    val DEFAULT_SERVER_BASE_URL get() = BuildConfig.FW_SERVER_BASE_URL

    enum class FirmwareUpdateStage {
        Idle,
        Downloading,
        Upgrading,
        Rebooting,
    }

    data class FirmwareMetadata(
        val device: String,
        val version: String,
        val size: Int,
        val sha256: String,
        val crc32: Long,
        val url: String,
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val checkMutex = Mutex()
    private val otaMutex = Mutex()

    private val otaScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var reconnectWatcherJob: Job? = null

    @Volatile
    private var waitingForReconnectAfterUpdate: Boolean = false

    private val _latestMetadata = MutableStateFlow<FirmwareMetadata?>(null)
    val latestMetadata: StateFlow<FirmwareMetadata?> = _latestMetadata.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _updateStage = MutableStateFlow(FirmwareUpdateStage.Idle)
    val updateStage: StateFlow<FirmwareUpdateStage> = _updateStage.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0.0)
    val downloadProgress: StateFlow<Double> = _downloadProgress.asStateFlow()

    private val _upgradeProgress = MutableStateFlow(0.0)
    val upgradeProgress: StateFlow<Double> = _upgradeProgress.asStateFlow()

    private val _progress = MutableStateFlow(0.0)
    val progress: StateFlow<Double> = _progress.asStateFlow()

    private val _transferSpeedKBps = MutableStateFlow(0.0)
    val transferSpeedKBps: StateFlow<Double> = _transferSpeedKBps.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    /** 与 iOS `FirmwareUpdateService.deviceName(for:)` 一致 */
    fun deviceNameForChip(chipName: String?): String {
        return when (chipName?.trim()) {
            "sf32lb52j" -> "callmate-sf32lb52j"
            "sf32lb525" -> "callmate-sf32lb525"
            else -> "callmate-sf32lb525"
        }
    }

    private fun t(zh: Boolean, zhString: String, enString: String) = if (zh) zhString else enString

    /**
     * GET `/api/firmware/latest?device=...`，解析 JSON 为 [FirmwareMetadata]。
     */
    suspend fun checkForUpdate(chipName: String?, serverBaseUrl: String = DEFAULT_SERVER_BASE_URL) {
        checkMutex.withLock {
            if (_isChecking.value) return@withLock
            _isChecking.value = true
            _lastError.value = null
            try {
                val device = deviceNameForChip(chipName)
                val base = serverBaseUrl.trimEnd('/')
                val url = "$base/api/firmware/latest?device=$device"
                Log.i(TAG, "checkForUpdate chip=$chipName device=$device url=$url")

                withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(url).get().build()
                    client.newCall(req).execute().use { resp ->
                        val code = resp.code
                        val body = resp.body?.string().orEmpty()
                        if (code != 200) {
                            Log.w(TAG, "HTTP $code body=${body.take(200)}")
                            _lastError.value = "http_$code"
                            return@withContext
                        }
                        val json = JSONObject(body)
                        val crc = when {
                            json.has("crc32") && !json.isNull("crc32") -> json.optLong("crc32", 0L)
                            else -> 0L
                        }
                        val meta = FirmwareMetadata(
                            device = json.getString("device"),
                            version = json.getString("version"),
                            size = json.getInt("size"),
                            sha256 = json.getString("sha256"),
                            crc32 = crc,
                            url = json.getString("url"),
                        )
                        _latestMetadata.value = meta
                        Log.i(TAG, "OK version=${meta.version} size=${meta.size}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkForUpdate failed", e)
                _lastError.value = e.message
            } finally {
                _isChecking.value = false
            }
        }
    }

    /**
     * 与 iOS `startUpdateIfAvailable()` 对齐：需已 [checkForUpdate] 且设备已连接。
     */
    suspend fun startUpdateIfAvailable(
        bleManager: BleManager,
        languageZh: Boolean = true,
        serverBaseUrl: String = DEFAULT_SERVER_BASE_URL,
    ) {
        val meta = _latestMetadata.value
        if (meta == null) {
            Log.w(TAG, "startUpdateIfAvailable: no metadata; tap check first")
            _lastError.value = t(
                languageZh,
                "还没有可用的更新信息，请先点击「检查更新」。",
                "No update info yet. Please check for updates first.",
            )
            return
        }
        startUpdate(bleManager, meta, languageZh, serverBaseUrl)
    }

    suspend fun startUpdate(
        bleManager: BleManager,
        metadata: FirmwareMetadata,
        languageZh: Boolean = true,
        serverBaseUrl: String = DEFAULT_SERVER_BASE_URL,
    ) {
        otaMutex.withLock {
            if (_isUpdating.value) {
                Log.w(TAG, "startUpdate: already updating")
                return@withLock
            }
            _isUpdating.value = true
        }

        reconnectWatcherJob?.cancel()
        _lastError.value = null
        waitingForReconnectAfterUpdate = false
        _updateStage.value = FirmwareUpdateStage.Downloading
        _downloadProgress.value = 0.0
        _upgradeProgress.value = 0.0
        _progress.value = 0.0
        _transferSpeedKBps.value = 0.0
        _statusText.value = t(languageZh, "正在下载更新包，请稍候…", "Downloading update package...")

        Log.i(TAG, "====== OTA UPDATE START version=${metadata.version} size=${metadata.size} ======")

        try {
            if (!bleManager.isOtaReady()) {
                throw IOException(
                    t(languageZh, "未找到 OTA 特征，请重连设备后重试。", "OTA not ready. Reconnect and try again."),
                )
            }

            val firmwareData = withContext(Dispatchers.IO) {
                downloadFirmware(metadata.url, metadata.size, serverBaseUrl)
            }
            _downloadProgress.value = 1.0
            _progress.value = 1.0

            if (firmwareData.size != metadata.size) {
                throw IOException("Size mismatch: got ${firmwareData.size}, expected ${metadata.size}")
            }

            val crc32 = CRC32MPEG2.checksum(firmwareData)
            val gotCrc = crc32.toLong() and 0xFFFFFFFFL
            val expCrc = metadata.crc32 and 0xFFFFFFFFL
            if (gotCrc != expCrc) {
                Log.e(TAG, "CRC mismatch computed=0x${gotCrc.toString(16)} expected=0x${expCrc.toString(16)}")
                throw IOException(
                    t(languageZh, "固件校验失败（CRC32 不一致）", "CRC32 mismatch"),
                )
            }

            val bleMaxPayload = bleManager.otaMaxChunkPayloadBytes()
            val rawChunkSize = minOf(480, maxOf(120, if (bleMaxPayload > 0) bleMaxPayload else 480))
            val alignedChunkSize = (rawChunkSize / 4) * 4
            val chunkSize = maxOf(120, alignedChunkSize)
            val totalChunks = (firmwareData.size + chunkSize - 1) / chunkSize

            _updateStage.value = FirmwareUpdateStage.Upgrading
            _upgradeProgress.value = 0.0
            _progress.value = 0.0
            _statusText.value = t(
                languageZh,
                "正在准备升级，请保持设备靠近手机。",
                "Preparing update. Keep device near your phone.",
            )

            val beginParams = JSONObject().apply {
                put("size", firmwareData.size)
                put("crc32", gotCrc)
                put("chunk", chunkSize)
                put("version", metadata.version)
            }
            val beginResult = sendAndAwaitAck(bleManager, "fw_begin", beginParams, 30_000L)
            if (beginResult != 0) {
                throw IOException("fw_begin rejected (result=$beginResult)")
            }

            delay(50)

            val packets = prebuildAllPackets(firmwareData, chunkSize)
            blindUpload(bleManager, packets, totalChunks, chunkSize, languageZh)

            var verifiedComplete = false
            var round = 1
            val maxVerifyRounds = 4
            while (round <= maxVerifyRounds) {
                _statusText.value = t(
                    languageZh,
                    "正在校验数据（第 $round/$maxVerifyRounds 轮）",
                    "Verifying data (round $round/$maxVerifyRounds)",
                )
                val (ackRes, miss) = sendFwVerifyAndAwait(bleManager, 10_000L)
                if (ackRes != 0) {
                    throw IOException("fw_verify rejected (result=$ackRes)")
                }
                if (miss.complete || miss.missingChunks == 0) {
                    verifiedComplete = true
                    break
                }
                resendMissing(bleManager, miss.ranges, chunkSize, firmwareData, languageZh)
                round++
            }
            if (!verifiedComplete) {
                throw IOException("fw_verify: unresolved missing chunks")
            }

            _statusText.value = t(languageZh, "即将完成，正在写入设备…", "Almost done. Writing update to device...")
            val endResult = sendAndAwaitAck(bleManager, "fw_end", JSONObject(), 10_000L)
            if (endResult != 0) {
                throw IOException("fw_end failed (result=$endResult)")
            }

            waitingForReconnectAfterUpdate = true
            _updateStage.value = FirmwareUpdateStage.Rebooting
            _upgradeProgress.value = 1.0
            _progress.value = 1.0
            _transferSpeedKBps.value = 0.0
            _statusText.value = t(
                languageZh,
                "更新已发送，设备正在重启（约 5-10 秒）。",
                "Update sent. Device is rebooting (about 5-10s).",
            )

            reconnectWatcherJob = otaScope.launch {
                try {
                    if (bleManager.isCtrlReady.value) {
                        withTimeout(60_000L) {
                            bleManager.isCtrlReady.first { !it }
                        }
                    }
                    withTimeout(120_000L) {
                        bleManager.isCtrlReady.first { it }
                    }
                    if (waitingForReconnectAfterUpdate && _lastError.value == null) {
                        waitingForReconnectAfterUpdate = false
                        _updateStage.value = FirmwareUpdateStage.Idle
                        _statusText.value = ""
                    }
                } catch (_: CancellationException) {
                } catch (e: Exception) {
                    Log.w(TAG, "reconnect watcher", e)
                }
            }

            Log.i(TAG, "====== OTA UPDATE COMPLETE ======")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "OTA failed", e)
            waitingForReconnectAfterUpdate = false
            reconnectWatcherJob?.cancel()
            _updateStage.value = FirmwareUpdateStage.Idle
            _transferSpeedKBps.value = 0.0
            _lastError.value = t(
                languageZh,
                "升级没有完成，请保持设备靠近手机后重试。",
                "Update did not complete. Keep the device near your phone and try again.",
            )
            _statusText.value = t(languageZh, "升级未完成", "Update not completed")
            Log.e(TAG, "====== OTA UPDATE FAILED ======")
        } finally {
            _isUpdating.value = false
        }
    }

    private fun prebuildAllPackets(firmwareData: ByteArray, chunkSize: Int): List<ByteArray> {
        val total = firmwareData.size
        val totalChunks = (total + chunkSize - 1) / chunkSize
        val out = ArrayList<ByteArray>(totalChunks)
        var offset = 0
        var index = 0u
        while (offset < total) {
            val end = minOf(offset + chunkSize, total)
            val chunk = firmwareData.copyOfRange(offset, end)
            val crc = CRC32MPEG2.checksum(chunk)
            out.add(BleFirmwarePackets.buildOtaChunkPacket(index, offset.toUInt(), chunk, crc))
            offset = end
            index++
        }
        return out
    }

    private suspend fun blindUpload(
        bleManager: BleManager,
        packets: List<ByteArray>,
        totalChunks: Int,
        chunkSize: Int,
        languageZh: Boolean,
    ) {
        _statusText.value = t(languageZh, "正在传输更新包…", "Transferring update...")
        bleManager.resetOtaDirectQueue()
        bleManager.loadOtaPackets(packets)

        delay(250)
        val uploadStart = System.nanoTime()
        var prevSentChunks = 0
        var prevPollTime = uploadStart

        while (coroutineContext.isActive) {
            val remaining = bleManager.getOtaQueueDepthOrAbort()
            if (remaining < 0) {
                throw IOException("Device disconnected during update")
            }
            val sentChunks = totalChunks - remaining
            val now = System.nanoTime()
            val intervalChunks = sentChunks - prevSentChunks
            val intervalElapsed = (now - prevPollTime) / 1_000_000_000.0
            val txKBps = if (intervalElapsed > 0.05) {
                (intervalChunks * chunkSize) / 1024.0 / intervalElapsed
            } else {
                0.0
            }
            val currentProgress = if (totalChunks > 0) {
                (sentChunks.toDouble() / totalChunks).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            _upgradeProgress.value = currentProgress
            _progress.value = currentProgress
            _transferSpeedKBps.value = txKBps
            val pct = (currentProgress * 100).toInt()
            _statusText.value = String.format(
                t(languageZh, "正在升级 %d%%（%d/%d）", "Updating %d%% (%d/%d)"),
                pct,
                sentChunks,
                totalChunks,
            )

            prevSentChunks = sentChunks
            prevPollTime = now

            if (remaining == 0) break
            delay(250)
        }
    }

    private suspend fun resendMissing(
        bleManager: BleManager,
        ranges: List<FirmwareMissingRange>,
        chunkSize: Int,
        firmwareData: ByteArray,
        languageZh: Boolean,
    ) {
        if (ranges.isEmpty()) return
        val resendPackets = ArrayList<ByteArray>()
        for (range in ranges) {
            if (range.end < range.start) continue
            for (idx in range.start..range.end) {
                val chunkOffset = idx * chunkSize
                if (chunkOffset >= firmwareData.size) break
                val end = minOf(chunkOffset + chunkSize, firmwareData.size)
                val chunk = firmwareData.copyOfRange(chunkOffset, end)
                val crc = CRC32MPEG2.checksum(chunk)
                resendPackets.add(
                    BleFirmwarePackets.buildOtaChunkPacket(
                        idx.toUInt(),
                        chunkOffset.toUInt(),
                        chunk,
                        crc,
                    ),
                )
            }
        }
        if (resendPackets.isEmpty()) return
        Log.i(TAG, "[OTA] resend: ${resendPackets.size} packets for ${ranges.size} range(s)")
        bleManager.resetOtaDirectQueue()
        bleManager.loadOtaPackets(resendPackets)
        delay(250)
        while (coroutineContext.isActive) {
            val remaining = bleManager.getOtaQueueDepthOrAbort()
            if (remaining < 0) throw IOException("Device disconnected during update")
            if (remaining == 0) break
            delay(250)
        }
    }

    private suspend fun sendAndAwaitAck(
        bleManager: BleManager,
        cmd: String,
        params: JSONObject,
        timeoutMs: Long,
    ): Int {
        val result = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val deferred = async {
                    bleManager.bleEvents.first {
                        it is CallMateBleEvent.Ack && (it as CallMateBleEvent.Ack).cmd == cmd
                    } as CallMateBleEvent.Ack
                }
                yield()
                bleManager.sendCommand(cmd, params, expectAck = true)
                deferred.await().result
            }
        }
        return result ?: throw IOException("ack timeout: $cmd")
    }

    private suspend fun sendFwVerifyAndAwait(
        bleManager: BleManager,
        timeoutMs: Long,
    ): Pair<Int, CallMateBleEvent.FirmwareMissing> {
        val pair = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val ackJ = async {
                    bleManager.bleEvents.first {
                        it is CallMateBleEvent.Ack && (it as CallMateBleEvent.Ack).cmd == "fw_verify"
                    } as CallMateBleEvent.Ack
                }
                val missJ = async {
                    bleManager.bleEvents.first { it is CallMateBleEvent.FirmwareMissing }
                }
                yield()
                bleManager.sendCommand("fw_verify", JSONObject(), expectAck = true)
                Pair(ackJ.await().result, missJ.await() as CallMateBleEvent.FirmwareMissing)
            }
        }
        return pair ?: throw IOException("fw_verify timeout")
    }

    private suspend fun downloadFirmware(
        urlString: String,
        expectedSize: Int,
        serverBaseUrl: String,
    ): ByteArray {
        val full = if (urlString.startsWith("http://") || urlString.startsWith("https://")) {
            urlString
        } else {
            serverBaseUrl.trimEnd('/') + urlString
        }
        Log.i(TAG, "download URL: $full")
        val req = Request.Builder().url(full).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IOException("empty body")
            val contentLen = body.contentLength()
            val total = if (contentLen > 0) contentLen else expectedSize.toLong()
            val out = ByteArrayOutputStream()
            body.byteStream().use { ins ->
                val buf = ByteArray(8192)
                var received = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    received += n
                    if (total > 0) {
                        _downloadProgress.value = (received.toDouble() / total).coerceIn(0.0, 1.0)
                        _progress.value = _downloadProgress.value
                    }
                }
            }
            return out.toByteArray()
        }
    }

    /** 与 iOS `isUpdateAvailable(current:latest:)` 一致 */
    fun isUpdateAvailable(current: String?, latest: String?): Boolean {
        val c = current?.trim().orEmpty()
        val l = latest?.trim().orEmpty()
        if (c.isEmpty() || l.isEmpty()) return false
        val cParts = c.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = l.split(".").mapNotNull { it.toIntOrNull() }
        if (cParts.isEmpty() || lParts.isEmpty()) return c != l
        val n = maxOf(cParts.size, lParts.size)
        for (i in 0 until n) {
            val cv = cParts.getOrElse(i) { 0 }
            val lv = lParts.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }
}
