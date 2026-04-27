package com.vaca.callmate.ui.screens.device

import android.bluetooth.BluetoothAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vaca.callmate.core.ble.BleManager
import kotlinx.coroutines.flow.combine as flowCombine
import kotlinx.coroutines.flow.distinctUntilChanged

private data class BleCore(
    val btState: Int,
    val bleReady: Boolean,
    val connectedAddr: String?,
    val connectingAddr: String?,
    val otaTransferActive: Boolean,
    val outboundLivePresentation: Boolean,
)

/**
 * 合并多路 BLE StateFlow 后 [distinctUntilChanged]，减少 Tab 页因单次连接抖动触发的整树重组。
 * 与「接电话」页 [com.vaca.callmate.ui.screens.CallsView]、AI 分身 sheet [com.vaca.callmate.ui.screens.AISecView] 共用。
 */
@Immutable
data class BleConnectionSnapshot(
    val btState: Int = BluetoothAdapter.STATE_OFF,
    val bleReady: Boolean = false,
    val connectedAddr: String? = null,
    val connectingAddr: String? = null,
    val otaTransferActive: Boolean = false,
    val outboundLivePresentation: Boolean = false,
    val sessionHadReadyCtrl: Boolean = false,
) {
    val otaUpdating: Boolean
        get() = otaTransferActive && bleReady && !connectedAddr.isNullOrBlank()
}

@Composable
fun rememberBleConnectionSnapshot(bleManager: BleManager): BleConnectionSnapshot {
    val snapshot by remember(bleManager) {
        flowCombine(
            flowCombine(
                flowCombine(
                    bleManager.bluetoothState,
                    bleManager.isReady,
                    bleManager.connectedAddress,
                ) { bt: Int, ready: Boolean, conn: String? ->
                    Triple(bt, ready, conn)
                },
                flowCombine(
                    bleManager.connectingAddress,
                    bleManager.otaTransferActive,
                    bleManager.outboundLivePresentation,
                ) { connecting: String?, ota: Boolean, outbound: Boolean ->
                    Triple(connecting, ota, outbound)
                },
            ) { t1, t2 ->
                BleCore(
                    btState = t1.first,
                    bleReady = t1.second,
                    connectedAddr = t1.third,
                    connectingAddr = t2.first,
                    otaTransferActive = t2.second,
                    outboundLivePresentation = t2.third,
                )
            },
            bleManager.sessionHadReadyCtrl,
        ) { core, sess ->
            BleConnectionSnapshot(
                btState = core.btState,
                bleReady = core.bleReady,
                connectedAddr = core.connectedAddr,
                connectingAddr = core.connectingAddr,
                otaTransferActive = core.otaTransferActive,
                outboundLivePresentation = core.outboundLivePresentation,
                sessionHadReadyCtrl = sess,
            )
        }.distinctUntilChanged()
    }.collectAsState(initial = BleConnectionSnapshot())
    return snapshot
}
