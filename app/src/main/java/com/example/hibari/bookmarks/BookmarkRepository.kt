package com.example.hibari.bookmarks

import com.example.hibari.data.BookmarkDao
import com.example.hibari.data.BookmarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BookmarkRepository(private val dao: BookmarkDao) {

    suspend fun add(url: String, title: String, folderId: Long? = null): Long =
        withContext(Dispatchers.IO) {
            dao.insert(
                BookmarkEntity(
                    url = url,
                    title = title.ifBlank { url },
                    parentFolderId = folderId,
                ),
            )
        }

    suspend fun getRootBookmarks(): List<BookmarkEntity> = withContext(Dispatchers.IO) {
        dao.getRootBookmarks()
    }

    suspend fun getBookmarksInFolder(folderId: Long): List<BookmarkEntity> =
        withContext(Dispatchers.IO) { dao.getBookmarksInFolder(folderId) }

    suspend fun isBookmarked(url: String): Boolean =
        withContext(Dispatchers.IO) { dao.findByUrl(url) != null }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { dao.delete(id) }

    /** Import a list of entities (used by NetscapeHtmlImporter). */
    suspend fun importAll(entities: List<BookmarkEntity>): List<Long> =
        withContext(Dispatchers.IO) { dao.insertAll(entities) }
}
