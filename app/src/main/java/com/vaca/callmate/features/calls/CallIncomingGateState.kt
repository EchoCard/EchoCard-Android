package com.vaca.callmate.features.calls

import java.util.Collections

/**
 * 与 iOS `CallSessionController` + `CallTransportCoordinator.planBLEIncomingCallGate` 对齐的进程内状态。
 * [activeMode] 由 [com.vaca.callmate.data.AppPreferences] 与 UI 同步。
 */
object CallIncomingGateState {

    /** `standby` | `semi` | `full` 等与 iOS `activeModeKey` 一致 */
    @Volatile
    var activeMode: String = "semi"

    @Volatile
    var contactPassthroughActive: Boolean = false

    val ignoredContactUids: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

    fun resetAfterCallTerminated() {
        contactPassthroughActive = false
        ignoredContactUids.clear()
    }
}
