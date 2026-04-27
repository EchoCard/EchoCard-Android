package com.vaca.callmate.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CallFeedbackDao {

    @Query("SELECT * FROM call_feedback WHERE callId = :callId")
    suspend fun getByCallId(callId: String): List<CallFeedbackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(feedback: CallFeedbackEntity): Unit
}
