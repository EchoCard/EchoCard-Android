package com.vaca.callmate.features.outbound

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.vaca.callmate.core.audio.LibOpusEncoder

private const val TAG = "WsManualListen"
private const val SAMPLE_RATE = 16000
/** 与 hello `audio_params.frame_duration` 60ms 对齐 */
private const val FRAME_SAMPLES = 960

/**
 * 对标 iOS `AudioService` + `CallSessionController+AudioDelegate`：麦克风 PCM → Opus 帧，经 [onOpusPacket] 上行。
 * @param audioSource [MediaRecorder.AudioSource.VOICE_COMMUNICATION] 用于模拟通话等 VoIP 场景以启用平台回声消除（对标 iOS `enableEchoCancellation`）。
 */
class WsManualListenRecorder(
    private val context: Context,
    private val onOpusPacket: (ByteArray) -> Unit,
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    /** 可选：拷贝一份 PCM 用于本地 WAV 录音（与上行同一路采集） */
    private val onPcmFrame: ((ShortArray, Int) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var encoder: LibOpusEncoder? = null
    private var audioRecord: AudioRecord? = null

    /** 与 [AudioTrack] 共用会话，便于平台 AEC / 双工（对标 iOS 单引擎持续采集+播放） */
    fun audioSessionId(): Int =
        audioRecord?.audioSessionId?.takeIf { it != 0 } ?: AudioManager.AUDIO_SESSION_ID_GENERATE

    fun start() {
        if (job != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted")
            return
        }
        val enc = try {
            LibOpusEncoder(SAMPLE_RATE, 1)
        } catch (e: Exception) {
            Log.e(TAG, "OpusEncoder init failed", e)
            return
        }
        encoder = enc

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return
        }
        val bufferSize = maxOf(minBuf, FRAME_SAMPLES * 2 * 4)
        val record = AudioRecord(
            audioSource,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Log.e(TAG, "AudioRecord not initialized")
            return
        }
        audioRecord = record
        record.startRecording()

        val pcm = ShortArray(FRAME_SAMPLES)
        val opusOut = ByteArray(4000)
        job = scope.launch {
            while (isActive) {
                val n = record.read(pcm, 0, FRAME_SAMPLES)
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) break
                if (n != FRAME_SAMPLES) continue
                onPcmFrame?.invoke(pcm, FRAME_SAMPLES)
                try {
                    val len = enc.encode(pcm, 0, FRAME_SAMPLES, opusOut, 0, opusOut.size)
                    if (len > 0) {
                        onOpusPacket(opusOut.copyOf(len))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "opus encode: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        encoder?.release()
        encoder = null
    }
}
