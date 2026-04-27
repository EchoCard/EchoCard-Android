package com.vaca.callmate.core.telephony

/**
 * 聚合后的**唯一**通话抽象，供 [CallStateAggregator] 输出、再经协调下发 MCU。
 * 与 `docs/CALL_STATE_ARCHITECTURE.md` 中的模型一致。
 */
sealed class UnifiedCallState {
    data object Idle : UnifiedCallState()

    data class Ringing(
        val number: String?,
        val source: CallSignalSource
    ) : UnifiedCallState()

    data object Offhook : UnifiedCallState()

    data object Disconnected : UnifiedCallState()
}

/**
 * 聚合器输入源（用于 Ringing.source 与优先级策略）。
 */
enum class CallSignalSource {
    Hfp,
    Telecom,
    NotificationMeta
}
