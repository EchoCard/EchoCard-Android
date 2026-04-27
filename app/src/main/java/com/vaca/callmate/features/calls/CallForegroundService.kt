package com.vaca.callmate.features.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vaca.callmate.MainActivity
import com.vaca.callmate.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通话态（振铃 / 接通中 / 已接通）前台服务，类型 [connectedDevice]，减轻后台 CPU/网络调度导致的 BLE→云端音频卡顿。
 * 启动/停止仅由 [CallForegroundController] 与 [CallSessionViewModel.status] 同步，不随 Compose 页面切换。
 * 与 [com.vaca.callmate.CallMateApplication.bleManager] 同属进程级通话基础设施。
 * [CallForegroundController] 在控制 WebSocket 建连/重连时也会保持本服务，以抬高控制通道所在进程优先级。
 */
class CallForegroundService : Service() {

    /** Must call [startForeground] after [startForegroundService]; if STOP is handled first (rapid sync), still satisfy the OS before [stopForeground]. */
    private val foregroundStarted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                try {
                    ensureForegroundStarted()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                ensureForegroundStarted()
            }
        }
        return START_STICKY
    }

    private fun ensureForegroundStarted() {
        if (!foregroundStarted.compareAndSet(false, true)) return
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.call_foreground_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.call_foreground_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("callmate://livecall/open")
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.call_foreground_notification_title))
            .setContentText(getString(R.string.call_foreground_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(pending)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    companion object {
        const val ACTION_START = "com.vaca.callmate.action.CALL_FG_START"
        const val ACTION_STOP = "com.vaca.callmate.action.CALL_FG_STOP"
        private const val CHANNEL_ID = "callmate_active_call"
        private const val NOTIFICATION_ID = 0x43414c4d // "CALM"

        fun createStartIntent(context: Context): Intent =
            Intent(context, CallForegroundService::class.java).apply { action = ACTION_START }
    }
}
