package com.vaca.callmate.data

import androidx.compose.runtime.Stable

/**
 * App navigation state: landing -> scanning -> bound -> onboarding -> main
 */
enum class AppState {
    Landing,
    Scanning,
    Bound,
    Onboarding,
    Main
}

enum class Language { Zh, En }

enum class CallStatus { Handled, Blocked, Passed, Missed }

@Stable
data class CallRecord(
    val id: Int,
    /** Room `call_log.id`，用于从详情回查数据库 */
    val roomLogId: String? = null,
    val outboundTaskId: String? = null,
    /** 用于外呼统计/排序；无则 0 */
    val startedAtMillis: Long = 0L,
    val phone: String,
    val label: String,
    val time: String,
    val status: CallStatus,
    val summary: String,
    val fullSummary: String,
    val transcript: List<ChatMessage>? = null,
    val duration: Int,
    /** 与 iOS `CallLog.isSimulation` 一致 */
    val isSimulation: Boolean = false,
    /** 原始 summary 含 `[OUTBOUND_TASK]`，与 iOS 一致 */
    val isOutbound: Boolean = false,
    /** 与 iOS `CallLog.isImportant` 一致 */
    val isImportant: Boolean? = null,
    /** 与 iOS `CallLog.tokenCount` 一致 */
    val tokenCount: Int? = null,
    /** 本地录音文件名（位于 [com.vaca.callmate.core.audio.CallRecordingFiles] 目录） */
    val recordingFileName: String? = null,
    val wsSessionId: String? = null
)

enum class MessageSender { Ai, User, System, Caller }

data class ChatMessage(
    val id: Long,
    val sender: MessageSender,
    val text: String,
    val isAudio: Boolean = false,
    val duration: Int? = null,
    val startTime: Int? = null,
    val endTime: Int? = null
)

data class StrategyData(
    val trigger: String,
    val action: String
)

data class OnboardingScript(
    val stepId: Int,
    val aiQuestions: List<Pair<String, Int>>, // text, durationMs
    val topic: String,
    val simulatedUserReply: String,
    val strategy: StrategyData
)
