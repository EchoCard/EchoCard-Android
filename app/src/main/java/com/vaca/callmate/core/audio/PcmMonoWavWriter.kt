package com.vaca.callmate.core.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * 流式写入 16-bit mono PCM 为 WAV（详情页 [android.media.MediaPlayer] 可直接播放）。
 */
class PcmMonoWavWriter(
    file: File,
    private val sampleRate: Int = 16000
) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private var pcmBytes: Long = 0

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    fun writePcmShorts(pcm: ShortArray, length: Int) {
        for (i in 0 until length) {
            val s = pcm[i].toInt()
            raf.write(s and 0xff)
            raf.write((s shr 8) and 0xff)
        }
        pcmBytes += length * 2L
    }

    override fun close() {
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
            writeLe16(1)
            writeLe32(sampleRate)
            writeLe32(sampleRate * 2)
            writeLe16(2)
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
}
