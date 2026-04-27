package com.vaca.callmate.features.device.latency

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 与 iOS `ContinuousLatencyAnalyzer` 对齐：对 200–800 Hz 带内做 Goertzel 峰值，并输出波形缩略图。
 */
class ContinuousLatencyAnalyzer(private val sampleRate: Double = 16000.0) {

    private val fftSize = 2048
    private val ring = kotlin.collections.ArrayDeque<Float>(fftSize * 2)
    private val lock = Any()
    @Volatile
    private var stopped = false

    private val _currentFrequencyHz = MutableStateFlow<Double?>(null)
    val currentFrequencyHz: StateFlow<Double?> = _currentFrequencyHz.asStateFlow()

    private val _lastWaveformSamples = MutableStateFlow<FloatArray>(FloatArray(0))
    val lastWaveformSamples: StateFlow<FloatArray> = _lastWaveformSamples.asStateFlow()

    private val _lastSpectrumMagnitudes = MutableStateFlow<FloatArray>(FloatArray(0))
    val lastSpectrumMagnitudes: StateFlow<FloatArray> = _lastSpectrumMagnitudes.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default)

    fun reset() {
        synchronized(lock) {
            ring.clear()
            stopped = false
        }
        _currentFrequencyHz.value = null
        _lastWaveformSamples.value = FloatArray(0)
        _lastSpectrumMagnitudes.value = FloatArray(0)
    }

    fun stop() {
        stopped = true
    }

    fun push(samples: FloatArray) {
        if (stopped || samples.isEmpty()) return
        synchronized(lock) {
            for (s in samples) {
                if (ring.size >= fftSize * 2) ring.removeFirst()
                ring.addLast(s)
            }
            if (ring.size < fftSize) return
        }
        val copy = synchronized(lock) {
            ring.takeLast(fftSize).toFloatArray()
        }
        scope.launch {
            runAnalysis(copy)
        }
    }

    private fun runAnalysis(samples: FloatArray) {
        if (samples.size != fftSize) return
        // Hann
        val windowed = FloatArray(fftSize)
        for (i in 0 until fftSize) {
            val w = 0.5f * (1f - cos(2.0 * PI * i / (fftSize - 1)).toFloat())
            windowed[i] = samples[i] * w
        }
        var peakF = 200.0
        var peakMag = 0.0
        var f = 200.0
        while (f <= 800.0) {
            val m = goertzelMag(windowed, sampleRate, f)
            if (m > peakMag) {
                peakMag = m
                peakF = f
            }
            f += 25.0
        }
        _currentFrequencyHz.value = peakF

        val wave = samples.copyOf(256.coerceAtMost(samples.size))
        var peak = 1e-6f
        for (x in wave) peak = max(peak, abs(x))
        val norm = wave.map { (it / peak).coerceIn(-1f, 1f) }.toFloatArray()
        _lastWaveformSamples.value = norm

        val bins = 64
        val mags = FloatArray(bins)
        val fLo = 200.0
        val fHi = 800.0
        for (b in 0 until bins) {
            val ff = fLo + (fHi - fLo) * b / (bins - 1).coerceAtLeast(1)
            mags[b] = (goertzelMag(windowed, sampleRate, ff) / (32768.0 * 0.01)).toFloat().coerceIn(0f, 1f)
        }
        _lastSpectrumMagnitudes.value = mags
    }

    private fun goertzelMag(samples: FloatArray, sr: Double, freq: Double): Double {
        val n = samples.size
        val k = (0.5 + n * freq / sr).toInt().coerceIn(1, n - 1)
        val omega = 2.0 * PI * k / n
        val coeff = 2.0 * cos(omega)
        var sPrev = 0.0
        var sPrev2 = 0.0
        for (i in 0 until n) {
            val s = samples[i].toDouble() + coeff * sPrev - sPrev2
            sPrev2 = sPrev
            sPrev = s
        }
        val power = sPrev2 * sPrev2 + sPrev * sPrev - coeff * sPrev * sPrev2
        return sqrt(power.coerceAtLeast(0.0))
    }
}
