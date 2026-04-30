package com.vaca.callmate.ui.screens.outbound

import androidx.compose.ui.graphics.Color
import com.vaca.callmate.data.outbound.OutboundTaskStatus
import com.vaca.callmate.ui.theme.AppError
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppWarning

/** 对标 iOS `OutboundTask.Status.color` */
fun OutboundTaskStatus.statusColor(): Color = when (this) {
    OutboundTaskStatus.Scheduled -> AppWarning
    OutboundTaskStatus.Pending -> AppPrimary.copy(alpha = 0.6f)
    OutboundTaskStatus.Running -> AppPrimary
    OutboundTaskStatus.NotConnected -> AppWarning.copy(alpha = 0.6f)
    OutboundTaskStatus.Completed -> AppSuccess
    OutboundTaskStatus.Partial -> AppWarning
    OutboundTaskStatus.Failed -> AppError
}
