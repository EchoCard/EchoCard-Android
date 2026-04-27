@file:Suppress("DEPRECATION")

package com.vaca.callmate.core.telephony

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.vaca.callmate.core.AppFeatureFlags

/**
 * 实验用：来电时打 logcat，观察系统是否提供号码/能否解析联系人名。
 * 过滤：`adb logcat -s IncomingCallDebug`
 *
 * Android 10+ 常对第三方应用隐藏来电号码；若号码为 null 属预期，需默认拨号/ConnectionService 等能力才可能拿到。
 */
object IncomingCallDebugLogger {
    private const val TAG = "IncomingCallDebug"

    private var appContext: Context? = null
    private var telephonyManager: TelephonyManager? = null

    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    private var phoneStateReceiver: BroadcastReceiver? = null
    private var started = false

    fun start(context: Context) {
        if (!AppFeatureFlags.incomingCallDebugLoggingEnabled) return
        val app = context.applicationContext
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_PHONE_STATE 未授权，无法监听通话状态")
            return
        }
        synchronized(this) {
            if (started) return
            started = true
            appContext = app
            val tm = app.getSystemService(TelephonyManager::class.java) ?: return
            telephonyManager = tm
            Log.i(
                TAG,
                "start: manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}"
            )

            @Suppress("DEPRECATION")
            val listener =
                object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        val stateStr = callStateToString(state)
                        Log.i(
                            TAG,
                            "PhoneStateListener: state=$stateStr number=${phoneNumber ?: "(null)"}"
                        )
                        if (state == TelephonyManager.CALL_STATE_RINGING) {
                            Log.i(TAG, "  RINGING")
                        }
                    }
                }
            phoneStateListener = listener
            @Suppress("DEPRECATION")
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)

            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
                        val extraState = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                        @Suppress("DEPRECATION")
                        val incoming =
                            intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                        Log.i(
                            TAG,
                            "Broadcast PHONE_STATE: extraState=$extraState EXTRA_INCOMING_NUMBER=${incoming ?: "(null)"}"
                        )
                    }
                }
            phoneStateReceiver = receiver
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(receiver, filter)
            }
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (!started) return
            started = false
            phoneStateReceiver?.let { r ->
                try {
                    app.unregisterReceiver(r)
                } catch (_: Exception) {
                }
            }
            phoneStateReceiver = null
            telephonyManager?.let { tm ->
                phoneStateListener?.let { l ->
                    @Suppress("DEPRECATION")
                    tm.listen(l, PhoneStateListener.LISTEN_NONE)
                }
            }
            phoneStateListener = null
            telephonyManager = null
            appContext = null
            Log.i(TAG, "stop")
        }
    }

    private fun callStateToString(state: Int): String =
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            else -> "UNKNOWN($state)"
        }

}
