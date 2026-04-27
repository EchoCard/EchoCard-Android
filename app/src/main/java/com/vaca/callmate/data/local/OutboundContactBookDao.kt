package com.vaca.callmate.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboundContactBookDao {
    @Query("SELECT * FROM outbound_contact_book_entry ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<OutboundContactBookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OutboundContactBookEntity)

    @Query("DELETE FROM outbound_contact_book_entry WHERE id = :id")
    suspend fun deleteById(id: String)
}
