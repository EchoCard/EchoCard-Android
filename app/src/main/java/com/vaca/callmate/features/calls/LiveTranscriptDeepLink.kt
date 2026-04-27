package com.vaca.callmate.features.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通知点击后经 [com.vaca.callmate.MainActivity] 解析深链，请求 [com.vaca.callmate.ui.screens.CallsView] 拉回 Live 转写页。
 */
object LiveTranscriptDeepLink {

    private val _pendingOpen = MutableStateFlow(false)
    val pendingOpen: StateFlow<Boolean> = _pendingOpen.asStateFlow()

    fun markFromNotification() {
        _pendingOpen.value = true
    }

    fun consumed() {
        _pendingOpen.value = false
    }
}
