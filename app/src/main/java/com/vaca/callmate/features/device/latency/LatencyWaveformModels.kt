package com.vaca.callmate.features.device.latency

/**
 * 与 iOS `LatencyWaveformKind` / `LatencyWaveformTrace` / `LatencyStageMeasurement` 对齐。
 */
enum class LatencyWaveformKind {
    Playback,
    BleLoopback,
    Microphone
}

data class LatencyWaveformTrace(
    val kind: LatencyWaveformKind,
    val samples: FloatArray,
    val startTimeMs: Double,
    val sampleRate: Double,
    val eventTimeMs: Double?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LatencyWaveformTrace
        return kind == other.kind &&
            samples.contentEquals(other.samples) &&
            startTimeMs == other.startTimeMs &&
            sampleRate == other.sampleRate &&
            eventTimeMs == other.eventTimeMs
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + samples.contentHashCode()
        result = 31 * result + startTimeMs.hashCode()
        result = 31 * result + sampleRate.hashCode()
        result = 31 * result + (eventTimeMs?.hashCode() ?: 0)
        return result
    }
}

data class LatencyStageMeasurement(
    val id: String,
    val milliseconds: Double?
)

data class LatencyLoopbackPacket(
    val payload: ByteArray,
    val receivedAtNs: Long
)
