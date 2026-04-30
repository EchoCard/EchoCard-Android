package com.vaca.callmate.features.calls

import java.util.concurrent.atomic.AtomicInteger

/**
 * 模拟通话全屏期间：云端 `type=filler`（口语里常被说成「filter」）仍可能命中**其它并行** WebSocket
 *（例如 AI 分身 [OutboundChatController]），但此时无 BLE 通话上下文，向 MCU 发 `play_filler` 会
 * ack=-2 等次生问题（对齐 iOS `WebSocketService` filler 分支注释：无 BLE 通话勿转 MCU）。
 *
 * [SimulationCallController] 自身不转发 filler；本开关堵住并行 WS 的误转发。
 */
object SimulationMcuFillerSuppressor {
    private val depth = AtomicInteger(0)

    /** [SimulationView] 进入组合时调用，与 [leave] 成对。 */
    fun enter() {
        depth.incrementAndGet()
    }

    fun leave() {
        depth.updateAndGet { d -> maxOf(0, d - 1) }
    }

    fun shouldSuppressPlayFillerToMcu(): Boolean = depth.get() > 0
}
