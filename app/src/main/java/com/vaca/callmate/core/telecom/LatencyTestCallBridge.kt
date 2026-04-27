package com.vaca.callmate.core.telecom

/**
 * 与 iOS `LatencyTestCallProvider` 回调对齐；[LatencyTestConnection] 与 [LatencyTestViewModel] 通过此桥通信。
 */
object LatencyTestCallBridge {
    var onAnswered: (() -> Unit)? = null
    var onAudioActivated: (() -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onFailed: ((String) -> Unit)? = null

    fun clear() {
        onAnswered = null
        onAudioActivated = null
        onEnded = null
        onFailed = null
    }
}
