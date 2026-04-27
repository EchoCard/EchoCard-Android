package com.vaca.callmate.core.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.vaca.callmate.core.firmware.CRC32MPEG2
import com.vaca.callmate.core.network.TTSFillerItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 与 iOS `TTSFillerEncoder.swift` 对齐：把服务端下发的 filler mp3 (24 kHz mono, ~0.7–1.2 s)
 * 转成一条纯 mSBC 57B 帧流，交给 BLE 预加载流程推到 MCU。
 *
 * 流水线：
 *   mp3 → MediaExtractor/MediaCodec（系统 mp3 解码）→ Float32 PCM at source rate
 *       → 手写线性插值重采样 → 16 kHz mono Float32
 *       → 夹到 Int16（每 120 样本 = 7.5 ms = 1 个 mSBC 帧）
 *       → libsbc `MsbcEncoder` → 每帧 57 B，拼起来返回
 *
 * 和 iOS 一样：输出**不带** H2 头（2 字节同步计数器由 MCU 在 HFP eSCO TX 注入时补）。
 * 详见 docs/tts-filler-low-latency.md §5.2。
 */
data class TTSFillerEncodedAsset(
    val fillerId: String,
    /** 连续拼接的 SBC 帧，每帧 57B；frames == data.size / 57 */
    val data: ByteArray,
    val frames: Int,
    /** 对 [data] 整体做 CRC32/MPEG2（与 iOS 一致）；以 Long 承载 UInt32。 */
    val crc32: Long,
) {
    val durationMs: Int get() = frames * 15 / 2

    // ByteArray-sensitive equals/hashCode so Compose/Flow dedup works.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TTSFillerEncodedAsset) return false
        return fillerId == other.fillerId &&
            frames == other.frames &&
            crc32 == other.crc32 &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = fillerId.hashCode()
        result = 31 * result + frames
        result = 31 * result + crc32.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

object TTSFillerEncoder {

    private const val TAG = "TTSFillers"
    private const val MSBC_FRAME_BYTES = MsbcEncoder.FRAME_BYTES
    private const val MSBC_SAMPLES_PER_FRAME = MsbcEncoder.SAMPLES_PER_FRAME
    private const val TARGET_SAMPLE_RATE = 16000
    const val MAX_ENCODED_BYTES = 32 * 1024

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun encode(item: TTSFillerItem, cacheDir: File): TTSFillerEncodedAsset =
        withContext(Dispatchers.IO) {
            val tmp = downloadToTemporary(item.audioUrl, cacheDir)
            try {
                val msbc = encodeLocalMP3(tmp)
                val frames = msbc.size / MSBC_FRAME_BYTES
                if (frames <= 0) throw IOException("TTSFillerEncoder: empty output")
                val crc = CRC32MPEG2.checksum(msbc).toLong() and 0xFFFFFFFFL
                Log.i(TAG, "encode id=${item.fillerId} frames=$frames bytes=${msbc.size} crc32=0x${crc.toString(16)}")
                TTSFillerEncodedAsset(item.fillerId, msbc, frames, crc)
            } finally {
                try {
                    tmp.delete()
                } catch (_: Exception) {
                }
            }
        }

    /**
     * 与 iOS `encodeLocalMP3(at:)` 对齐。公开出来便于离线/手工测试指向已下载好的 mp3。
     */
    fun encodeLocalMP3(file: File): ByteArray {
        val (pcmFloat, srcRate) = decodeToFloatMono(file)
        if (pcmFloat.isEmpty()) throw IOException("TTSFillerEncoder: 0 decoded samples")
        val resampled = linearResample(pcmFloat, srcRate, TARGET_SAMPLE_RATE)
        if (resampled.isEmpty()) throw IOException("TTSFillerEncoder: 0 resampled samples")

        var pcm16Size = resampled.size
        // 裁掉凑不满 1 个 mSBC 帧的尾巴。
        val extra = pcm16Size % MSBC_SAMPLES_PER_FRAME
        if (extra > 0) pcm16Size -= extra
        if (pcm16Size <= 0) throw IOException("TTSFillerEncoder: too short after resample")

        val pcm16 = ShortArray(pcm16Size)
        for (i in 0 until pcm16Size) {
            val v = resampled[i] * 32768.0f
            pcm16[i] = when {
                v >= 32767f -> Short.MAX_VALUE
                v <= -32768f -> Short.MIN_VALUE
                else -> v.toInt().toShort()
            }
        }

        val encoder = MsbcEncoder()
        try {
            val encoded = encoder.encode(pcm16) ?: throw IOException("TTSFillerEncoder: mSBC encode failed")
            if (encoded.size > MAX_ENCODED_BYTES) {
                val capped = MAX_ENCODED_BYTES - (MAX_ENCODED_BYTES % MSBC_FRAME_BYTES)
                Log.w(TAG, "encode bytes=${encoded.size} > cap=$MAX_ENCODED_BYTES, truncating to $capped")
                return encoded.copyOfRange(0, capped)
            }
            return encoded
        } finally {
            encoder.release()
        }
    }

    // MARK: - Pipeline

    /**
     * 用系统 MediaExtractor + MediaCodec 把 mp3 解码成 Float PCM（mono，down-mix 多声道）。
     * 返回 (samples, srcRate)。
     */
    private fun decodeToFloatMono(file: File): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var audioTrack = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                audioTrack = i
                format = f
                break
            }
        }
        if (audioTrack < 0 || format == null) {
            extractor.release()
            throw IOException("TTSFillerEncoder: no audio track")
        }
        extractor.selectTrack(audioTrack)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmBytes = java.io.ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        val timeoutUs = 10_000L

        try {
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val size = extractor.readSampleData(inBuf!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    val chunk = ByteArray(bufferInfo.size)
                    outBuf.get(chunk)
                    pcmBytes.write(chunk)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        val raw = pcmBytes.toByteArray()
        // MediaCodec audio decoder outputs 16-bit signed little-endian PCM by default on API 24+.
        // Convert to Float [-1, 1] and downmix to mono if channelCount > 1.
        val shortBuf: ShortBuffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val totalShorts = shortBuf.limit()
        if (totalShorts <= 0 || channelCount <= 0) {
            return FloatArray(0) to srcRate
        }
        val monoCount = totalShorts / channelCount
        val mono = FloatArray(monoCount)
        if (channelCount == 1) {
            for (i in 0 until monoCount) {
                mono[i] = shortBuf.get(i).toFloat() / 32768.0f
            }
        } else {
            for (i in 0 until monoCount) {
                var sum = 0.0f
                for (c in 0 until channelCount) {
                    sum += shortBuf.get(i * channelCount + c).toFloat()
                }
                mono[i] = sum / (channelCount * 32768.0f)
            }
        }
        return mono to srcRate
    }

    /**
     * 与 iOS `linearResample` 对齐：朴素线性插值。24 kHz→16 kHz 对低频语音 filler 听感足够干净。
     */
    private fun linearResample(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (src.isEmpty() || srcRate <= 0 || dstRate <= 0) return FloatArray(0)
        if (srcRate == dstRate) return src
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outCount = maxOf(1, (src.size / ratio).toInt())
        val out = FloatArray(outCount)
        for (j in 0 until outCount) {
            val x = j * ratio
            val i0 = x.toInt()
            if (i0 >= src.size - 1) {
                out[j] = src[src.size - 1]
                continue
            }
            val i1 = i0 + 1
            val frac = (x - i0).toFloat()
            out[j] = src[i0] * (1f - frac) + src[i1] * frac
        }
        return out
    }

    private fun downloadToTemporary(url: String, cacheDir: File): File {
        val file = File(cacheDir, "filler-${UUID.randomUUID()}.mp3")
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("TTSFillerEncoder: HTTP ${resp.code} on $url")
            }
            val body = resp.body ?: throw IOException("TTSFillerEncoder: empty body")
            FileOutputStream(file).use { fos ->
                body.byteStream().use { it.copyTo(fos) }
            }
        }
        return file
    }
}
