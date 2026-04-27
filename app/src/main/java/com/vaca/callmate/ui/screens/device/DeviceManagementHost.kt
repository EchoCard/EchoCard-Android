package com.vaca.callmate.ui.screens.device

import androidx.compose.runtime.Composable
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.Language

@Composable
fun DeviceManagementHost(
    language: Language,
    bleManager: BleManager,
    preferences: AppPreferences,
    page: DeviceNavPage,
    onPageChange: (DeviceNavPage) -> Unit,
    onExitRoot: () -> Unit,
    onUnbindDevice: () -> Unit,
    onRebind: () -> Unit
) {
    when (page) {
        DeviceNavPage.Main ->
            DeviceManagementMainScreen(
                language = language,
                bleManager = bleManager,
                preferences = preferences,
                onClose = onExitRoot,
                onUnbindDevice = onUnbindDevice,
                onOpenAdvanced = { onPageChange(DeviceNavPage.Advanced) }
            )
        DeviceNavPage.Advanced ->
            DeviceAdvancedSettingsScreen(
                language = language,
                bleManager = bleManager,
                onBack = { onPageChange(DeviceNavPage.Main) },
                onOpenLight = { onPageChange(DeviceNavPage.Light) },
                onOpenDiagnostics = { onPageChange(DeviceNavPage.Diagnostics) },
                onRebind = onRebind
            )
        DeviceNavPage.Diagnostics ->
            DeviceDiagnosticsScreen(
                language = language,
                bleManager = bleManager,
                onBack = { onPageChange(DeviceNavPage.Advanced) }
            )
        DeviceNavPage.Light ->
            DeviceLightControlScreen(
                language = language,
                bleManager = bleManager,
                onBack = { onPageChange(DeviceNavPage.Advanced) }
            )
    }
}
