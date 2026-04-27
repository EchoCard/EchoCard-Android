package com.vaca.callmate.ui.screens.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.McuPeripheralRegs
import com.vaca.callmate.core.ble.McuRegDumpData
import com.vaca.callmate.core.ble.McuRegDumpState
import com.vaca.callmate.core.ble.requestRegDump
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppError
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private fun Modifier.regDumpCard(shape: RoundedCornerShape = RoundedCornerShape(20.dp)): Modifier =
    this
        .shadow(6.dp, shape, spotColor = Color.Black.copy(alpha = 0.04f), ambientColor = Color.Transparent)
        .clip(shape)
        .background(AppSurface)
        .border(0.5.dp, Color.White.copy(alpha = 0.55f), shape)

@Composable
fun McuRegistersScreen(
    language: Language,
    bleManager: BleManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by bleManager.mcuRegDumpState.collectAsState()
    val isReady by bleManager.isReady.collectAsState()
    var search by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(mapOf<String, Boolean>()) }

    LaunchedEffect(isReady) {
        if (isReady && state is McuRegDumpState.Idle) {
            bleManager.requestRegDump()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackgroundSecondary)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBackIos,
                    contentDescription = null,
                    tint = AppPrimary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    t("MCU 寄存器", "MCU Registers", language),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextPrimary
                )
            }
            IconButton(
                onClick = { bleManager.requestRegDump() },
                enabled = isReady && (state !is McuRegDumpState.Loading),
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = AppPrimary)
            }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = {
                Text(t("外设名或地址/值", "Peripheral or addr/value", language), color = AppTextSecondary)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AppSurface,
                unfocusedContainerColor = AppSurface,
                disabledContainerColor = AppSurface,
                focusedBorderColor = AppPrimary.copy(alpha = 0.22f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.55f)
            )
        )

        when (val st = state) {
            McuRegDumpState.Idle -> {
                Text(
                    t("点击右上角刷新获取寄存器快照", "Tap refresh to fetch register snapshot", language),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .regDumpCard()
                        .padding(16.dp),
                    color = AppTextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
            is McuRegDumpState.Loading -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .regDumpCard()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = AppPrimary)
                    Text(
                        t("接收中…", "Receiving…", language) + " (${st.receivedChunks}/${st.totalChunks})",
                        fontSize = 13.sp,
                        color = AppTextSecondary,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
            is McuRegDumpState.Error -> {
                Text(
                    st.message,
                    color = AppError,
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .regDumpCard()
                        .padding(16.dp)
                )
            }
            is McuRegDumpState.Loaded -> {
                val filtered = remember(st.data, search) {
                    filterPeripherals(st.data, search.trim())
                }
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filtered.forEach { periph ->
                        val open = expanded[periph.name] ?: false
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .regDumpCard()
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expanded = expanded + (periph.name to !open)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(periph.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    Text(
                                        "base 0x${"%08X".format(periph.base)}",
                                        fontSize = 12.sp,
                                        color = AppTextSecondary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Text(
                                    text = "${periph.registers.size} regs",
                                    fontSize = 11.sp,
                                    color = AppTextSecondary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Icon(
                                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    null,
                                    tint = AppTextSecondary.copy(alpha = 0.8f)
                                )
                            }
                            if (open) {
                                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                                    periph.registers.forEach { reg ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                "+0x${"%X".format(reg.offset * 4)}",
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = AppTextSecondary
                                            )
                                            Text(
                                                "0x${"%08X".format(reg.value)}",
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = AppTextPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun filterPeripherals(data: McuRegDumpData, q: String): List<McuPeripheralRegs> {
    if (q.isEmpty()) return data.peripherals
    val ql = q.lowercase()
    return data.peripherals.mapNotNull { periph ->
        val nameMatch = periph.name.lowercase().contains(ql)
        val matchingRegs = periph.registers.filter { reg ->
            if (nameMatch) return@filter true
            String.format("%08x", reg.addr).lowercase().contains(ql) ||
                String.format("%08x", reg.value).lowercase().contains(ql)
        }
        when {
            matchingRegs.isEmpty() && !nameMatch -> null
            nameMatch -> periph
            else -> McuPeripheralRegs(periph.name, periph.base, matchingRegs)
        }
    }
}
