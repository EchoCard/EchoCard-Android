package com.vaca.callmate.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 与 iOS SwiftData CallFeedback 对齐
 */
@Entity(
    tableName = "call_feedback",
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
data class CallFeedbackEntity(
    @PrimaryKey val id: String,
    val callId: String,
    val ratingRaw: String,
    val note: String?,
    val createdAtMillis: Long
)
