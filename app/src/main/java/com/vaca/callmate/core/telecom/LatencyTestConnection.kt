package com.vaca.callmate.core.telecom

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.util.Log

/**
 * 自管理「延迟测试」来电：与 iOS CallKit 自动接听对齐。
 * setActive 后请求蓝牙音频路由，监听 [onCallAudioStateChanged] 等待 BT 真正激活再通知上层。
 */
class LatencyTestConnection(
    @Suppress("UNUSED_PARAMETER") context: Context
) : Connection() {

    private companion object {
        const val TAG = "LatencyTestConn"
        const val BT_ROUTE_TIMEOUT_MS = 12000L
        const val BT_STABLE_DELAY_MS = 600L
    }

    @Volatile
    private var didAutoActivate = false
    @Volatile
    private var didNotifyAudioActivated = false
    @Volatile
    private var btRouteConfirmedCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        setConnectionCapabilities(
            CAPABILITY_SUPPORT_HOLD or CAPABILITY_HOLD
        )
        setAddress(Uri.parse("tel:latencytest"), TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName("Latency Test", TelecomManager.PRESENTATION_ALLOWED)
        setVideoState(0)
        setRinging()
        LatencyTestConnectionHolder.set(this)
        mainHandler.post {
            activateLatencyTestCallOnce()
        }
    }

    private fun activateLatencyTestCallOnce() {
        if (didAutoActivate) return
        didAutoActivate = true
        setActive()
        Log.i(TAG, "setActive done, requesting BT audio route")
        LatencyTestCallBridge.onAnswered?.invoke()
        requestBluetoothRoute()
        mainHandler.postDelayed(btRouteTimeoutRunnable, BT_ROUTE_TIMEOUT_MS)
    }

    private fun requestBluetoothRoute() {
        @Suppress("DEPRECATION")
        setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
        Log.i(TAG, "setAudioRoute(BLUETOOTH) requested")
    }

    @Deprecated("Deprecated in API 34+, but needed for older API levels")
    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(state: CallAudioState?) {
        super.onCallAudioStateChanged(state)
        if (state == null) return
        val isBt = (state.route and CallAudioState.ROUTE_BLUETOOTH) != 0
        val hasBtSupport = (state.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0
        Log.i(TAG, "onCallAudioStateChanged route=${state.route} bt=${state.activeBluetoothDevice} supported=${state.supportedRouteMask} isBt=$isBt hasBtSupport=$hasBtSupport")

        if (isBt) {
            btRouteConfirmedCount++
            if (!didNotifyAudioActivated) {
                mainHandler.removeCallbacks(btStableCheckRunnable)
                mainHandler.postDelayed(btStableCheckRunnable, BT_STABLE_DELAY_MS)
            }
        } else {
            mainHandler.removeCallbacks(btStableCheckRunnable)
            if (hasBtSupport) {
                Log.i(TAG, "BT supported but route is not BT — re-requesting")
                requestBluetoothRoute()
            }
        }
    }

    private val btStableCheckRunnable = Runnable {
        @Suppress("DEPRECATION")
        val currentState = callAudioState
        val isBt = currentState != null && (currentState.route and CallAudioState.ROUTE_BLUETOOTH) != 0
        if (isBt && !didNotifyAudioActivated) {
            Log.i(TAG, "BT audio route stable (confirmed $btRouteConfirmedCount times) — notifying audioActivated")
            didNotifyAudioActivated = true
            mainHandler.removeCallbacks(btRouteTimeoutRunnable)
            LatencyTestCallBridge.onAudioActivated?.invoke()
        } else if (!isBt) {
            Log.i(TAG, "BT route lost during stable check — will re-request on next callback")
        }
    }

    private val btRouteTimeoutRunnable = Runnable {
        if (!didNotifyAudioActivated) {
            Log.w(TAG, "BT route timeout (${BT_ROUTE_TIMEOUT_MS}ms) — firing audioActivated anyway")
            didNotifyAudioActivated = true
            LatencyTestCallBridge.onAudioActivated?.invoke()
        }
    }

    override fun onAnswer() {
        super.onAnswer()
        activateLatencyTestCallOnce()
    }

    override fun onDisconnect() {
        super.onDisconnect()
        mainHandler.removeCallbacks(btRouteTimeoutRunnable)
        mainHandler.removeCallbacks(btStableCheckRunnable)
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        LatencyTestCallBridge.onEnded?.invoke()
        LatencyTestConnectionHolder.set(null)
    }

    fun endFromApp() {
        if (state == STATE_DISCONNECTED) return
        mainHandler.removeCallbacks(btRouteTimeoutRunnable)
        mainHandler.removeCallbacks(btStableCheckRunnable)
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        LatencyTestConnectionHolder.set(null)
    }
}

object LatencyTestConnectionHolder {
    @Volatile
    var current: LatencyTestConnection? = null
        private set

    fun set(c: LatencyTestConnection?) {
        current = c
    }
}
