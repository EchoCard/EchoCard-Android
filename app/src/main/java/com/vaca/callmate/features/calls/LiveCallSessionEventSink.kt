package com.vaca.callmate.features.calls

/**
 * Live 来电 WebSocket 与会话状态的桥接：[LiveCallIncomingWebSocket] 只通过 [LiveCallSessionRegistry]
 * 投递事件，不持有 [CallSessionViewModel]，与 UI/Compose 生命周期解耦。
 */
interface LiveCallSessionEventSink {
    fun onLiveWsHelloAcked(serverSessionId: String?)

    fun onLiveWsDisconnected()

    /**
     * WS 链路终止（onClosing/onClosed/onFailure）；内含 Connecting 竞态下先补 Connected 再收尾。
     */
    fun onLiveWsTerminated(hadHelloAck: Boolean, reason: String)

    fun onLiveSttText(text: String)

    fun scheduleRemoteAiHangup()

    fun onLiveTtsStart()

    fun onLiveTtsSentenceStart(text: String)

    fun onLiveTtsStop()
}

object LiveCallSessionRegistry {

    @Volatile
    private var sink: LiveCallSessionEventSink? = null

    fun register(s: LiveCallSessionEventSink) {
        sink = s
    }

    fun unregister(s: LiveCallSessionEventSink) {
        if (sink === s) sink = null
    }

    fun currentSink(): LiveCallSessionEventSink? = sink
}
