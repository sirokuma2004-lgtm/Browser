package com.example.hibari.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

// ── Entities ────────────────────────────────────────────────────────────────

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentFolderId: Long? = null,
    val title: String,
    val url: String?,
    val isFolder: Boolean = false,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long = System.currentTimeMillis(),
    val visitCount: Int = 1,
)

// ── DAOs ────────────────────────────────────────────────────────────────────

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks WHERE parentFolderId IS NULL ORDER BY position ASC")
    suspend fun getRootBookmarks(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE parentFolderId = :folderId ORDER BY position ASC")
    suspend fun getBookmarksInFolder(folderId: Long): List<BookmarkEntity>

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntity): Long

    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT 200")
    suspend fun getRecent(): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' ORDER BY visitedAt DESC LIMIT 20")
    suspend fun search(query: String): List<HistoryEntity>

    @Query("DELETE FROM history WHERE visitedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM history")
    suspend fun deleteAll()
}

// ── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [BookmarkEntity::class, HistoryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hibari.db",
                ).build().also { instance = it }
            }
    }
}
