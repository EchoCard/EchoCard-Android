package com.vaca.callmate.data.outbound

import com.vaca.callmate.data.CallRecord

/**
 * 与 iOS `OutboundCallsView.outboundCalls(for:)` 对齐：优先 `outboundTaskId`，否则按时间窗 + 话术匹配。
 */
object OutboundCallMatching {
    fun callsForTask(
        task: OutboundTask,
        allOutboundCalls: List<CallRecord>,
        allTasksSortedByCreatedDesc: List<OutboundTask>
    ): List<CallRecord> {
        val tid = task.id.toString()
        val exact = allOutboundCalls
            .filter { it.outboundTaskId == tid }
            .sortedByDescending { it.startedAtMillis }
        if (exact.isNotEmpty()) return exact

        val nextCreatedAt = allTasksSortedByCreatedDesc
            .filter { it.createdAt > task.createdAt }
            .minOfOrNull { it.createdAt }
        val window = allOutboundCalls.filter { call ->
            call.outboundTaskId == null &&
                call.startedAtMillis >= task.createdAt &&
                (nextCreatedAt == null || call.startedAtMillis < nextCreatedAt)
        }
        return window.filter { call ->
            val s = call.summary
            val fs = call.fullSummary
            s.contains(task.promptType) || fs.contains(task.promptRule) || s.contains(task.id.toString())
        }.sortedByDescending { it.startedAtMillis }
    }
}
