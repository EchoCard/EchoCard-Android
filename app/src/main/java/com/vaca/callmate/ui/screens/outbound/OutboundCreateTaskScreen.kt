package com.vaca.callmate.ui.screens.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.local.OutboundPromptTemplateEntity
import com.vaca.callmate.data.outbound.ContactMode
import com.vaca.callmate.data.outbound.OutboundContact
import com.vaca.callmate.data.outbound.OutboundCreateTaskDraft
import com.vaca.callmate.data.outbound.OutboundCreateTaskSubmission
import com.vaca.callmate.data.outbound.OutboundDialRiskControl
import com.vaca.callmate.data.outbound.OutboundTaskStatus
import com.vaca.callmate.data.outbound.TimingMode
import com.vaca.callmate.ui.theme.AppBackgroundSecondary
import com.vaca.callmate.ui.theme.AppBorder
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSurface
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.AppTextSecondary
import com.vaca.callmate.ui.theme.AppTextTertiary
import com.vaca.callmate.ui.theme.AppTypography
import java.util.Calendar

private fun t(zh: String, en: String, language: Language) = if (language == Language.Zh) zh else en

private fun Modifier.outboundCreateGroupedCard(shape: RoundedCornerShape = RoundedCornerShape(12.dp)): Modifier =
    this
        .shadow(
            elevation = 6.dp,
            shape = shape,
            spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.04f),
            ambientColor = androidx.compose.ui.graphics.Color.Transparent
        )
        .background(AppSurface, shape)
        .border(0.5.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f), shape)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutboundCreateTaskScreen(
    language: Language,
    templates: List<OutboundPromptTemplateEntity>,
    existingContacts: List<OutboundContact>,
    initialDraft: OutboundCreateTaskDraft?,
    onOpenAI: (() -> Unit)?,
    onClose: () -> Unit,
    onCreate: (OutboundCreateTaskSubmission) -> Unit,
    modifier: Modifier = Modifier
) {
    var step by remember { mutableIntStateOf(0) } // 0 main, 1 contacts
    var selectedPromptId by remember { mutableStateOf<String?>(null) }
    var contactMode by remember { mutableStateOf(ContactMode.Existing) }
    var selectedPhones by remember { mutableStateOf<Set<String>>(emptySet()) }
    var manualPhonesText by remember { mutableStateOf("") }
    var timingMode by remember { mutableStateOf(TimingMode.Immediate) }
    var scheduledMillis by remember {
        mutableStateOf(Calendar.getInstance().apply { add(Calendar.MINUTE, 15) }.timeInMillis)
    }
    var callFrequency by remember { mutableIntStateOf(30) }
    var redialMissed by remember { mutableStateOf(false) }
    var alertMessage by remember { mutableStateOf<String?>(null) }
    var showPromptPicker by remember { mutableStateOf(false) }
    var promptSearch by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    LaunchedEffect(templates, initialDraft) {
        val draft = initialDraft ?: OutboundCreateTaskDraft.empty(System.currentTimeMillis())
        selectedPromptId = draft.promptId ?: templates.firstOrNull()?.id
        contactMode = draft.contactMode
        selectedPhones = draft.selectedPhones
        manualPhonesText = draft.manualPhonesText
        timingMode = draft.timingMode
        scheduledMillis = draft.scheduledTimeMillis
        callFrequency = draft.callFrequency
        redialMissed = draft.redialMissed
        step = 0
    }

    val selectedTemplate = templates.find { it.id == selectedPromptId } ?: templates.firstOrNull()

    fun parseManualContacts(text: String): List<OutboundContact> {
        val parts = text.split(Regex("[,，\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val seen = mutableSetOf<String>()
        val out = ArrayList<OutboundContact>()
        for (raw in parts) {
            val phone = raw.replace("-", "")
            if (!seen.add(phone)) continue
            out.add(OutboundContact(phone = phone, name = t("手动号码", "Manual Contact", language)))
        }
        return out
    }

    fun createAction() {
        val prompt = selectedTemplate ?: run {
            alertMessage = t("请选择话术。", "Please select a prompt.", language)
            return
        }
        val contacts: List<OutboundContact> = when (contactMode) {
            ContactMode.Existing -> existingContacts.filter { selectedPhones.contains(it.phone) }
            ContactMode.Manual -> parseManualContacts(manualPhonesText)
        }
        if (contacts.isEmpty()) {
            alertMessage = t("请至少选择/输入一个号码。", "Please select or input at least one number.", language)
            return
        }
        val blocked = contacts.firstOrNull { OutboundDialRiskControl.isEmergencyNumber(it.phone) }
        if (blocked != null) {
            val n = OutboundDialRiskControl.normalizePhone(blocked.phone)
            alertMessage = t(
                "检测到紧急号码 $n。包含紧急号码（如 110/112/911/999），默认禁止 AI 外呼。",
                "Detected emergency number $n. Emergency numbers are blocked by default.",
                language
            )
            return
        }
        val executeAt = if (timingMode == TimingMode.Scheduled) scheduledMillis else System.currentTimeMillis()
        if (OutboundDialRiskControl.isDeepNight(executeAt)) {
            alertMessage = t(
                "当前为当地深夜时段（${OutboundDialRiskControl.DEEP_NIGHT_START_HOUR}:00-${OutboundDialRiskControl.DEEP_NIGHT_END_HOUR}:00），默认禁止 AI 外呼。",
                "Local deep-night window (${OutboundDialRiskControl.DEEP_NIGHT_START_HOUR}:00-${OutboundDialRiskControl.DEEP_NIGHT_END_HOUR}:00), AI outbound is blocked.",
                language
            )
            return
        }
        val submission = OutboundCreateTaskSubmission(
            promptName = prompt.name,
            promptContent = prompt.content,
            contacts = contacts,
            scheduledAtMillis = if (timingMode == TimingMode.Scheduled) scheduledMillis else null,
            status = if (timingMode == TimingMode.Scheduled) OutboundTaskStatus.Scheduled else OutboundTaskStatus.Running,
            callFrequency = callFrequency,
            redialMissed = redialMissed
        )
        onCreate(submission)
    }

    Scaffold(
        modifier = modifier,
        containerColor = AppBackgroundSecondary,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(52.dp)
                    .padding(horizontal = 20.dp)
            ) {
                IconButton(
                    onClick = {
                        if (step == 1) step = 0 else onClose()
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(40.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBackIos,
                        contentDescription = null,
                        tint = AppPrimary,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Text(
                    text = if (step == 0) t("创建批量任务", "Create Batch Task", language)
                    else t("选择联系人", "Choose Contacts", language),
                    modifier = Modifier.align(Alignment.Center),
                    style = AppTypography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = AppTextPrimary
                )
                if (step == 0 && onOpenAI != null) {
                    TextButton(
                        onClick = onOpenAI,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .background(
                                    color = AppPrimary,
                                    shape = RoundedCornerShape(999.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AppSurface, modifier = Modifier.size(13.dp))
                            Text(
                                t("AI智能创建", "AI Create", language),
                                style = AppTypography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                color = AppSurface
                            )
                        }
                    }
                }
                if (step == 1) {
                    TextButton(
                        onClick = { step = 0 },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Text(t("完成", "Done", language), color = AppPrimary)
                    }
                }
            }
        },
        bottomBar = {
            if (step == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppSurface)
                        .navigationBarsPadding()
                        .padding(16.dp)
                ) {
                    Button(
                        onClick = { createAction() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(
                                elevation = 8.dp,
                                shape = RoundedCornerShape(16.dp),
                                spotColor = AppPrimary.copy(alpha = 0.35f),
                                ambientColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(t("创建任务", "Create Task", language))
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackgroundSecondary)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (step == 0) {
                Text(
                    t("话术与名单", "Prompt & Contacts", language),
                    style = AppTypography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = AppTextPrimary
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .outboundCreateGroupedCard()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPromptPicker = true }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t("话术配置", "Prompt", language), color = AppTextPrimary)
                        Text(
                            selectedTemplate?.name ?: t("请选择", "Select", language),
                            style = AppTypography.bodySmall,
                            color = AppTextSecondary,
                            maxLines = 1
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = AppBorder)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { step = 1 }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t("外呼名单", "Contacts", language), color = AppTextPrimary)
                        Text(
                            contactSummary(language, contactMode, selectedPhones, manualPhonesText),
                            style = AppTypography.bodySmall,
                            color = AppTextSecondary
                        )
                    }
                }

                Text(
                    t("执行设置", "Execution", language),
                    style = AppTypography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = AppTextPrimary
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .outboundCreateGroupedCard()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t("执行时间", "Timing", language), color = AppTextPrimary)
                        SingleChoiceSegmentedButtonRow {
                            SegmentedButton(
                                selected = timingMode == TimingMode.Immediate,
                                onClick = { timingMode = TimingMode.Immediate },
                                shape = SegmentedButtonDefaults.itemShape(0, 2)
                            ) { Text(t("立即外呼", "Immediate", language)) }
                            SegmentedButton(
                                selected = timingMode == TimingMode.Scheduled,
                                onClick = { timingMode = TimingMode.Scheduled },
                                shape = SegmentedButtonDefaults.itemShape(1, 2)
                            ) { Text(t("定时外呼", "Scheduled", language)) }
                        }
                    }
                    if (timingMode == TimingMode.Scheduled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDatePicker = true },
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(t("选择日期", "Pick date", language), color = AppTextPrimary)
                            Text(formatSchedule(scheduledMillis, language), color = AppTextSecondary)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showTimePicker = true },
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(t("选择时间", "Pick time", language), color = AppTextPrimary)
                            Text(formatTimeOnly(scheduledMillis, language), color = AppTextSecondary)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t("呼叫频率", "Call Frequency", language), color = AppTextPrimary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { callFrequency = (callFrequency - 5).coerceAtLeast(5) },
                                enabled = callFrequency > 5
                            ) { Icon(Icons.Default.Remove, null) }
                            Text(
                                t("$callFrequency 通/时", "$callFrequency/h", language),
                                style = AppTypography.labelLarge,
                                color = AppTextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            IconButton(
                                onClick = { callFrequency = (callFrequency + 5).coerceAtMost(120) },
                                enabled = callFrequency < 120
                            ) { Icon(Icons.Default.Add, null) }
                        }
                    }
                }

                Text(
                    t("高级选项", "Advanced", language),
                    style = AppTypography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = AppTextPrimary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .outboundCreateGroupedCard()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(t("重拨未接通的号码", "Redial Missed Calls", language), color = AppTextPrimary)
                    androidx.compose.material3.Switch(checked = redialMissed, onCheckedChange = { redialMissed = it })
                }
                Text(
                    t("开启后，对未接通号码在任务结束后自动重拨一次。", "When enabled, unanswered numbers are redialed once after task completion.", language),
                    style = AppTypography.bodySmall,
                    color = AppTextSecondary
                )
            } else {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = contactMode == ContactMode.Existing,
                        onClick = { contactMode = ContactMode.Existing },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text(t("从列表选择", "From List", language)) }
                    SegmentedButton(
                        selected = contactMode == ContactMode.Manual,
                        onClick = { contactMode = ContactMode.Manual },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text(t("手动输入", "Manual Input", language)) }
                }
                if (contactMode == ContactMode.Existing) {
                    if (existingContacts.isEmpty()) {
                        Text(
                            t("暂无可选联系人，请切换手动输入。", "No contacts found. Switch to manual input.", language),
                            color = AppTextSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .outboundCreateGroupedCard()
                                .padding(16.dp)
                        )
                    } else {
                        existingContacts.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedPhones = if (selectedPhones.contains(item.phone)) {
                                            selectedPhones - item.phone
                                        } else {
                                            selectedPhones + item.phone
                                        }
                                    }
                                    .outboundCreateGroupedCard()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(item.name, style = AppTypography.bodyLarge, color = AppTextPrimary)
                                    Text(item.phone, style = AppTypography.bodySmall, color = AppTextSecondary)
                                }
                                RadioButton(
                                    selected = selectedPhones.contains(item.phone),
                                    onClick = {
                                        selectedPhones = if (selectedPhones.contains(item.phone)) {
                                            selectedPhones - item.phone
                                        } else {
                                            selectedPhones + item.phone
                                        }
                                    }
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = manualPhonesText,
                        onValueChange = { manualPhonesText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        placeholder = { Text(t("号码", "Numbers", language)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                    Text(
                        t("可输入多个号码，使用逗号或换行分隔。", "Enter multiple numbers separated by commas or new lines.", language),
                        style = AppTypography.bodySmall,
                        color = AppTextSecondary
                    )
                }
            }
        }
    }

    if (showPromptPicker) {
        val filtered = remember(promptSearch, templates) {
            val k = promptSearch.trim()
            if (k.isEmpty()) templates
            else templates.filter { it.name.contains(k, true) || it.content.contains(k, true) }
        }
        AlertDialog(
            onDismissRequest = { showPromptPicker = false },
            confirmButton = { TextButton({ showPromptPicker = false }) { Text(t("完成", "Done", language)) } },
            title = { Text(t("选择模版", "Select Template", language)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = promptSearch,
                        onValueChange = { promptSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(t("搜索模版名称或内容", "Search name or content", language)) }
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.verticalScroll(rememberScrollState()).height(280.dp)) {
                        filtered.forEach { tmpl ->
                            TextButton(onClick = {
                                selectedPromptId = tmpl.id
                                showPromptPicker = false
                            }) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(tmpl.name, style = AppTypography.bodyLarge)
                                    Text(
                                        tmpl.content.replace("\n", " ").take(120),
                                        style = AppTypography.bodySmall,
                                        color = AppTextSecondary,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = scheduledMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { picked ->
                        val old = Calendar.getInstance().apply { timeInMillis = scheduledMillis }
                        val pick = Calendar.getInstance().apply { timeInMillis = picked }
                        old.set(Calendar.YEAR, pick.get(Calendar.YEAR))
                        old.set(Calendar.MONTH, pick.get(Calendar.MONTH))
                        old.set(Calendar.DAY_OF_MONTH, pick.get(Calendar.DAY_OF_MONTH))
                        scheduledMillis = old.timeInMillis
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ showDatePicker = false }) { Text(t("取消", "Cancel", language)) } }
        ) {
            DatePicker(state = state)
        }
    }

    if (showTimePicker) {
        val cal = Calendar.getInstance().apply { timeInMillis = scheduledMillis }
        val timeState = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton({
                    val c = Calendar.getInstance().apply { timeInMillis = scheduledMillis }
                    c.set(Calendar.HOUR_OF_DAY, timeState.hour)
                    c.set(Calendar.MINUTE, timeState.minute)
                    c.set(Calendar.SECOND, 0)
                    scheduledMillis = c.timeInMillis
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ showTimePicker = false }) { Text(t("取消", "Cancel", language)) } },
            text = { TimePicker(state = timeState) }
        )
    }

    alertMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { alertMessage = null },
            confirmButton = { TextButton({ alertMessage = null }) { Text(t("知道了", "OK", language)) } },
            title = { Text(t("提示", "Notice", language)) },
            text = { Text(msg) }
        )
    }
}

private fun contactSummary(
    language: Language,
    contactMode: ContactMode,
    selectedPhones: Set<String>,
    manualPhonesText: String
): String {
    val t: (String, String) -> String = { zh, en -> if (language == Language.Zh) zh else en }
    return when (contactMode) {
        ContactMode.Existing -> {
            if (selectedPhones.isEmpty()) t("请选择", "Select")
            else t("已选 ${selectedPhones.size} 人", "${selectedPhones.size} selected")
        }
        ContactMode.Manual -> {
            val n = manualPhonesText.split(Regex("[,，\\s]+")).map { it.trim() }.count { it.isNotEmpty() }
            if (n == 0) t("手动输入", "Manual input") else t("已输入 $n 个号码", "$n numbers")
        }
    }
}

private fun formatSchedule(millis: Long, language: Language): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH) + 1}-${c.get(Calendar.DAY_OF_MONTH)}"
}

private fun formatTimeOnly(millis: Long, language: Language): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    val h = c.get(Calendar.HOUR_OF_DAY)
    val m = c.get(Calendar.MINUTE)
    return String.format("%02d:%02d", h, m)
}
