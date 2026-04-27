package com.vaca.callmate.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "outbound_contact_book_entry")
data class OutboundContactBookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)
