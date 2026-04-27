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
 * 与 iOS `handleBLECallStateOutgoingAnswered` 对齐：外呼在对方接听后建 `scene=call` Live WS。
 * 仅依赖 MCU 下发的 `outgoing_answered`（Android：HFP；iOS：ANCS）。
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
            "outbound live coordinator: subscribed call_state (outgoing_answered)"
        )

        callStateJob = scope.launch {
            bleManager.callStateEvents.collect { st ->
                val s = st.lowercase()
                if (s == "ended" || s == "rejected" || s == "phone_handled") {
                    outboundWs?.stop("call_state_$s")
                    outboundWs = null
                    return@collect
                }
                if (s != "outgoing_answered") return@collect

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
