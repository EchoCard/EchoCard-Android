package com.vaca.callmate.features.calls

import android.content.Context
import android.util.Log
import com.vaca.callmate.core.audio.CallRecordingFiles
import com.vaca.callmate.core.audio.ConversationStereoWavWriter
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.CallMateBleEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.vaca.callmate.core.audio.LibOpusDecoder
import java.util.UUID

private const val TAG = "LiveCallRec"
private const val SAMPLE_RATE = 16000

/**
 * 与 iOS `AudioService` 会话录音对齐：左=BLE 下行 Opus（来电方），右=云端 TTS Opus（上行至 MCU 前同帧）。
 */
class LiveCallConversationRecorder(
    private val context: Context,
    private val bleManager: BleManager,
    private val scope: CoroutineScope,
    private val callId: UUID,
) {
    private var writer: ConversationStereoWavWriter? = null
    private var fileName: String? = null
    private var downlinkJob: Job? = null
    private val leftDecoder = LibOpusDecoder(SAMPLE_RATE, 1)
    private val rightDecoder = LibOpusDecoder(SAMPLE_RATE, 1)
    private var decodersReleased = false

    fun start() {
        if (writer != null) return
        val f = CallRecordingFiles.file(context, "live_${callId}.wav")
        writer = ConversationStereoWavWriter(f, sampleRate = SAMPLE_RATE)
        fileName = f.name
        // 独立流 + DROP_OLDEST，避免与实时 BLE→WS 桥接争用 SharedFlow 背压
        downlinkJob = scope.launch(Dispatchers.IO) {
            bleManager.bleEventsRecording.collect { ev ->
                when (ev) {
                    is CallMateBleEvent.AudioDownlinkOpus -> decodeLeft(ev.payload)
                    else -> Unit
                }
            }
        }
    }

    private fun decodeLeft(data: ByteArray) {
        if (data.isEmpty()) return
        val w = writer ?: return
        try {
            val pcm = ShortArray(960)
            val samples = leftDecoder.decode(data, 0, data.size, pcm, 0, 960, false)
            if (samples > 0) {
                w.appendLeftPcm(pcm, samples)
            }
        } catch (e: Exception) {
            Log.w(TAG, "downlink opus: ${e.message}")
        }
    }

    fun onTtsOpusFrame(data: ByteArray) {
        if (data.isEmpty()) return
        val w = writer ?: return
        try {
            val pcm = ShortArray(960)
            val samples = rightDecoder.decode(data, 0, data.size, pcm, 0, 960, false)
            if (samples > 0) {
                w.appendRightPcm(pcm, samples)
            }
        } catch (e: Exception) {
            Log.w(TAG, "tts opus: ${e.message}")
        }
    }

    fun stop(): String? {
        downlinkJob?.cancel()
        downlinkJob = null
        val n = fileName
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        fileName = null
        if (!decodersReleased) {
            leftDecoder.release()
            rightDecoder.release()
            decodersReleased = true
        }
        return n
    }
}
