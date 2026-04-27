package com.vaca.callmate.features.outbound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.vaca.callmate.core.audio.LibOpusDecoder

private const val TAG = "WsTtsAudio"
private const val SAMPLE_RATE = 16000

/**
 * 与 iOS `AudioService.playOpusData` 对齐：解码 WS 二进制 Opus 帧并在本机扬声器播放。
 * 模拟通话等双工场景可 [bindDuplexPlaybackSession] 与 [AudioRecord] 同 session + VOICE_COMMUNICATION，麦与喇叭同时存活。
 */
class WsTtsAudioPlayer(
    private val scope: CoroutineScope,
) {
    private var decoder: LibOpusDecoder? = null
    private var track: AudioTrack? = null
    /** 非 0 时：与麦克风同 audio session，USAGE_VOICE_COMMUNICATION（模拟通话 realtime） */
    @Volatile
    private var duplexPlaybackSessionId: Int = 0

    @Volatile
    var muted: Boolean = false

    /**
     * 与 iOS `PlaybackDecodeWorker` 写入 `conversationWriter.appendTTSPCM(recordingPCMData)` 对齐：
     * 解码后、送入 [AudioTrack] 之前的同一份 16-bit PCM，用于会话录音右声道。
     */
    @Volatile
    var onDecodedPcmForRecording: ((ShortArray, Int) -> Unit)? = null

    private fun ensureDecoder(): LibOpusDecoder {
        decoder?.let { return it }
        return try {
            LibOpusDecoder(SAMPLE_RATE, 1).also { decoder = it }
        } catch (e: Exception) {
            Log.e(TAG, "OpusDecoder init failed", e)
            throw e
        }
    }

    /**
     * 在 [AudioRecord.startRecording] 之后调用，使播放与采集同会话（平台回声消除路由）。
     */
    fun bindDuplexPlaybackSession(sessionId: Int) {
        if (sessionId == 0 || sessionId == AudioManager.AUDIO_SESSION_ID_GENERATE) return
        duplexPlaybackSessionId = sessionId
        scope.launch(Dispatchers.Main) {
            releaseTrackOnly()
            Log.i(TAG, "duplex playback bound to audioSessionId=$sessionId")
        }
    }

    fun clearDuplexPlaybackSession() {
        duplexPlaybackSessionId = 0
        scope.launch(Dispatchers.Main) {
            releaseTrackOnly()
        }
    }

    private fun releaseTrackOnly() {
        try {
            track?.stop()
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }

    private fun ensureTrack(): AudioTrack? {
        track?.let { return it }
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return null
        }
        val duplex = duplexPlaybackSessionId != 0
        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(
                if (duplex) AudioAttributes.USAGE_VOICE_COMMUNICATION
                else AudioAttributes.USAGE_MEDIA
            )
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val sessionForCtor = if (duplex) duplexPlaybackSessionId else AudioManager.AUDIO_SESSION_ID_GENERATE
        val t = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack(
                attr,
                fmt,
                minBuf * 2,
                AudioTrack.MODE_STREAM,
                sessionForCtor
            )
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
                AudioTrack.MODE_STREAM
            )
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            Log.e(TAG, "AudioTrack not initialized")
            return null
        }
        track = t
        return t
    }

    fun onTtsStart() {
        scope.launch(Dispatchers.Main) {
            try {
                ensureDecoder()
                ensureTrack()?.play()
            } catch (e: Exception) {
                Log.w(TAG, "onTtsStart: ${e.message}")
            }
        }
    }

    fun onTtsStop() {
        scope.launch(Dispatchers.Main) {
            try {
                track?.pause()
                track?.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun release() {
        scope.launch(Dispatchers.Main) {
            duplexPlaybackSessionId = 0
            releaseTrackOnly()
            decoder?.release()
            decoder = null
            onDecodedPcmForRecording = null
        }
    }

    fun playOpusFrame(data: ByteArray) {
        if (muted || data.isEmpty()) return
        scope.launch(Dispatchers.Main) {
            try {
                val dec = ensureDecoder()
                val tr = ensureTrack() ?: return@launch
                if (tr.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    tr.play()
                }
                val pcm = ShortArray(960)
                val samples = dec.decode(data, 0, data.size, pcm, 0, 960, false)
                if (samples > 0) {
                    tr.write(pcm, 0, samples)
                    onDecodedPcmForRecording?.invoke(pcm.copyOf(samples), samples)
                }
            } catch (e: Exception) {
                Log.w(TAG, "opus decode: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "play frame: ${e.message}")
            }
        }
    }
}
