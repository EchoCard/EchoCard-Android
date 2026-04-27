@file:Suppress("DEPRECATION")

package com.vaca.callmate.core.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.features.calls.IncomingCallSessionCoordinator

/**
 * 智能模式（`semi`）：仅在系统检测到通话（振铃/摘机）时让 MCU `hfp_connect`，空闲时 `hfp_disconnect`，
 * 避免 BLE 一连上经典蓝牙就拉起 HFP。
 */
object SemiModeHfpTelephonyBridge {
    private const val TAG = "SemiModeHfp"

    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyManager: TelephonyManager? = null
    private var started = false

    fun start(context: Context, bleManager: BleManager) {
        val app = context.applicationContext
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_PHONE_STATE not granted; semi HFP follows telephony only after permission")
            return
        }
        synchronized(this) {
            if (started) return
            started = true
            val tm = app.getSystemService(TelephonyManager::class.java) ?: return
            telephonyManager = tm
            val listener =
                object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        bleManager.applySemiModeHfpFromTelephony(state)
                        // 陌生号 smart 模式 + pickup_delay 窗口内用户抢接 → 让协调器翻
                        // `userHandledEarly`、取消 pickupJob、打断 WS，避免后续 AI `✿END✿` 把
                        // 用户正在说的通话挂掉（对齐 iOS 883ede8d）。协调器内部按 pendingAnswerUid
                        // 自做门禁，非来电期间的 OFFHOOK/IDLE 会被忽略。
                        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                            IncomingCallSessionCoordinator.notifyTelephonyOffhook()
                        }
                    }
                }
            phoneStateListener = listener
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            val initial = tm.callState
            Log.i(TAG, "start: initial callState=$initial")
            bleManager.applySemiModeHfpFromTelephony(initial)
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (!started) return
            started = false
            telephonyManager?.let { tm ->
                phoneStateListener?.let { l ->
                    tm.listen(l, PhoneStateListener.LISTEN_NONE)
                }
            }
            phoneStateListener = null
            telephonyManager = null
            Log.i(TAG, "stop")
        }
    }
}
