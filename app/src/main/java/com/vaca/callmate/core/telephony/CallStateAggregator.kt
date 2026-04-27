package com.vaca.callmate.core.telephony

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **Call State Aggregator**（调用状态聚合器）
 *
 * - **真相**在系统侧（HFP / Telecom / Notification）；本类负责 **合并、去重、防抖**，
 *   保证对下游（BLE → MCU）**不产生双事件**。
 * - 具体接入：HFP 指标、Telecom/InCall、`NotificationListener` 元数据等，在实现类中订阅并调用 [submit]。
 *
 * 设计文档：[docs/CALL_STATE_ARCHITECTURE.md](/docs/CALL_STATE_ARCHITECTURE.md)
 */
interface CallStateAggregator {

    /** 当前聚合后的状态；UI 与「是否允许往 MCU 发信令」应只依赖此流 */
    val state: StateFlow<UnifiedCallState>

    /**
     * 各源上报原始观测（实现内做优先级与单会话去重）。
     * @param sessionId App 侧统一会话 id（与 MCU `sid` 对齐策略见协议文档）
     */
    fun submit(source: CallSignalSource, raw: AggregatorRawObservation)

    /** BLE 重连或 App 冷启动后，用系统快照对齐（state-based 同步） */
    fun reconcileWithSystemSnapshot(snapshot: UnifiedCallState)
}

/**
 * 原始观测（实现类映射自 HFP/Telecom/Notification）。
 */
data class AggregatorRawObservation(
    val ringing: Boolean? = null,
    val offhook: Boolean? = null,
    val disconnected: Boolean? = null,
    val number: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * 占位实现：未接入真实 HFP/Telecom 前保持 [UnifiedCallState.Idle]。
 * 接入后替换为完整实现或删除此类仅保留接口。
 */
class NoOpCallStateAggregator : CallStateAggregator {

    private val _state = MutableStateFlow<UnifiedCallState>(UnifiedCallState.Idle)

    override val state: StateFlow<UnifiedCallState> = _state.asStateFlow()

    override fun submit(source: CallSignalSource, raw: AggregatorRawObservation) {
        //  intentionally empty — wire HFP/Telecom/Notification here
    }

    override fun reconcileWithSystemSnapshot(snapshot: UnifiedCallState) {
        _state.value = snapshot
    }
}
