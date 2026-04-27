package com.vaca.callmate.features.device.latency

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * HFP/SCO 下 16 kHz 方波播放 + 录音，与 iOS `LatencyTestRunner` 音频段对齐。
 */
class LatencyAudioEngine(private val context: Context) {

    private val tag = "LatencyAudio"
    private val sampleRate = 16000
    private val squareWaveHz = 500.0

    private var audioManager: AudioManager? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private val stopFlag = AtomicBoolean(false)

    /** 与 iOS `cachedPlaybackSamples` 对齐，供波形分析。 */
    var lastPlaybackFloatsForAnalysis: FloatArray = FloatArray(0)
        private set

    /** 与 iOS `playStartTime` 对齐（[System.nanoTime]）。 */
    var playStartNsForMeasurement: Long = 0L
        private set

    /**
     * Telecom 自管理通话下，音频路由由 [Connection.setAudioRoute] 控制。
     * 这里设 MODE_IN_COMMUNICATION + 显式选择蓝牙 SCO 输入设备（与 iOS
     * `setPreferredInput(bluetoothHFP)` 对齐），确保 AudioRecord 录蓝牙而非本地 mic。
     */
    fun configureSco() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = false
    }

    fun releaseSco() {
        val am = audioManager ?: return
        am.mode = AudioManager.MODE_NORMAL
    }

    private fun setPreferredBluetoothInput(record: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val am = audioManager ?: return
        val btDevice = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        if (btDevice != null) {
            val ok = record.setPreferredDevice(btDevice)
            Log.i(tag, "setPreferredDevice(BT_SCO input ${btDevice.productName}) = $ok")
        } else {
            Log.w(tag, "No BT_SCO input device found — AudioRecord may use built-in mic")
        }
    }

    private fun setPreferredBluetoothOutput(track: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val am = audioManager ?: return
        val btDevice = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        if (btDevice != null) {
            val ok = track.setPreferredDevice(btDevice)
            Log.i(tag, "setPreferredDevice(BT_SCO output ${btDevice.productName}) = $ok")
        } else {
            Log.w(tag, "No BT_SCO output device found — AudioTrack may use built-in speaker")
        }
    }

    /**
     * 静音播放 + 读麦克风，等待 SCO 稳定（与 iOS priming 一致）。
     */
    fun startPriming() {
        stopAll()
        stopFlag.set(false)
        val minT = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = buildAudioTrack(minT * 2)
        setPreferredBluetoothOutput(track)
        audioTrack = track
        val silence = ShortArray(sampleRate) { 0 }
        track.play()
        thread(name = "latency-priming", isDaemon = true) {
            while (!stopFlag.get()) {
                track.write(silence, 0, silence.size)
            }
        }
        startRecordDrainOnly()
    }

    private fun startRecordDrainOnly() {
        val minR = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = buildAudioRecord(minR * 2)
        setPreferredBluetoothInput(rec)
        audioRecord = rec
        rec.startRecording()
        stopFlag.set(false)
        recordThread = thread(name = "latency-priming-rec", isDaemon = true) {
            val buf = ShortArray(1024)
            while (!stopFlag.get()) {
                rec.read(buf, 0, buf.size)
            }
        }
    }

    /**
     * @param durationSec 单次测试时长；持续测试传较长值并由外部 [stopAll] 结束。
     * @param onMicSamples 每帧 PCM float 回调（连续测试用于 FFT）。
     * @param onFirstEdgeMs 首次超过阈值的延迟（相对播放起点）。
     */
    fun startPlayAndRecord(
        durationSec: Float,
        continuous: Boolean,
        onMicSamples: (FloatArray) -> Unit,
        onFirstEdgeMs: (Double) -> Unit,
        onCompleted: () -> Unit
    ) {
        stopAll()
        stopFlag.set(false)
        val samplesPerCycle = (sampleRate / squareWaveHz).toInt().coerceAtLeast(2)
        val amp = (0.3 * 32767.0).toInt().coerceIn(1, 32767)

        val loopLen = if (continuous) {
            samplesPerCycle * 16
        } else {
            (sampleRate * durationSec).toInt().coerceAtLeast(sampleRate / 2)
        }
        val wave = ShortArray(loopLen) { i ->
            val phase = (i % samplesPerCycle) / samplesPerCycle.toDouble()
            (if (phase < 0.5) amp else -amp).toShort()
        }

        if (!continuous) {
            lastPlaybackFloatsForAnalysis = FloatArray(wave.size) { i -> wave[i] / 32768f }
        } else {
            lastPlaybackFloatsForAnalysis = FloatArray(0)
        }

        val minT = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = buildAudioTrack(minT * 2)
        setPreferredBluetoothOutput(track)
        audioTrack = track
        track.play()

        val minR = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = buildAudioRecord(minR * 2)
        setPreferredBluetoothInput(rec)
        audioRecord = rec
        rec.startRecording()

        val playStartNs = AtomicLong(0L)
        var edgeReported = false
        val chunk = 2048

        thread(name = "latency-play", isDaemon = true) {
            var firstWrite = true
            if (continuous) {
                while (!stopFlag.get()) {
                    var off = 0
                    while (off < wave.size && !stopFlag.get()) {
                        val n = minOf(chunk, wave.size - off)
                        track.write(wave, off, n)
                        if (firstWrite) {
                            firstWrite = false
                            val ns = System.nanoTime()
                            playStartNs.set(ns)
                            playStartNsForMeasurement = ns
                        }
                        off += n
                    }
                }
            } else {
                var off = 0
                while (off < wave.size && !stopFlag.get()) {
                    val n = minOf(chunk, wave.size - off)
                    track.write(wave, off, n)
                    if (firstWrite) {
                        firstWrite = false
                        val ns = System.nanoTime()
                        playStartNs.set(ns)
                        playStartNsForMeasurement = ns
                    }
                    off += n
                }
            }
        }

        recordThread = thread(name = "latency-measure-rec", isDaemon = true) {
            val buf = ShortArray(1024)
            while (!stopFlag.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val startNs = playStartNs.get()
                if (startNs == 0L) continue
                val floats = FloatArray(n) { j -> buf[j] / 32768f }
                onMicSamples(floats)
                if (!edgeReported) {
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
                    for (i in 0 until n) {
                        if (kotlin.math.abs(buf[i].toInt()) > 4915) {
                            edgeReported = true
                            onFirstEdgeMs(elapsedMs)
                            break
                        }
                    }
                }
                if (!continuous) {
                    val elapsed = (System.nanoTime() - startNs) / 1_000_000_000.0
                    if (elapsed >= durationSec) break
                }
            }
            onCompleted()
        }
    }

    fun stopAll() {
        stopFlag.set(true)
        try {
            recordThread?.interrupt()
        } catch (_: Exception) {
        }
        recordThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        lastPlaybackFloatsForAnalysis = FloatArray(0)
        playStartNsForMeasurement = 0L
    }

    private fun buildAudioTrack(bufferSize: Int): AudioTrack {
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(attr)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufferSize.coerceAtLeast(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }
    }

    private fun buildAudioRecord(bufferSize: Int): AudioRecord {
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize.coerceAtLeast(min * 2)
        )
    }
}
