package com.vaca.callmate.features.calls

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 与 [CallSessionViewModel] 状态及控制通道 [com.vaca.callmate.features.outbound.ControlChannelService] 同步
 * （当前控制通道为推送，[foregroundBoostActive] 恒为 false）：
 * 在通话态（[CallSessionStatus.Ringing] / [Connecting] / [Connected]）**或** 控制 WS 需要保持链路（已连接 / 建连中 / 断线重连中）时
 * 启动 [CallForegroundService]，提升进程优先级，减轻 BLE、Live WS、**控制 WS** 在后台被限流或杀进程的概率。
 */
object CallForegroundController {

    private const val TAG = "CallForegroundCtl"

    fun sync(
        context: Context,
        status: CallSessionStatus,
        controlChannelForegroundBoost: Boolean = false
    ) {
        val app = context.applicationContext
        val callActive = when (status) {
            CallSessionStatus.Ringing,
            CallSessionStatus.Connecting,
            CallSessionStatus.Connected -> true
            CallSessionStatus.Idle,
            CallSessionStatus.Ended -> false
        }
        val active = callActive || controlChannelForegroundBoost
        if (active) {
            val intent = CallForegroundService.createStartIntent(app)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    @Suppress("DEPRECATION")
                    app.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService: ${e.message}")
            }
        } else {
            stop(app)
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        try {
            app.startService(
                Intent(app, CallForegroundService::class.java).apply {
                    action = CallForegroundService.ACTION_STOP
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "stop: ${e.message}")
        }
    }
}
