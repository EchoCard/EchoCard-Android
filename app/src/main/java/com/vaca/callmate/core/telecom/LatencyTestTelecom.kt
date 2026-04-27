package com.vaca.callmate.core.telecom

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/**
 * 注册自管理 [PhoneAccount] 并发起「延迟测试」来电，与 iOS `LatencyTestCallProvider` 对齐。
 */
object LatencyTestTelecom {
    private const val TAG = "LatencyTestTelecom"
    private const val ACCOUNT_ID = "callmate_latency_test"

    /** 自管理通话需 API 28+（[PhoneAccount.CAPABILITY_SELF_MANAGED]）。 */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    fun phoneAccountHandle(context: Context): PhoneAccountHandle {
        val cn = ComponentName(context, CallMateConnectionService::class.java)
        return PhoneAccountHandle(cn, ACCOUNT_ID, Process.myUserHandle())
    }

    fun registerIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val tm = context.getSystemService(TelecomManager::class.java) ?: return
        val handle = phoneAccountHandle(context)
        val builder = PhoneAccount.Builder(handle, "EchoCard Latency Test")
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
        }
        tm.registerPhoneAccount(builder.build())
    }

    /**
     * 发起系统来电；成功则 [CallMateConnectionService] 将创建 [LatencyTestConnection]。
     */
    fun reportIncomingCall(context: Context): Boolean {
        if (!isSupported()) {
            Log.e(TAG, "Telecom latency test requires API 28+")
            return false
        }
        return try {
            registerIfNeeded(context)
            val tm = context.getSystemService(TelecomManager::class.java) ?: return false
            val extras = Bundle()
            extras.putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.parse("tel:latencytest")
            )
            tm.addNewIncomingCall(phoneAccountHandle(context), extras)
            true
        } catch (e: Exception) {
            Log.e(TAG, "addNewIncomingCall failed", e)
            false
        }
    }

    fun endLatencyTestCall() {
        LatencyTestConnectionHolder.current?.endFromApp()
    }
}
