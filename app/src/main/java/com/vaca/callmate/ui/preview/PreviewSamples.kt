package com.vaca.callmate.ui.preview

import com.vaca.callmate.data.CallRecord
import com.vaca.callmate.data.CallStatus
import com.vaca.callmate.data.ChatMessage
import com.vaca.callmate.data.IncomingCall
import com.vaca.callmate.data.MessageSender
import com.vaca.callmate.data.outbound.OutboundContact
import com.vaca.callmate.data.outbound.OutboundTask
import com.vaca.callmate.data.outbound.OutboundTaskStatus
import java.util.UUID

/** 供 @Preview 使用的靜態範例資料（不觸發網路 / Room 寫入）。 */
object PreviewSamples {
    val callRecord: CallRecord
        get() = CallRecord(
            id = 1,
            roomLogId = "preview-room",
            phone = "13800138000",
            label = "快递",
            time = "2025-03-27 10:00",
            status = CallStatus.Handled,
            summary = "已签收",
            fullSummary = "已签收",
            transcript = listOf(
                ChatMessage(1L, MessageSender.Caller, "你好"),
                ChatMessage(2L, MessageSender.Ai, "您好，已安排")
            ),
            duration = 120
        )

    val incomingCall: IncomingCall
        get() = IncomingCall(
            callId = "preview",
            caller = "测试",
            number = "13800138000",
            title = "",
            statusText = "接通"
        )

    val outboundTask: OutboundTask
        get() = OutboundTask(
            id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            promptType = "默认",
            promptRule = "请礼貌问候",
            contacts = listOf(
                OutboundContact(phone = "13800138000", name = "张三")
            ),
            scheduledAt = null,
            status = OutboundTaskStatus.Running,
            dialSuccessCount = 1,
            dialFailureCount = 0,
            callFrequency = 30,
            redialMissed = false,
            createdAt = System.currentTimeMillis()
        )
}
