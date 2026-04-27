package com.vaca.callmate.features.device.latency

import com.vaca.callmate.core.audio.LibOpusDecoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 与 iOS `LatencyTestRunner` 中 `buildWaveformTracesIfNeeded` / `makeStageMeasurements` 等静态逻辑对齐。
 */
object LatencyWaveformAnalysis {

    private const val SAMPLE_RATE = 16000.0
    private const val SQUARE_WAVE_HZ = 500.0
    private const val FRAME_SIZE = 960 // 60 ms @ 16 kHz
    private const val MIN_PLAUSIBLE_PLAYBACK_TO_BLE_MS = 30.0

    fun buildCharts(
        playbackSamples: FloatArray,
        micSamples: FloatArray,
        loopbackPackets: List<LatencyLoopbackPacket>,
        playStartNs: Long?,
        totalLatencyMs: Double?
    ): Pair<List<LatencyStageMeasurement>, List<LatencyWaveformTrace>> {
        val samplesPerCycle = max(1, (SAMPLE_RATE / SQUARE_WAVE_HZ).toInt())
        val desiredCount = max(samplesPerCycle * 16, 512)

        val bleSamples = decodeLoopbackOpusPackets(loopbackPackets)
        val stageMeasurements = makeStageMeasurements(
            loopbackPackets = loopbackPackets,
            playStartNs = playStartNs,
            totalLatencyMs = totalLatencyMs
        )
        val playbackToBleMs = stageMeasurements.find { it.id == "playback_to_ble" }?.milliseconds
        val totalMs = stageMeasurements.find { it.id == "total" }?.milliseconds

        val traces = listOfNotNull(
            makeTimelineTrace(
                kind = LatencyWaveformKind.Playback,
                samples = playbackSamples,
                desiredCount = desiredCount,
                eventTimeMs = 0.0,
                sampleRate = SAMPLE_RATE
            ),
            makeTimelineTrace(
                kind = LatencyWaveformKind.BleLoopback,
                samples = bleSamples,
                desiredCount = desiredCount,
                eventTimeMs = playbackToBleMs,
                sampleRate = SAMPLE_RATE
            ),
            makeTimelineTrace(
                kind = LatencyWaveformKind.Microphone,
                samples = micSamples,
                desiredCount = desiredCount,
                eventTimeMs = totalMs,
                sampleRate = SAMPLE_RATE
            )
        )
        return stageMeasurements to traces
    }

    private fun decodeLoopbackOpusPackets(packets: List<LatencyLoopbackPacket>): FloatArray {
        if (packets.isEmpty()) return FloatArray(0)
        val decoder = try {
            LibOpusDecoder(16000, 1)
        } catch (_: Exception) {
            return FloatArray(0)
        }
        val maxSamples = 16000 * 8
        val out = ArrayList<Float>(min(maxSamples, packets.size * FRAME_SIZE))
        val pcm = ShortArray(FRAME_SIZE)
        for (packet in packets) {
            if (packet.payload.isEmpty()) continue
            try {
                val n = decoder.decode(packet.payload, 0, packet.payload.size, pcm, 0, FRAME_SIZE, false)
                if (n > 0) {
                    for (i in 0 until n) {
                        if (out.size >= maxSamples) break
                        out.add(pcm[i] / 32768f)
                    }
                }
            } catch (_: Exception) {
                continue
            }
            if (out.size >= maxSamples) break
        }
        decoder.release()
        return out.toFloatArray()
    }

    private fun makeStageMeasurements(
        loopbackPackets: List<LatencyLoopbackPacket>,
        playStartNs: Long?,
        totalLatencyMs: Double?
    ): List<LatencyStageMeasurement> {
        val playbackToBleMs = estimatePlaybackToBleMs(loopbackPackets, playStartNs)
        val bleToRecordingMs = totalLatencyMs?.let { total ->
            playbackToBleMs?.let { p -> max(0.0, total - p) }
        }
        return listOf(
            LatencyStageMeasurement("playback_to_ble", playbackToBleMs),
            LatencyStageMeasurement("ble_to_recording", bleToRecordingMs),
            LatencyStageMeasurement("total", totalLatencyMs)
        )
    }

    private fun estimatePlaybackToBleMs(
        loopbackPackets: List<LatencyLoopbackPacket>,
        playStartNs: Long?
    ): Double? {
        if (playStartNs == null) return null
        val decoder = try {
            LibOpusDecoder(16000, 1)
        } catch (_: Exception) {
            return null
        }
        try {
            val pcm = ShortArray(FRAME_SIZE)
            for (packet in loopbackPackets) {
                if (packet.receivedAtNs < playStartNs) continue
                if (packet.payload.isEmpty()) continue
                try {
                    val n = decoder.decode(packet.payload, 0, packet.payload.size, pcm, 0, FRAME_SIZE, false)
                    if (n <= 0) continue
                    val packetSamples = FloatArray(n) { i -> pcm[i] / 32768f }
                    val edgeIndex = firstSquareWaveEdgeIndex(packetSamples) ?: continue
                    val sr = 16000.0
                    val decodedDurationMs = packetSamples.size * 1000.0 / sr
                    val edgeOffsetMs = edgeIndex * 1000.0 / sr
                    val deltaNs = ((decodedDurationMs - edgeOffsetMs) * 1_000_000).toLong()
                    val estimatedEventNs = packet.receivedAtNs - deltaNs
                    val estimatedLatencyMs = max(0.0, (estimatedEventNs - playStartNs) / 1_000_000.0)
                    if (estimatedLatencyMs >= MIN_PLAUSIBLE_PLAYBACK_TO_BLE_MS) {
                        return estimatedLatencyMs
                    }
                } catch (_: Exception) {
                    continue
                }
            }
            return null
        } finally {
            decoder.release()
        }
    }

    private fun firstSquareWaveEdgeIndex(samples: FloatArray): Int? {
        if (samples.size < 40) return null
        val amplitudeThreshold = 0.12f
        val window = 8
        val halfCycleMin = 8
        val halfCycleMax = 24

        fun mean(from: Int, length: Int): Float {
            val end = min(samples.size, from + length)
            if (from >= end) return 0f
            var sum = 0f
            for (i in from until end) sum += samples[i]
            return sum / (end - from)
        }

        for (i in 0 until (samples.size - window * 2)) {
            val left = mean(i, window)
            val right = mean(i + window, window)
            if (abs(left) <= amplitudeThreshold || abs(right) <= amplitudeThreshold || left * right >= 0) continue

            val searchStart = i + halfCycleMin
            val searchEnd = min(samples.size - window * 2, i + halfCycleMax)
            for (j in searchStart until searchEnd) {
                val nextLeft = mean(j, window)
                val nextRight = mean(j + window, window)
                if (abs(nextLeft) > amplitudeThreshold && abs(nextRight) > amplitudeThreshold && nextLeft * nextRight < 0) {
                    return i + window
                }
            }
        }
        return null
    }

    private fun makeTimelineTrace(
        kind: LatencyWaveformKind,
        samples: FloatArray,
        desiredCount: Int,
        eventTimeMs: Double?,
        sampleRate: Double
    ): LatencyWaveformTrace? {
        if (samples.isEmpty()) return null
        val index = firstInterestingIndex(samples) ?: 0
        val leading = min(index, desiredCount / 6)
        val start = max(0, index - leading)
        val end = min(samples.size, start + desiredCount)
        val slice = samples.copyOfRange(start, end)
        val startTimeMs: Double = if (eventTimeMs != null) {
            max(0.0, eventTimeMs - (index - start) * 1000 / sampleRate)
        } else {
            start * 1000 / sampleRate
        }
        return LatencyWaveformTrace(
            kind = kind,
            samples = normalizeForDisplay(slice),
            startTimeMs = startTimeMs,
            sampleRate = sampleRate,
            eventTimeMs = eventTimeMs
        )
    }

    private fun firstInterestingIndex(samples: FloatArray): Int? {
        for (i in samples.indices) {
            if (abs(samples[i]) > 0.08f) return i
        }
        var bestIndex: Int? = null
        var bestMag = 0f
        for (i in samples.indices) {
            val mag = abs(samples[i])
            if (mag > bestMag) {
                bestMag = mag
                bestIndex = i
            }
        }
        return bestIndex
    }

    private fun normalizeForDisplay(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        var peak = 1e-6f
        for (x in samples) peak = max(peak, abs(x))
        if (peak <= 0.0001f) return samples
        return FloatArray(samples.size) { i -> samples[i] / peak }
    }
}
