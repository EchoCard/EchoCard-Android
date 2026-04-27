package com.vaca.callmate.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 与 iOS SwiftData `CallLog` 模型字段一一对应；主键 `id` 为通话 UUID 字符串，与 iOS 一致。
 */
@Entity(tableName = "call_log")
data class CallLogEntity(
    @PrimaryKey val id: String,
    val createdAtMillis: Long,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val durationSeconds: Int,
    val recordingFileName: String?,
    val statusRaw: String,
    val phone: String,
    val label: String,
    val summary: String?,
    val fullSummary: String?,
    val backendSummary: String?,
    val isSimulation: Boolean,
    val isImportant: Boolean?,
    val languageRaw: String,
    val outboundTaskId: String?,
    val wsSessionId: String?,
    val errorMessage: String?,
    val tokenCount: Int?,
    val aiDuration: Int?
)
