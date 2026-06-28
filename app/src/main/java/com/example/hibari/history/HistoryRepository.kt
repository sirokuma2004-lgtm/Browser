package com.example.hibari.history

import com.example.hibari.data.HistoryDao
import com.example.hibari.data.HistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HistoryRepository(private val dao: HistoryDao) {

    /** Record a page visit. Existing entries for the same URL get their count bumped. */
    suspend fun record(url: String, title: String) = withContext(Dispatchers.IO) {
        if (url.isBlank() || url.startsWith("about:")) return@withContext
        val existing = dao.findByUrl(url)
        if (existing != null) {
            dao.update(
                existing.copy(
                    title = title.ifBlank { existing.title },
                    visitedAt = System.currentTimeMillis(),
                    visitCount = existing.visitCount + 1,
                ),
            )
        } else {
            dao.insert(HistoryEntity(url = url, title = title.ifBlank { url }))
        }
    }

    suspend fun getRecent(): List<HistoryEntity> = withContext(Dispatchers.IO) {
        dao.getRecent()
    }

    suspend fun search(query: String): List<HistoryEntity> = withContext(Dispatchers.IO) {
        if (query.length < 2) emptyList() else dao.search(query)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) { dao.deleteAll() }

    suspend fun deleteOlderThan(cutoffMs: Long) = withContext(Dispatchers.IO) {
        dao.deleteOlderThan(cutoffMs)
    }
}
