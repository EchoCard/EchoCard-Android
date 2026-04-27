package com.vaca.callmate.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vaca.callmate.ui.theme.CallMateTheme

/**
 * IDE 預覽中避免初始化 BLE（[com.vaca.callmate.core.ble.BleManager] 會 registerReceiver）、
 * Room、部分 ViewModel 等，否則易出現 InvocationTargetException。
 * 真機運行請使用實際畫面，勿依賴此佔位。
 */
@Composable
fun PreviewUnavailablePlaceholder(label: String) {
    CallMateTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
        }
    }
}
