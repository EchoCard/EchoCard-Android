package com.vaca.callmate.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 对标 iOS [ConversationAudioWriter]：双声道 16kHz int16 WAV（左=用户侧麦克风，右=AI/TTS），
 * 以墙钟时间为统一时间轴，按固定间隔刷盘混音（与 iOS `flushBuffers` 一致）。
 *
 * 刷盘 [RandomAccessFile.write] 在锁外执行，避免与 [appendLeftPcm]/[appendRightPcm] 长时间互等（解码线程 vs flush 线程）。
 */
class ConversationStereoWavWriter(
    file: File,
    private val sampleRate: Int = 16000,
) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private val lock = Any()
    private val startTimeNanos = System.nanoTime()
    private var writtenSamples: Int = 0
    private val micBuffer = ArrayList<Short>(8192)
    private val ttsBuffer = ArrayList<Short>(8192)
    private var micReadIndex = 0
    private var ttsReadIndex = 0
    private var finished = false
    private var pcmBytes: Long = 0

    private val flushExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ConversationStereoWavWriter-flush").apply { isDaemon = true }
    }
    private val flushFuture: ScheduledFuture<*>

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
        flushFuture = flushExecutor.scheduleAtFixedRate(
            { runCatching { flushBuffersLocked() } },
            FLUSH_INTERVAL_MS.toLong(),
            FLUSH_INTERVAL_MS.toLong(),
            TimeUnit.MILLISECONDS
        )
    }

    fun appendLeftPcm(pcm: ShortArray, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            if (finished) return
            for (i in 0 until length) {
                micBuffer.add(pcm[i])
            }
        }
    }

    fun appendRightPcm(pcm: ShortArray, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            if (finished) return
            for (i in 0 until length) {
                ttsBuffer.add(pcm[i])
            }
        }
    }

    private fun flushBuffersLocked() {
        val pair = synchronized(lock) {
            if (finished) return@synchronized null
            val elapsed = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0
            val targetSamples = (elapsed * sampleRate).toInt()
            val samplesToWrite = targetSamples - writtenSamples
            if (samplesToWrite <= 0) return@synchronized null
            extractStereoSnapshot(samplesToWrite)
        } ?: return
        writeInterleavedStereo(pair.first, pair.second)
    }

    /**
     * 与旧版 [writeStereoFrames] 相同语义；仅在持有 [lock] 时调用。
     */
    private fun extractStereoSnapshot(samplesToWrite: Int): Pair<ShortArray, ShortArray> {
        val micAvailable = micBuffer.size - micReadIndex
        val ttsAvailable = ttsBuffer.size - ttsReadIndex
        val l = ShortArray(samplesToWrite)
        val r = ShortArray(samplesToWrite)
        for (i in 0 until samplesToWrite) {
            l[i] = if (i < micAvailable) micBuffer[micReadIndex++] else 0
            r[i] = if (i < ttsAvailable) ttsBuffer[ttsReadIndex++] else 0
        }
        writtenSamples += samplesToWrite
        trimBuffersIfNeeded()
        return Pair(l, r)
    }

    private fun writeInterleavedStereo(l: ShortArray, r: ShortArray) {
        require(l.size == r.size)
        val n = l.size
        if (n == 0) return
        for (i in 0 until n) {
            val left = l[i].toInt()
            val right = r[i].toInt()
            raf.write(left and 0xff)
            raf.write((left shr 8) and 0xff)
            raf.write(right and 0xff)
            raf.write((right shr 8) and 0xff)
        }
        pcmBytes += n * 4L
    }

    private fun trimBuffersIfNeeded() {
        val trimThreshold = 8192
        if (micReadIndex > trimThreshold) {
            micBuffer.subList(0, micReadIndex).clear()
            micReadIndex = 0
        }
        if (ttsReadIndex > trimThreshold) {
            ttsBuffer.subList(0, ttsReadIndex).clear()
            ttsReadIndex = 0
        }
    }

    override fun close() {
        synchronized(lock) {
            if (finished) return
            finished = true
        }
        flushFuture.cancel(false)
        flushExecutor.shutdown()
        try {
            flushExecutor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        val finalPair = synchronized(lock) {
            val micAvailable = micBuffer.size - micReadIndex
            val ttsAvailable = ttsBuffer.size - ttsReadIndex
            val remainingSamples = maxOf(micAvailable, ttsAvailable)
            if (remainingSamples <= 0) return@synchronized null
            extractStereoSnapshot(remainingSamples)
        }
        finalPair?.let { writeInterleavedStereo(it.first, it.second) }
        try {
            val dataSize = pcmBytes.toInt()
            val riffChunkSize = 36 + dataSize
            raf.seek(0)
            raf.write("RIFF".toByteArray())
            writeLe32(riffChunkSize)
            raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray())
            writeLe32(16)
            writeLe16(1)
            writeLe16(2)
            writeLe32(sampleRate)
            writeLe32(sampleRate * 4)
            writeLe16(4)
            writeLe16(16)
            raf.write("data".toByteArray())
            writeLe32(dataSize)
        } finally {
            raf.close()
        }
    }

    private fun writeLe32(v: Int) {
        raf.write(v and 0xff)
        raf.write((v shr 8) and 0xff)
        raf.write((v shr 16) and 0xff)
        raf.write((v shr 24) and 0xff)
    }

    private fun writeLe16(v: Int) {
        raf.write(v and 0xff)
        raf.write((v shr 8) and 0xff)
    }

    companion object {
        private const val FLUSH_INTERVAL_MS = 100
    }
}
