package com.vaca.callmate.core.telecom

import android.telecom.Call
import android.telecom.InCallService

/**
 * 与 iOS CallKit 通话中 UI 等价：系统通话状态变化时在此接收。
 * 当前为占位，后续可在此更新 CallSessionViewModel 并显示 LiveCallView。
 */
class CallMateInCallService : InCallService() {

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        // TODO: 通知 CallSessionViewModel，或通过 EventBus/Flow 通知 UI
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
    }
}
