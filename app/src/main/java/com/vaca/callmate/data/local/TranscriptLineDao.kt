package com.vaca.callmate.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranscriptLineDao {

    @Query("SELECT * FROM transcript_line WHERE callId = :callId ORDER BY line_index ASC")
    suspend fun getByCallId(callId: String): List<TranscriptLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(lines: List<TranscriptLineEntity>): Unit
}
