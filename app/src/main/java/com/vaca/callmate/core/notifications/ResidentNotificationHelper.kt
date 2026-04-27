package com.vaca.callmate.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.vaca.callmate.R

/**
 * Android 侧对标 iOS「待机时显示灵动岛」常驻态：低优先级 ongoing 通知（非 Foreground Service）。
 */
object ResidentNotificationHelper {

    private const val CHANNEL_ID = "callmate_resident"
    private const val NOTIF_ID = 71001

    fun update(context: Context, enabled: Boolean, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "EchoCard",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        if (!enabled) {
            nm.cancel(NOTIF_ID)
            return
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIF_ID, n)
    }
}
