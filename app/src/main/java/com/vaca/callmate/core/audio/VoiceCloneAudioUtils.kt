package com.vaca.callmate.core.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

object VoiceCloneAudioUtils {

    enum class TrainingSampleValidation {
        Valid,
        TooShort,
        Silent,
    }

    private const val TAG = "VoiceCloneAudioUtils"
    private const val MIN_DURATION_MS = 3_000L
    private const val MIN_FILE_BYTES = 4_000L
    private const val RMS_THRESHOLD = 0.008f
    private const val PEAK_THRESHOLD = 0.05f
    private const val CODEC_TIMEOUT_US = 10_000L

    fun durationMs(file: File): Long {
        if (!file.exists()) return 0L
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    /** 与 iOS 校验语义对齐：≥3s，且录音里必须有可听见的人声/波形。 */
    fun validateTrainingSample(file: File): TrainingSampleValidation {
        val dur = durationMs(file)
        if (dur < MIN_DURATION_MS || file.length() < MIN_FILE_BYTES) {
            return TrainingSampleValidation.TooShort
        }
        return if (hasAudibleSignal(file)) {
            TrainingSampleValidation.Valid
        } else {
            TrainingSampleValidation.Silent
        }
    }

    fun isValidTrainingSample(file: File): Boolean =
        validateTrainingSample(file) == TrainingSampleValidation.Valid

    private fun hasAudibleSignal(file: File): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(file.absolutePath)
            var audioTrack = -1
            var formatFound: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    formatFound = format
                    break
                }
            }
            if (audioTrack < 0 || formatFound == null) {
                Log.w(TAG, "hasAudibleSignal: no audio track")
                return false
            }
            extractor.selectTrack(audioTrack)
            val mime = formatFound.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.isEmpty()) {
                Log.w(TAG, "hasAudibleSignal: empty mime")
                return false
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(formatFound, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var sumSquares = 0.0
            var sampleCount = 0L
            var peak = 0f

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val size = extractor.readSampleData(inputBuffer!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val pcmChunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(pcmChunk)
                            val shortBuffer = ByteBuffer
                                .wrap(pcmChunk)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                            while (shortBuffer.hasRemaining()) {
                                val normalized = abs(shortBuffer.get().toInt()) / 32768.0f
                                peak = maxOf(peak, normalized)
                                sumSquares += normalized * normalized
                                sampleCount += 1
                                if (peak >= PEAK_THRESHOLD) {
                                    codec.releaseOutputBuffer(outputIndex, false)
                                    return true
                                }
                            }
                            if (sampleCount > 0) {
                                val rms = sqrt(sumSquares / sampleCount.toDouble()).toFloat()
                                if (rms >= RMS_THRESHOLD) {
                                    codec.releaseOutputBuffer(outputIndex, false)
                                    return true
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            }

            sampleCount > 0 && (
                peak >= PEAK_THRESHOLD ||
                    sqrt(sumSquares / sampleCount.toDouble()).toFloat() >= RMS_THRESHOLD
                )
        } catch (e: Exception) {
            Log.w(TAG, "hasAudibleSignal failed: ${e.message}")
            false
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }
}
