package com.vaca.callmate.core.audio

/**
 * JNI 绑定到 libopus（与 iOS [RealOpusEncoder]/[RealOpusDecoder] 一致：VOIP、24 kbps、16-bit PCM）。
 */
internal object LibOpusNative {
    init {
        System.loadLibrary("callmate_opus")
    }

    external fun encoderCreate(sampleRate: Int, channels: Int): Long

    external fun encoderDestroy(handle: Long)

    external fun encoderEncode(
        handle: Long,
        pcm: ShortArray,
        pcmOffset: Int,
        frameSamples: Int,
        out: ByteArray,
        outOffset: Int,
        maxOutBytes: Int,
    ): Int

    external fun decoderCreate(sampleRate: Int, channels: Int): Long

    external fun decoderDestroy(handle: Long)

    external fun decoderDecode(
        handle: Long,
        opus: ByteArray,
        opusOffset: Int,
        opusLen: Int,
        pcm: ShortArray,
        pcmOffset: Int,
        frameSamples: Int,
        decodeFec: Boolean,
    ): Int
}

/**
 * 对标 iOS `RealOpusEncoder`（[opus_encoder_create] + [OPUS_SET_BITRATE] 24000）。
 */
class LibOpusEncoder(
    sampleRate: Int,
    channels: Int,
) {
    private var nativeHandle: Long = LibOpusNative.encoderCreate(sampleRate, channels)

    init {
        if (nativeHandle == 0L) {
            throw IllegalStateException("opus_encoder_create failed")
        }
    }

    fun encode(
        pcm: ShortArray,
        pcmOffset: Int,
        frameSamples: Int,
        opus: ByteArray,
        opusOffset: Int,
        maxOpusBytes: Int,
    ): Int =
        LibOpusNative.encoderEncode(nativeHandle, pcm, pcmOffset, frameSamples, opus, opusOffset, maxOpusBytes)

    fun release() {
        if (nativeHandle != 0L) {
            LibOpusNative.encoderDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }
}

/**
 * 对标 iOS `RealOpusDecoder`（[opus_decoder_create] + [opus_decode]）。
 */
class LibOpusDecoder(
    sampleRate: Int,
    channels: Int,
) {
    private var nativeHandle: Long = LibOpusNative.decoderCreate(sampleRate, channels)

    init {
        if (nativeHandle == 0L) {
            throw IllegalStateException("opus_decoder_create failed")
        }
    }

    fun decode(
        opus: ByteArray,
        opusOffset: Int,
        opusLen: Int,
        pcm: ShortArray,
        pcmOffset: Int,
        frameSize: Int,
        decodeFec: Boolean,
    ): Int =
        LibOpusNative.decoderDecode(nativeHandle, opus, opusOffset, opusLen, pcm, pcmOffset, frameSize, decodeFec)

    fun release() {
        if (nativeHandle != 0L) {
            LibOpusNative.decoderDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }
}
