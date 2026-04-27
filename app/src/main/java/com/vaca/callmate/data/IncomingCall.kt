package com.vaca.callmate.data

/**
 * 与 iOS CallMateIncomingCall 对齐：来电/外呼任务信息
 */
data class IncomingCall(
    val callId: String,
    val caller: String,
    val number: String,
    val title: String = "",
    val statusText: String = "",
    val canHandoff: Boolean = true,
    val canHangup: Boolean = true,
    /** BLE `incoming_call.uid` */
    val bleUid: Int = 0,
    /** BLE `incoming_call.sid`（会话绑定，与 MCU 一致） */
    val bleSid: Long? = null,
    val isContact: Boolean = true
)
