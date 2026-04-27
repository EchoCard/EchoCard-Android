package com.vaca.callmate.data.repository

import com.vaca.callmate.data.CallRecord
import com.vaca.callmate.data.CallStatus
import com.vaca.callmate.data.ChatMessage
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.MessageSender
import com.vaca.callmate.data.local.CallFeedbackEntity
import com.vaca.callmate.data.local.CallLogEntity
import com.vaca.callmate.data.local.CallLogWithTranscript
import com.vaca.callmate.data.local.TranscriptLineEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.UUID

fun CallLogWithTranscript.toCallRecord(language: Language): CallRecord {
    val status = when (call.statusRaw.lowercase()) {
        "blocked" -> CallStatus.Blocked
        "passed" -> CallStatus.Passed
        "missed" -> CallStatus.Missed
        else -> CallStatus.Handled
    }
    val rawSummary = call.summary ?: ""
    val isOutbound = rawSummary.contains("[OUTBOUND_TASK]")
    val timeStr = formatTime(call.startedAtMillis)
    val displaySummary = call.summary?.replace("[OUTBOUND_TASK] ", "")?.replace("[OUTBOUND_TASK]", "")?.trim()
        ?: ""
    val transcriptMessages = transcript.sortedBy { it.index }.map { line ->
        val sender = when (line.senderRaw.lowercase()) {
            "caller" -> MessageSender.Caller
            "ai", "assistant" -> MessageSender.Ai
            "user" -> MessageSender.User
            else -> MessageSender.System
        }
        ChatMessage(
            id = line.id.hashCode().toLong(),
            sender = sender,
            text = line.text,
            startTime = line.startOffsetMs?.div(1000),
            endTime = line.endOffsetMs?.div(1000)
        )
    }
    return CallRecord(
        id = call.id.hashCode(),
        roomLogId = call.id,
        outboundTaskId = call.outboundTaskId,
        startedAtMillis = call.startedAtMillis,
        phone = call.phone,
        label = call.label,
        time = timeStr,
        status = status,
        /** 与 iOS `CallLog.displaySummary`：仅展示去标签后的文本；空则详情页显示「（无摘要）」 */
        summary = displaySummary,
        /** 与 iOS `CallLog.fullSummary` 一致：仅 AI 应对结果，不回落到 summary（避免与「通话信息概览」重复） */
        fullSummary = call.fullSummary?.trim() ?: "",
        transcript = transcriptMessages.ifEmpty { null },
        duration = call.durationSeconds,
        isSimulation = call.isSimulation,
        isOutbound = isOutbound,
        isImportant = call.isImportant,
        tokenCount = call.tokenCount,
        recordingFileName = call.recordingFileName,
        wsSessionId = call.wsSessionId
    )
}

private fun formatTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        diff < 86400_000 -> "${diff / 3600_000}小时前"
        else -> "${diff / 86400_000}天前"
    }
}

/**
 * 通话记录仓库：Room 数据 + 与 UI CallRecord 的转换
 */
class CallRepository(
    private val callLogDao: com.vaca.callmate.data.local.CallLogDao,
    private val transcriptDao: com.vaca.callmate.data.local.TranscriptLineDao,
    private val feedbackDao: com.vaca.callmate.data.local.CallFeedbackDao
) {

    fun getAllWithTranscriptFlow(language: Language): Flow<List<CallRecord>> =
        callLogDao.getAllWithTranscriptFlow().map { list ->
            list.map { it.toCallRecord(language) }
        }

    fun getRecentFlow(limit: Int, language: Language): Flow<List<CallRecord>> =
        callLogDao.getRecentWithTranscriptFlow(limit).map { list ->
            list.map { it.toCallRecord(language) }
        }

    /** 外呼通话记录：summary 含 `[OUTBOUND_TASK]`，与 iOS `outboundCalls` 一致 */
    fun getOutboundCallsFlow(language: Language): Flow<List<CallRecord>> =
        callLogDao.getAllWithTranscriptFlow().map { list ->
            list
                .filter { it.call.summary?.contains("[OUTBOUND_TASK]") == true && !it.call.isSimulation }
                .map { it.toCallRecord(language) }
        }

    suspend fun getById(id: String, language: Language): CallRecord? {
        val withTranscript = callLogDao.getWithTranscript(id) ?: return null
        return withTranscript.toCallRecord(language)
    }

    suspend fun getCallLogsForOutboundTask(outboundTaskId: String): List<CallLogWithTranscript> =
        callLogDao.getAllWithTranscriptByOutboundTaskId(outboundTaskId)

    /**
     * 与 iOS 详情页随 `ChatSummaryService` 写库一致：摘要轮询更新后 UI 可刷新。
     */
    fun observeCallRecord(id: String, language: Language): Flow<CallRecord?> =
        callLogDao.getWithTranscriptFlow(id).map { it?.toCallRecord(language) }.distinctUntilChanged()

    suspend fun deleteByRoomId(id: String) {
        callLogDao.deleteById(id)
    }

    /** 与 iOS `CallFeedback` 写入一致 */
    suspend fun insertCallFeedback(callId: String, ratingRaw: String, note: String? = null) {
        val entity = CallFeedbackEntity(
            id = "${callId}_fb_${UUID.randomUUID()}",
            callId = callId,
            ratingRaw = ratingRaw,
            note = note,
            createdAtMillis = System.currentTimeMillis()
        )
        feedbackDao.insert(entity)
    }

    suspend fun getLatestFeedbackRating(callId: String): String? {
        val list = feedbackDao.getByCallId(callId)
        return list.maxByOrNull { it.createdAtMillis }?.ratingRaw
    }

    /**
     * 与 iOS `ChatSummaryService.pollAndUpdate` 写回 DB 的合并规则一致（含 `[OUTBOUND_TASK]` 前缀）。
     */
    suspend fun applyChatSummaryPollResult(
        id: String,
        title: String,
        identity: String?,
        responseResult: String?,
        backendSummary: String?,
        tokenCount: Int?,
        aiDuration: Int?,
    ) {
        val entity = callLogDao.getById(id) ?: return
        val rawSummary = entity.summary ?: ""
        val isOutbound = rawSummary.contains("[OUTBOUND_TASK]")
        val newSummary = if (isOutbound) "[OUTBOUND_TASK] $title" else title
        val newLabel = if (!identity.isNullOrEmpty()) identity else entity.label
        /** 与 iOS `ChatSummaryService`：有 result/suggestion 则写入，否则清空 fullSummary */
        val newFull = responseResult?.trim()?.takeIf { it.isNotEmpty() }
        val newBackend = if (!backendSummary.isNullOrEmpty()) backendSummary else entity.backendSummary
        val newToken = tokenCount?.takeIf { it > 0 } ?: entity.tokenCount
        val newDur = aiDuration?.takeIf { it > 0 } ?: entity.aiDuration
        callLogDao.updateSummaryFromBackend(
            id = id,
            summary = newSummary,
            label = newLabel,
            fullSummary = newFull,
            backendSummary = newBackend,
            tokenCount = newToken,
            aiDuration = newDur,
        )
    }

    suspend fun insertCall(
        id: String,
        startedAtMillis: Long,
        durationSeconds: Int,
        statusRaw: String,
        phone: String,
        label: String,
        summary: String?,
        fullSummary: String?,
        languageRaw: String,
        isSimulation: Boolean,
        transcript: List<Pair<String, String>>,
        recordingFileName: String? = null,
        wsSessionId: String? = null,
        outboundTaskId: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val entity = CallLogEntity(
            id = id,
            createdAtMillis = now,
            startedAtMillis = startedAtMillis,
            endedAtMillis = now,
            durationSeconds = durationSeconds,
            recordingFileName = recordingFileName,
            statusRaw = statusRaw,
            phone = phone,
            label = label,
            summary = summary,
            fullSummary = fullSummary,
            backendSummary = null,
            isSimulation = isSimulation,
            isImportant = null,
            languageRaw = languageRaw,
            outboundTaskId = outboundTaskId,
            wsSessionId = wsSessionId,
            errorMessage = null,
            tokenCount = null,
            aiDuration = null
        )
        callLogDao.insertCall(entity)
        val lines = transcript.mapIndexed { index, (sender, text) ->
            TranscriptLineEntity(
                id = "${id}_t_$index",
                callId = id,
                index = index,
                senderRaw = sender,
                text = text,
                timestampMillis = now,
                startOffsetMs = null,
                endOffsetMs = null,
                typeRaw = null
            )
        }
        callLogDao.insertTranscriptLines(lines)
    }

    suspend fun insertOutboundCallLog(
        callId: String = java.util.UUID.randomUUID().toString(),
        startedAtMillis: Long,
        durationSeconds: Int,
        statusRaw: String,
        phone: String,
        label: String,
        summary: String?,
        fullSummary: String?,
        languageRaw: String,
        isSimulation: Boolean = false,
        outboundTaskId: String?,
        errorMessage: String?
    ) {
        val now = System.currentTimeMillis()
        val entity = CallLogEntity(
            id = callId,
            createdAtMillis = now,
            startedAtMillis = startedAtMillis,
            endedAtMillis = now,
            durationSeconds = durationSeconds,
            recordingFileName = null,
            statusRaw = statusRaw,
            phone = phone,
            label = label,
            summary = summary,
            fullSummary = fullSummary,
            backendSummary = null,
            isSimulation = isSimulation,
            isImportant = null,
            languageRaw = languageRaw,
            outboundTaskId = outboundTaskId,
            wsSessionId = null,
            errorMessage = errorMessage,
            tokenCount = null,
            aiDuration = null
        )
        callLogDao.insertCall(entity)
    }
}
