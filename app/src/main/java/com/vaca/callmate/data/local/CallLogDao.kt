package com.vaca.callmate.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CallLogDao {

    @Query("SELECT * FROM call_log ORDER BY startedAtMillis DESC")
    fun getAllFlow(): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_log ORDER BY startedAtMillis DESC LIMIT :limit")
    fun getRecentFlow(limit: Int): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_log WHERE id = :id")
    suspend fun getById(id: String): CallLogEntity?

    @Transaction
    @Query("SELECT * FROM call_log WHERE id = :id")
    suspend fun getWithTranscript(id: String): CallLogWithTranscript?

    @Transaction
    @Query("SELECT * FROM call_log WHERE id = :id")
    fun getWithTranscriptFlow(id: String): Flow<CallLogWithTranscript?>

    @Transaction
    @Query("SELECT * FROM call_log ORDER BY startedAtMillis DESC")
    fun getAllWithTranscriptFlow(): Flow<List<CallLogWithTranscript>>

    @Transaction
    @Query("SELECT * FROM call_log ORDER BY startedAtMillis DESC LIMIT :limit")
    fun getRecentWithTranscriptFlow(limit: Int): Flow<List<CallLogWithTranscript>>

    @Transaction
    @Query("SELECT * FROM call_log WHERE outboundTaskId = :outboundTaskId ORDER BY startedAtMillis DESC")
    suspend fun getAllWithTranscriptByOutboundTaskId(outboundTaskId: String): List<CallLogWithTranscript>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCall(call: CallLogEntity): Unit

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscriptLines(lines: List<TranscriptLineEntity>): Unit

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeedback(feedback: CallFeedbackEntity): Unit

    @Query("DELETE FROM call_log WHERE id = :id")
    suspend fun deleteById(id: String): Unit

    @Query(
        """
        UPDATE call_log SET
            summary = :summary,
            label = :label,
            fullSummary = :fullSummary,
            backendSummary = :backendSummary,
            tokenCount = :tokenCount,
            aiDuration = :aiDuration
        WHERE id = :id
        """
    )
    suspend fun updateSummaryFromBackend(
        id: String,
        summary: String?,
        label: String?,
        fullSummary: String?,
        backendSummary: String?,
        tokenCount: Int?,
        aiDuration: Int?,
    )
}

data class CallLogWithTranscript(
    @Embedded val call: CallLogEntity,
    @Relation(parentColumn = "id", entityColumn = "callId")
    val transcript: List<TranscriptLineEntity>,
    @Relation(parentColumn = "id", entityColumn = "callId")
    val feedback: List<CallFeedbackEntity>
)
