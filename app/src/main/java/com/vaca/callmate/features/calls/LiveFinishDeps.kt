package com.vaca.callmate.features.calls

import android.content.Context
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.repository.CallRepository
import com.vaca.callmate.data.Language

/** 由 [LiveCallView] 注入，供 [CallSessionViewModel] 在挂断/持久化时访问 BLE、仓库等。 */
data class LiveFinishDeps(
    val appContext: Context,
    val bleManager: BleManager,
    val preferences: AppPreferences,
    val callRepository: CallRepository?,
    val language: Language,
)
