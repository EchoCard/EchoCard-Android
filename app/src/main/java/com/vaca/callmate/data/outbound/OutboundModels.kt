package com.vaca.callmate.data.outbound

import com.vaca.callmate.data.Language
import java.util.UUID

enum class ContactMode { Existing, Manual }

enum class TimingMode { Immediate, Scheduled }

enum class OutboundDialRiskReason {
    EmergencyNumber,
    DeepNight
}

enum class OutboundTaskStatus {
    Scheduled,
    Running,
    Completed,
    Partial,
    Failed;

    /** 与 iOS `OutboundTaskStatus` rawValue 一致，供控制 WS JSON 使用 */
    fun toApiString(): String = when (this) {
        Scheduled -> "scheduled"
        Running -> "running"
        Completed -> "completed"
        Partial -> "partial"
        Failed -> "failed"
    }

    fun titleZh(): String = when (this) {
        Scheduled -> "已定时"
        Running -> "执行中"
        Completed -> "已完成"
        Partial -> "部分成功"
        Failed -> "失败"
    }

    fun titleEn(): String = when (this) {
        Scheduled -> "Scheduled"
        Running -> "Running"
        Completed -> "Completed"
        Partial -> "Partial"
        Failed -> "Failed"
    }

    fun title(language: Language): String =
        if (language == Language.Zh) titleZh() else titleEn()

    companion object {
        fun fromApiString(raw: String): OutboundTaskStatus? = when (raw.lowercase()) {
            "scheduled" -> Scheduled
            "running" -> Running
            "completed" -> Completed
            "partial" -> Partial
            "failed" -> Failed
            else -> null
        }
    }
}

data class OutboundContact(
    val id: UUID = UUID.randomUUID(),
    val phone: String,
    val name: String
)

data class OutboundTask(
    val id: UUID,
    val promptType: String,
    val promptRule: String,
    val contacts: List<OutboundContact>,
    val scheduledAt: Long?,
    var status: OutboundTaskStatus,
    var dialSuccessCount: Int,
    var dialFailureCount: Int,
    val callFrequency: Int,
    val redialMissed: Boolean,
    val createdAt: Long
)

data class OutboundCreateTaskDraft(
    val promptId: String?,
    val contactMode: ContactMode,
    val selectedPhones: Set<String>,
    val manualPhonesText: String,
    val timingMode: TimingMode,
    val scheduledTimeMillis: Long,
    val callFrequency: Int,
    val redialMissed: Boolean
) {
    companion object {
        fun empty(nowMillis: Long) = OutboundCreateTaskDraft(
            promptId = null,
            contactMode = ContactMode.Existing,
            selectedPhones = emptySet(),
            manualPhonesText = "",
            timingMode = TimingMode.Immediate,
            scheduledTimeMillis = nowMillis + 15 * 60_000L,
            callFrequency = 30,
            redialMissed = false
        )
    }
}

data class OutboundCreateTaskSubmission(
    val promptName: String,
    val promptContent: String,
    val contacts: List<OutboundContact>,
    val scheduledAtMillis: Long?,
    val status: OutboundTaskStatus,
    val callFrequency: Int,
    val redialMissed: Boolean
)
