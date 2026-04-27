package com.vaca.callmate.features.calls

import android.content.Context
import android.util.Log
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.INCOMING_AI_CHAIN_TAG
import com.vaca.callmate.core.network.NetworkStatus
import com.vaca.callmate.data.AbnormalCallRecordStore
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.IncomingCall
import com.vaca.callmate.core.ble.sendIgnoreIncomingCall
import com.vaca.callmate.core.ble.answerCall
import com.vaca.callmate.core.notifications.LiveTranscriptNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 与 iOS `CallSessionController.startFromIncomingCall` + `scheduleAutoAnswerForIncomingCall` 对齐：
 * - 网络可用时建立 `scene=call` WebSocket（[LiveCallIncomingWebSocket]）
 * - 延迟 [AppPreferences.pickupDelayFlow] 秒后检查网络再 `answer`；失败则记 `network_unavailable` 并发 `ignore` + 关闭 Live UI
 * - `call_state` 结束类事件时取消未完成的接听任务并停止 WS
 */
object IncomingCallSessionCoordinator {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Main.immediate)

    @Volatile
    private var started = false

    private lateinit var sessionViewModel: CallSessionViewModel

    private var incomingCollectJob: Job? = null
    private var callStateCollectJob: Job? = null
    private var pickupJob: Job? = null
    private var ws: LiveCallIncomingWebSocket? = null

    @Volatile
    private var pendingAnswerUid: Int? = null

    /**
     * 与 iOS `aiAnswerRequested` 对齐（commit 883ede8d）：协调器是否已向 MCU 下发 `answer`。
     * 仅在自动应答发出前翻 true 时，陌生号 smart 模式里 MCU/ANCS 漏判也能靠手机系统 OFFHOOK
     * 兜底。发给 MCU 后的 manual pickup 不再触发 early-handoff 路径（那时 AI 已接管）。
     */
    @Volatile
    private var aiAnswerSent: Boolean = false

    /**
     * 与 iOS `phoneHandledCall` 对齐：用户在 pickup delay 窗口内抢接导致我们根本没发
     * `answer`；后续 AI WS 收到 `✿END✿`/断连时不要向 MCU 下发 `hangup`，否则 MCU 会
     * `AT+CHUP` 把用户正在说的通话挂断（详见 iOS 883ede8d 的 bug 描述）。
     */
    @Volatile
    private var userHandledEarly: Boolean = false

    /** 用于通话结束时清理 [LiveCallWsSessionHolder] */
    @Volatile
    private var lastIncomingUidForSession: Int = 0

    /**
     * SID-based dedup: PBAP may emit `caller_resolved` multiple times for the same call;
     * each physical call has a unique MCU SID, so comparing SIDs is collision-free
     * (replaces the old composite `uid|sid|number` key that could collide on back-to-back
     * calls from the same number).
     */
    @Volatile
    private var lastHandledSid: Long? = null

    /**
     * 与 iOS `end()` 中 `ws.sendAbort` + `ws.disconnect` 对齐：挂断 AI 会话前先通知服务端。
     */
    fun abortAndStopLiveWebSocket(reason: String) {
        val s = ws
        if (s != null) {
            s.sendAbort(reason)
            s.stop(reason)
        }
        ws = null
    }

    fun start(
        appContext: Context,
        bleManager: BleManager,
        preferences: AppPreferences,
        sessionViewModel: CallSessionViewModel
    ) {
        if (started) return
        started = true
        this.sessionViewModel = sessionViewModel
        val ctx = appContext.applicationContext
        Log.i(INCOMING_AI_CHAIN_TAG, "coordinator start: subscribed incomingCall + callState")

        incomingCollectJob = scope.launch {
            bleManager.incomingCallEvents.collect { call ->
                scope.launch {
                    handleIncoming(ctx, bleManager, preferences, call)
                }
            }
        }

        callStateCollectJob = scope.launch {
            bleManager.callStateEvents.collect { st ->
                val s = st.lowercase()
                if (s == "ended" || s == "rejected" || s == "phone_handled") {
                    Log.i(
                        INCOMING_AI_CHAIN_TAG,
                        "call_state terminal=$s lastUid=$lastIncomingUidForSession cancel pickup+ws"
                    )
                    val u = lastIncomingUidForSession
                    sessionViewModel.incomingCall.value?.let { LiveCallWsSessionHolder.clear(it) }
                    if (u != 0) {
                        LiveTranscriptNotificationHelper.clearForUid(ctx, u)
                    }
                    pickupJob?.cancel()
                    pickupJob = null
                    pendingAnswerUid = null
                    aiAnswerSent = false
                    userHandledEarly = false
                    lastHandledSid = null
                    ws?.stop("call_state_$s")
                    ws = null
                }
            }
        }
    }

    /** 给 [CallSessionViewModel.completeLiveCallInternal] 判断是否抑制 BLE hangup。 */
    fun shouldSuppressBleHangup(): Boolean = userHandledEarly

    /**
     * 系统电话进入 OFFHOOK 时，由 [com.vaca.callmate.core.telephony.SemiModeHfpTelephonyBridge]
     * 转派过来。与 iOS `CallSessionController+SystemCallObserver.swift`（commit 883ede8d）
     * 同一意图：智能模式 + 陌生号 + pickup_delay 窗口内用户抢接时，
     * MCU/ANCS 可能漏判，导致 `phoneHandledCall` 一直是 false，AI 会话结束时下发 `hangup`
     * 把用户正在说的通话挂掉。这里用手机系统的 OFFHOOK 做兜底：
     *
     * - 仅在"协调器尚未发出 AI answer"时翻 [userHandledEarly]（iOS `!aiAnswerRequested`）。
     * - 翻位后取消 pickupJob、打断 Live WS，后续 AI hangup 被 [shouldSuppressBleHangup] 抑制。
     *
     * AI 已经 answer 之后的 OFFHOOK 是正常流程（MCU 通过 HFP 接听并回传），不走这条路径。
     */
    fun notifyTelephonyOffhook() {
        val pending = pendingAnswerUid
        if (pending == null) return
        if (aiAnswerSent || userHandledEarly) return
        Log.i(
            INCOMING_AI_CHAIN_TAG,
            "telephony OFFHOOK during pickup window → userHandledEarly uid=$pending (bypass AI answer)"
        )
        userHandledEarly = true
        pendingAnswerUid = null
        pickupJob?.cancel()
        pickupJob = null
        val s = ws
        if (s != null) {
            try {
                s.sendAbort("user_pickup_early")
            } catch (_: Exception) {
            }
            s.stop("user_pickup_early")
        }
        ws = null
    }

    /**
     * 纠偏：先按非联系人进了 [handleIncoming]（Live 可能已起），随后 PBAP/门禁判定为联系人时调用。
     * iOS 侧等价：不会在 `allowContactPassthrough` 之后进入 `prepareIncomingCall`，本不应出现 Live；
     * 仅「号码先到、名字后到」场景需要 dismiss（见 [BleControlDispatch.handleCallerResolved]）。
     */
    fun cancelPendingAiTakeover(appContext: Context, bleManager: BleManager, reason: String) {
        val uid = lastIncomingUidForSession
        Log.i(INCOMING_AI_CHAIN_TAG, "cancelPendingAiTakeover reason=$reason lastUid=$uid")
        pickupJob?.cancel()
        pickupJob = null
        pendingAnswerUid = null
        lastHandledSid = null
        ws?.stop(reason)
        ws = null
        sessionViewModel.incomingCall.value?.let { LiveCallWsSessionHolder.clear(it) }
        if (uid != 0) {
            LiveTranscriptNotificationHelper.clearForUid(appContext, uid)
        }
        bleManager.requestDismissIncomingCallUi()
    }

    private suspend fun handleIncoming(
        ctx: Context,
        bleManager: BleManager,
        preferences: AppPreferences,
        call: IncomingCall
    ) {
        val prevUid = lastIncomingUidForSession
        Log.i(
            INCOMING_AI_CHAIN_TAG,
            "handleIncoming uid=${call.bleUid} sid=${call.bleSid} isContact=${call.isContact} number=${call.number} supersedePrev=$prevUid"
        )

        /*
         * iOS: `CallSessionController.handleBLEIncomingCall` 在 `allowContactPassthrough` 即 return，
         * 不会 `prepareIncomingCall` / `liveCallRequest`，Live 本不弹。
         * Android 应在 [com.vaca.callmate.core.ble.BleControlDispatch.handleIncomingCall] 门禁拦截并勿 emit。
         * 此处仅防御异常路径（理论上不应收到 isContact=true 的 emitIncomingCall）。
         */
        if (call.isContact) {
            Log.w(
                INCOMING_AI_CHAIN_TAG,
                "contact on coordinator path (should be gated) uid=${call.bleUid} — skip WS/answer"
            )
            return
        }

        val newSid = call.bleSid
        if (newSid == null) {
            Log.w(INCOMING_AI_CHAIN_TAG, "handleIncoming skip (no SID on call) uid=${call.bleUid}")
            return
        }
        if (newSid == lastHandledSid) {
            Log.i(
                INCOMING_AI_CHAIN_TAG,
                "handleIncoming skip duplicate (same SID=$newSid as current session)"
            )
            return
        }
        lastHandledSid = newSid

        sessionViewModel.onIncomingCallFromCoordinator(call)

        pickupJob?.cancel()
        ws?.stop("superseded_by_new_incoming")
        ws = null

        if (prevUid != 0 && prevUid != call.bleUid) {
            LiveTranscriptNotificationHelper.clearForUid(ctx, prevUid)
        }

        pendingAnswerUid = call.bleUid
        lastIncomingUidForSession = call.bleUid
        aiAnswerSent = false
        userHandledEarly = false
        bleManager.ensureConnectionRecovered("incoming_call_session")

        val lang = preferences.languageFlow.first()
        if (NetworkStatus.isValidated(ctx)) {
            Log.i(INCOMING_AI_CHAIN_TAG, "live ws: connectForIncomingCall uid=${call.bleUid}")
            val session = LiveCallIncomingWebSocket(ctx, bleManager, preferences, lang, scope)
            ws = session
            session.connectForIncomingCall(call)
        } else {
            Log.i(
                INCOMING_AI_CHAIN_TAG,
                "live ws: skipped (network not validated at connect time) uid=${call.bleUid}"
            )
        }

        val delaySec = preferences.pickupDelayFlow.first().coerceIn(0, 60)
        Log.i(INCOMING_AI_CHAIN_TAG, "pickup scheduled delaySec=$delaySec uid=${call.bleUid}")
        pickupJob = scope.launch {
            if (delaySec > 0) delay(delaySec * 1000L)
            if (pendingAnswerUid != call.bleUid) {
                Log.i(
                    INCOMING_AI_CHAIN_TAG,
                    "pickup cancelled (superseded) expected=${call.bleUid} pending=$pendingAnswerUid"
                )
                return@launch
            }
            if (!NetworkStatus.isValidated(ctx)) {
                Log.i(INCOMING_AI_CHAIN_TAG, "auto-answer skipped: network unavailable uid=${call.bleUid}")
                AbnormalCallRecordStore.getInstance(ctx).append("network_unavailable")
                bleManager.sendIgnoreIncomingCall(call.bleUid, call.bleSid)
                bleManager.requestDismissIncomingCallUi()
                pendingAnswerUid = null
                ws?.stop("network_unavailable_after_delay")
                ws = null
                return@launch
            }
            Log.i(INCOMING_AI_CHAIN_TAG, "auto-answer send answer uid=${call.bleUid} sid=${call.bleSid}")
            aiAnswerSent = true
            bleManager.answerCall(call.bleUid, call.bleSid)
            LiveTranscriptNotificationHelper.showAiAnswered(
                ctx,
                call.bleUid,
                lang,
                LiveCallWsSessionHolder.get(call)
            )
            pendingAnswerUid = null
        }
    }
}
