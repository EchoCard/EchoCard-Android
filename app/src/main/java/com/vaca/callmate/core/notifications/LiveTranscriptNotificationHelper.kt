package com.vaca.callmate.core.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.Manifest
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.vaca.callmate.MainActivity
import com.vaca.callmate.R
import com.vaca.callmate.data.Language

private const val TAG = "LiveTranscriptNotif"

/**
 * 与 iOS `scheduleAIAnsweredNotificationIfNeeded` + `UNMutableNotificationContent` 对齐：
 * - **identifier / threadIdentifier**：`live_transcript_<uid>`（通知 tag + 动态快捷方式 id + LocusId）
 * - **title / body**、**sound**（默认提示音）
 * - **interruptionLevel .timeSensitive**：高重要性 Channel + 对话样式 + 振动/灯光（系统能力内等价）
 * - **relevanceScore 1.0**：`setSortKey("1.000000")` 固定最高档排序键
 * - **userInfo** → Intent extras + Notification extras
 */
object LiveTranscriptNotificationHelper {

    const val EXTRA_LIVE_TRANSCRIPT_CALL = "live_transcript_call"
    const val EXTRA_CALL_ID = "call_id"
    const val EXTRA_WS_SESSION_ID = "ws_session_id"

    private const val CHANNEL_ID = "callmate_live_transcript_v2"
    private const val CHANNEL_NAME = "来电代接 / Call screening"
    /** 与 iOS 分组展示一致：同组内按 relevance 排序 */
    private const val NOTIFICATION_GROUP = "callmate.live_transcript.group"
    /** 对齐 iOS relevanceScore = 1.0 */
    private const val SORT_KEY_RELEVANCE_MAX = "1.000000"

    private fun tagForUid(uid: Int) = "live_transcript_$uid"

    fun shortcutIdForUid(uid: Int) = "live_transcript_$uid"

    private fun notifyIdForUid(uid: Int): Int = 52_000_000 + (uid and 0x7fff)

    private fun contentExtras(wsSessionId: String) = Bundle().apply {
        putString(EXTRA_LIVE_TRANSCRIPT_CALL, "1")
        putString(EXTRA_CALL_ID, "")
        putString(EXTRA_WS_SESSION_ID, wsSessionId)
    }

    fun showAiAnswered(
        context: Context,
        uid: Int,
        language: Language,
        wsSessionId: String
    ) {
        if (uid <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "skip: POST_NOTIFICATIONS not granted uid=$uid")
                return
            }
        }
        val app = context.applicationContext
        ensureChannel(app)

        val title = if (language == Language.Zh) "来电代接" else "Call screening"
        val body = if (language == Language.Zh) {
            "AI分身已接听当前来电，点击查看实时转写。"
        } else {
            "AI has answered the call. Tap to view live transcript."
        }

        val deepLink = Uri.parse("callmate://livecall/")
        val open = buildContentIntent(app, deepLink, wsSessionId)
        val shortcutId = shortcutIdForUid(uid)
        pushConversationShortcut(app, shortcutId, title, body, open, language)

        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentPending = PendingIntent.getActivity(app, notifyIdForUid(uid), open, piFlags)

        val accent = ContextCompat.getColor(app, R.color.purple_500)
        val me = Person.Builder()
            .setName(if (language == Language.Zh) "本机" else "Device")
            .setImportant(true)
            .build()
        val ai = Person.Builder()
            .setName(if (language == Language.Zh) "AI 分身" else "AI Avatar")
            .setImportant(true)
            .setIcon(IconCompat.createWithResource(app, R.mipmap.ic_launcher))
            .build()
        val messagingStyle = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(title)
            .setGroupConversation(true)
            .addMessage(body, System.currentTimeMillis(), ai)

        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(messagingStyle)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPending)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setOnlyAlertOnce(false)
            .setColor(accent)
            .setColorized(true)
            .setShortcutId(shortcutId)
            .setGroup(NOTIFICATION_GROUP)
            .setSortKey(SORT_KEY_RELEVANCE_MAX)
            .addExtras(contentExtras(wsSessionId))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setLocusId(LocusIdCompat(shortcutId))
        }

        val n = builder.build()
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(tagForUid(uid), notifyIdForUid(uid), n)
        Log.i(TAG, "posted uid=$uid thread=$shortcutId relevance=$SORT_KEY_RELEVANCE_MAX")
    }

    private fun buildContentIntent(app: Context, deepLink: Uri, wsSessionId: String): Intent {
        return Intent(Intent.ACTION_VIEW, deepLink).apply {
            setClass(app, MainActivity::class.java)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_LIVE_TRANSCRIPT_CALL, "1")
            putExtra(EXTRA_CALL_ID, "")
            putExtra(EXTRA_WS_SESSION_ID, wsSessionId)
        }
    }

    private fun pushConversationShortcut(
        app: Context,
        shortcutId: String,
        title: String,
        body: String,
        open: Intent,
        language: Language
    ) {
        try {
            val person = Person.Builder()
                .setName(if (language == Language.Zh) "AI 分身" else "AI Avatar")
                .setImportant(true)
                .setIcon(IconCompat.createWithResource(app, R.mipmap.ic_launcher))
                .build()
            val shortcut = ShortcutInfoCompat.Builder(app, shortcutId)
                .setShortLabel(title)
                .setLongLabel("$title — $body")
                .setIntent(open)
                .setLongLived(true)
                .setIcon(IconCompat.createWithResource(app, R.mipmap.ic_launcher))
                .setPerson(person)
                .setCategories(setOf("android.shortcut.conversation"))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
        } catch (e: Exception) {
            Log.w(TAG, "pushDynamicShortcut failed id=$shortcutId", e)
        }
    }

    /**
     * 与 iOS 通话结束撤销同 thread 通知一致：取消通知并移除对话快捷方式。
     */
    fun clearForUid(context: Context, uid: Int) {
        if (uid <= 0) return
        val app = context.applicationContext
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(tagForUid(uid), notifyIdForUid(uid))
        try {
            ShortcutManagerCompat.removeDynamicShortcuts(app, listOf(shortcutIdForUid(uid)))
        } catch (e: Exception) {
            Log.w(TAG, "removeDynamicShortcuts uid=$uid", e)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val soundUri: Uri = Settings.System.DEFAULT_NOTIFICATION_URI
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val ch = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "AI 代接后立即提示；与 iOS Time Sensitive 同级为高优先级通道"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 280, 200, 280)
            setSound(soundUri, attrs)
            enableLights(true)
            lightColor = ContextCompat.getColor(context, R.color.purple_500)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setAllowBubbles(true)
            }
        }
        nm.createNotificationChannel(ch)
    }
}
