package com.vaca.callmate.core.network

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat

/**
 * 与 iOS `permissions.networkStatus == .satisfied` / EchoCardPermissionsCard [networkOk] 对齐。
 */
object NetworkStatus {

    fun isValidated(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_NETWORK_STATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
