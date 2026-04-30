package com.vaca.callmate.features.calls

import android.content.Context
import android.util.Log
import com.vaca.callmate.core.ble.INCOMING_AI_CHAIN_TAG
import com.vaca.callmate.core.network.NetworkStatus
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.core.ble.BleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val OUTBOUND_TITLE = "[OUTBOUND_TASK]"

/**
 * 与 iOS `handleBLECallStateOutgoingAnswered` 对齐：外呼在对方接听后建 `scene=call_outbound` Live WS
 *（[LiveCallIncomingWebSocket.sendHello]）。
 *
 * Android 上 AI 分身 / outbound_chat 使用独立的 [OutboundChatController] WebSocket，**不会**像 iOS 那样
 * 与 BLE Live 共用单例 socket，因此一般不需要在拨号前 `disconnect` 配置会话。
 *
 * 仍可能与 iOS 同类的是：部分机型/MCU 漏发 `outgoing_answered`，故在 `audio_streaming` 且尚无 Live WS
 * 时做一次后备建链（对标 iOS `call_state_audio_streaming_fallback`）。
 */
object OutboundLiveSessionCoordinator {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Main.immediate)

    @Volatile
    private var started = false

    private var outboundWs: LiveCallIncomingWebSocket? = null
    private var callStateJob: Job? = null

    fun abortAndStopLiveWebSocket(reason: String) {
        val s = outboundWs
        if (s != null) {
            s.sendAbort(reason)
            s.stop(reason)
        }
        outboundWs = null
    }

    fun start(
        appContext: Context,
        bleManager: BleManager,
        preferences: AppPreferences,
        sessionViewModel: CallSessionViewModel,
    ) {
        if (started) return
        started = true
        val ctx = appContext.applicationContext
        Log.i(
            INCOMING_AI_CHAIN_TAG,
            "outbound live coordinator: subscribed call_state (outgoing_answered + audio_streaming fallback)"
        )

        callStateJob = scope.launch {
            bleManager.callStateEvents.collect { st ->
                val s = st.lowercase()
                if (s == "ended" || s == "rejected" || s == "phone_handled") {
                    outboundWs?.stop("call_state_$s")
                    outboundWs = null
                    return@collect
                }
                val shouldConnectLiveWs = when {
                    s == "outgoing_answered" -> true
                    s == "audio_streaming" ->
                        bleManager.hasAppManagedOutboundDialPending() && outboundWs == null
                    else -> false
                }
                if (!shouldConnectLiveWs) return@collect
                if (s == "audio_streaming") {
                    Log.i(
                        INCOMING_AI_CHAIN_TAG,
                        "outbound live ws: audio_streaming fallback (outgoing_answered missing or live WS not started)"
                    )
                }

                var ic = sessionViewModel.incomingCall.value
                if (ic == null) {
                    val syn = bleManager.syntheticOutboundIncomingCallOrNull()
                    if (syn != null) {
                        sessionViewModel.startFromIncomingCall(syn)
                        ic = syn
                    }
                }
                if (ic == null || ic.title != OUTBOUND_TITLE) {
                    Log.i(
                        INCOMING_AI_CHAIN_TAG,
                        "outbound live ws: skip trigger=$s title=${ic?.title} (no outbound session)"
                    )
                    return@collect
                }
                val prompt = bleManager.outboundPromptRuleForLive()
                if (prompt.isNullOrEmpty()) {
                    Log.w(INCOMING_AI_CHAIN_TAG, "outbound live ws: skip (prompt empty) trigger=$s")
                    return@collect
                }
                if (!NetworkStatus.isValidated(ctx)) {
                    Log.w(INCOMING_AI_CHAIN_TAG, "outbound live ws: skip (network not validated) trigger=$s")
                    return@collect
                }

                outboundWs?.stop("superseded_outbound_ws")
                outboundWs = null

                val lang = preferences.languageFlow.first()
                val session = LiveCallIncomingWebSocket(ctx, bleManager, preferences, lang, scope)
                outboundWs = session
                Log.i(
                    INCOMING_AI_CHAIN_TAG,
                    "outbound live ws: connectForOutboundAnswered uid=${ic.bleUid} trigger=$s"
                )
                session.connectForOutboundAnswered(ic, prompt)
            }
        }
    }
}
