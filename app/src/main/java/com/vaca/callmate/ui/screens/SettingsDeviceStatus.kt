package com.vaca.callmate.ui.screens

import android.bluetooth.BluetoothAdapter
import androidx.compose.ui.graphics.Color
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppWarning

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

/**
 * 与 iOS `SettingsViewModel.deviceConnectionText` / `deviceConnectionColor` 语义对齐。
 */
fun settingsDeviceConnectionText(
    language: Language,
    adapterState: Int,
    isReady: Boolean,
    connectedAddress: String?,
    connectingAddress: String?
): String {
    return when (adapterState) {
        BluetoothAdapter.STATE_OFF ->
            t("蓝牙未开启", "Bluetooth Off", language)
        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_TURNING_ON ->
            t("蓝牙重置中", "Bluetooth Resetting", language)
        BluetoothAdapter.STATE_ON -> {
            if (isReady && connectedAddress != null) {
                t("已连接", "Connected", language)
            } else if (connectingAddress != null || connectedAddress != null) {
                t("连接中", "Connecting", language)
            } else {
                t("未连接", "Disconnected", language)
            }
        }
        else -> t("未连接", "Disconnected", language)
    }
}

fun settingsDeviceConnectionColor(
    adapterState: Int,
    isReady: Boolean,
    connectedAddress: String?,
    connectingAddress: String?
): Color {
    if (adapterState == BluetoothAdapter.STATE_OFF) return AppWarning
    if (adapterState != BluetoothAdapter.STATE_ON) return AppTextSecondary
    if (isReady && connectedAddress != null) return AppSuccess
    if (connectingAddress != null || connectedAddress != null) return AppWarning
    return AppTextSecondary
}
