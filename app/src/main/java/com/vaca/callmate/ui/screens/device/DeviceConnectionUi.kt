package com.vaca.callmate.ui.screens.device

import android.bluetooth.BluetoothAdapter
import androidx.compose.ui.graphics.Color
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppSuccess
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppWarning

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

/**
 * 与 [echoCardConnectionLabel] 显示「EchoCard 已连接」时一致（含 `sessionHadReadyCtrl` + 短暂 `!isReady`）。
 * 用于接管模式遮罩、`deviceConnected` 透明度等，避免顶栏已连接仍出现「立即连接」。
 */
fun echoCardUiShowsConnected(
    btState: Int,
    isReady: Boolean,
    connected: String?,
    connecting: String?,
    sessionHadReadyCtrl: Boolean,
): Boolean {
    return when (btState) {
        BluetoothAdapter.STATE_OFF,
        BluetoothAdapter.STATE_TURNING_OFF -> false
        else -> when {
            connected.isNullOrBlank() -> false
            isReady -> true
            !connecting.isNullOrBlank() -> false
            sessionHadReadyCtrl -> true
            else -> false
        }
    }
}

/**
 * 与 iOS `CallsView.connectionStatusText` / AI 分身 sheet 顶栏一致。
 *
 * @param sessionHadReadyCtrl 本次连接是否曾 ctrl 就绪。`onServiceChanged` 后可能短暂 `!isReady` 但 GATT 仍连着，
 *                            此时仍显示「已连接」，避免与首次「连接中」混淆。
 */
fun echoCardConnectionLabel(
    btState: Int,
    isReady: Boolean,
    connected: String?,
    connecting: String?,
    otaUpdating: Boolean,
    sessionHadReadyCtrl: Boolean,
    language: Language,
): String {
    return when (btState) {
        BluetoothAdapter.STATE_OFF -> t("蓝牙未开启", "Bluetooth Off", language)
        BluetoothAdapter.STATE_TURNING_OFF -> t("蓝牙未开启", "Bluetooth Off", language)
        else -> {
            if (isReady && !connected.isNullOrBlank()) {
                if (otaUpdating) {
                    t("EchoCard 已连接 · 固件升级中", "EchoCard Connected · Firmware update", language)
                } else {
                    t("EchoCard 已连接", "EchoCard Connected", language)
                }
            } else if (!connecting.isNullOrBlank()) {
                t("EchoCard 连接中", "EchoCard Connecting", language)
            } else if (!connected.isNullOrBlank()) {
                if (sessionHadReadyCtrl) {
                    t("EchoCard 已连接", "EchoCard Connected", language)
                } else {
                    t("EchoCard 连接中", "EchoCard Connecting", language)
                }
            } else {
                t("EchoCard 未连接", "EchoCard Disconnected", language)
            }
        }
    }
}

fun echoCardConnectionColor(
    btState: Int,
    isReady: Boolean,
    connected: String?,
    connecting: String?,
    sessionHadReadyCtrl: Boolean,
): Color {
    return when (btState) {
        BluetoothAdapter.STATE_OFF,
        BluetoothAdapter.STATE_TURNING_OFF -> AppWarning
        else -> {
            when {
                isReady && !connected.isNullOrBlank() -> AppSuccess
                !connected.isNullOrBlank() && sessionHadReadyCtrl && !isReady -> AppSuccess
                !connecting.isNullOrBlank() || !connected.isNullOrBlank() -> AppWarning
                else -> AppTextSecondary
            }
        }
    }
}

/** 与 iOS `DeviceModalView.connectionStatus` 一致：优先 `isCtrlReady`。 */
fun deviceManagementConnectionLabel(
    language: Language,
    isCtrlReady: Boolean,
    connectingAddress: String?,
    connectedAddress: String?,
    bluetoothState: Int
): Pair<String, Color> {
    if (isCtrlReady) {
        return t("已连接", "Connected", language) to AppSuccess
    }
    if (connectingAddress != null || (connectedAddress != null && bluetoothState == BluetoothAdapter.STATE_ON)) {
        return t("连接中", "Connecting", language) to AppWarning
    }
    return when (bluetoothState) {
        BluetoothAdapter.STATE_OFF ->
            t("蓝牙未开启", "Bluetooth Off", language) to AppTextSecondary
        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_TURNING_ON ->
            t("蓝牙重置中", "Bluetooth Resetting", language) to AppWarning
        BluetoothAdapter.STATE_ON ->
            t("未连接", "Disconnected", language) to AppTextSecondary
        else -> t("未连接", "Disconnected", language) to AppTextSecondary
    }
}
