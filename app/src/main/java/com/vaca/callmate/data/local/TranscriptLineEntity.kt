package com.vaca.callmate.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 与 iOS SwiftData TranscriptLine 对齐
 */
@Entity(
    tableName = "transcript_line",
    foreignKeys = [
        ForeignKey(
            entity = CallLogEntity::class,
            parentColumns = ["id"],
            childColumns = ["callId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("callId")]
)
data class TranscriptLineEntity(
    @PrimaryKey val id: String,
    val callId: String,
    @ColumnInfo(name = "line_index") val index: Int,
    val senderRaw: String,
    val text: String,
    val timestampMillis: Long,
    val startOffsetMs: Int?,
    val endOffsetMs: Int?,
    val typeRaw: String?
)
