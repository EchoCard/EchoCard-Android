package com.vaca.callmate.core.audio

/**
 * 与 iOS `MSBCCodec.swift` 中 `MSBCEncoder` 对齐的 JNI 绑定。
 *
 * 输入：16 kHz mono Int16 PCM，按 120 样本 / 帧（7.5 ms）分帧。
 * 输出：每帧 57 字节的 mSBC SBC 帧（首字节 `0xAD`，无 H2 头 —— MCU 在 HFP eSCO TX 注入时补）。
 *
 * libsbc context 不是线程安全的，每个 [MsbcEncoder] 实例维护独立 native handle，
 * 只在单一编码链路里使用。
 */
internal object MsbcNative {
    init {
        System.loadLibrary("callmate_opus")
    }

    external fun encoderCreate(): Long
    external fun encoderDestroy(handle: Long)

    /**
     * 编码 [frames] 个 120-样本 mSBC 帧，从 pcm[pcmOffset..] 读入，写到 out[outOffset..]。
     * @return 写入字节数（== frames * 57）成功；<0 失败。
     */
    external fun encoderEncode(
        handle: Long,
        pcm: ShortArray,
        pcmOffset: Int,
        frames: Int,
        out: ByteArray,
        outOffset: Int,
    ): Int
}

class MsbcEncoder {
    companion object {
        const val FRAME_BYTES: Int = 57
        const val SAMPLES_PER_FRAME: Int = 120
    }

    private var nativeHandle: Long = MsbcNative.encoderCreate()

    init {
        if (nativeHandle == 0L) {
            throw IllegalStateException("msbc_encoder_create failed")
        }
    }

    /**
     * 将 16 kHz mono Int16 PCM 编成连续的 mSBC 57B 帧流；尾部不足 120 样本的部分丢弃。
     * 失败返回 null。
     */
    fun encode(pcm: ShortArray): ByteArray? {
        val frames = pcm.size / SAMPLES_PER_FRAME
        if (frames <= 0) return null
        val out = ByteArray(frames * FRAME_BYTES)
        val written = MsbcNative.encoderEncode(nativeHandle, pcm, 0, frames, out, 0)
        if (written != out.size) return null
        return out
    }

    fun release() {
        if (nativeHandle != 0L) {
            MsbcNative.encoderDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }
}
