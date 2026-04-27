package com.vaca.callmate.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboundPromptTemplateDao {
    @Query("SELECT * FROM outbound_prompt_template ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<OutboundPromptTemplateEntity>>

    @Query("SELECT * FROM outbound_prompt_template ORDER BY updatedAtMillis DESC")
    suspend fun getAll(): List<OutboundPromptTemplateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OutboundPromptTemplateEntity)

    @Query("DELETE FROM outbound_prompt_template WHERE id = :id")
    suspend fun deleteById(id: String)
}
