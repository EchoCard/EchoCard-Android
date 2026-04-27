package com.vaca.callmate.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "outbound_prompt_template")
data class OutboundPromptTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val content: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)
