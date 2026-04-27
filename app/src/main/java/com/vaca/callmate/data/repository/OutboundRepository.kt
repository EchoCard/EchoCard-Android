package com.vaca.callmate.data.repository

import com.vaca.callmate.data.local.OutboundContactBookDao
import com.vaca.callmate.data.local.OutboundContactBookEntity
import com.vaca.callmate.data.local.OutboundPromptTemplateDao
import com.vaca.callmate.data.local.OutboundPromptTemplateEntity
import com.vaca.callmate.data.outbound.OutboundDefaultTemplates
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class OutboundRepository(
    private val promptDao: OutboundPromptTemplateDao,
    private val contactDao: OutboundContactBookDao
) {
    val templatesFlow: Flow<List<OutboundPromptTemplateEntity>> = promptDao.observeAll()
    val contactsFlow: Flow<List<OutboundContactBookEntity>> = contactDao.observeAll()

    suspend fun seedTemplatesIfEmpty() {
        if (promptDao.getAll().isNotEmpty()) return
        val now = System.currentTimeMillis()
        for (item in OutboundDefaultTemplates.defaults()) {
            promptDao.insert(
                OutboundPromptTemplateEntity(
                    id = UUID.randomUUID().toString(),
                    name = item.name,
                    content = item.content,
                    createdAtMillis = now,
                    updatedAtMillis = now
                )
            )
        }
    }

    suspend fun insertOrUpdateTemplate(entity: OutboundPromptTemplateEntity) {
        promptDao.insert(entity)
    }

    suspend fun deleteTemplate(id: String) {
        promptDao.deleteById(id)
    }

    suspend fun insertOrUpdateContact(entity: OutboundContactBookEntity) {
        contactDao.insert(entity)
    }

    suspend fun deleteContact(id: String) {
        contactDao.deleteById(id)
    }

    suspend fun findTemplateByName(name: String): OutboundPromptTemplateEntity? {
        val n = name.trim()
        if (n.isEmpty()) return null
        return promptDao.getAll().firstOrNull { it.name.trim() == n }
    }
}
