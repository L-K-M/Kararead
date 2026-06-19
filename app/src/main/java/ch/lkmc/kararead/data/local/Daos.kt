package ch.lkmc.kararead.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE bookmarkId = :id")
    fun observe(id: String): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE bookmarkId = :id")
    suspend fun get(id: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    @Query("DELETE FROM reading_progress WHERE bookmarkId = :id")
    suspend fun delete(id: String)
}

@Dao
interface CachedArticleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(article: CachedArticleEntity)
    @Query("SELECT * FROM cached_article WHERE bookmarkId = :id")
    suspend fun get(id: String): CachedArticleEntity?

    @Query("SELECT * FROM cached_article WHERE bookmarkId = :id")
    fun observe(id: String): Flow<CachedArticleEntity?>

    @Query("DELETE FROM cached_article WHERE bookmarkId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM cached_article WHERE cachedAt < :threshold")
    suspend fun deleteOlderThan(threshold: Long)

    @Query("SELECT COUNT(*) FROM cached_article")
    suspend fun count(): Int

    @Query("SELECT bookmarkId FROM cached_article")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT bookmarkId FROM cached_article")
    suspend fun ids(): List<String>

    /** Reading-time hints captured when articles were cached (for list cards). */
    @Query("SELECT bookmarkId, readingTimeMinutes FROM cached_article WHERE readingTimeMinutes IS NOT NULL")
    fun observeReadingTimes(): Flow<List<ReadingTimeRow>>

    /**
     * Metadata of every cached article (no html/text body), newest first. Backs
     * the library's offline fallback: when a listing can't be fetched, the
     * downloaded articles are shown from here instead of a "couldn't load" error.
     */
    @Query(
        """
        SELECT bookmarkId, title, url, siteName, author, excerpt, imageUrl, faviconUrl,
               createdAt, datePublished, readingTimeMinutes, archived, favourited
        FROM cached_article
        ORDER BY createdAt DESC
        """,
    )
    fun observeCachedBookmarks(): Flow<List<CachedBookmarkRow>>

    /**
     * Recently *opened* articles, newest first: cached articles that also have a
     * reading-progress row (i.e. the user actually read them). Joining on
     * reading_progress excludes offline-prefetched-but-unopened articles. Archived
     * (read) articles are excluded explicitly — opening one from the Archive tab
     * re-caches it, which would otherwise resurrect it in "jump back in".
     */
    @Query(
        """
        SELECT c.bookmarkId AS bookmarkId, c.title AS title, c.imageUrl AS imageUrl,
               p.fraction AS fraction, p.updatedAt AS lastOpened
        FROM reading_progress p
        INNER JOIN cached_article c ON c.bookmarkId = p.bookmarkId
        WHERE c.archived = 0
        ORDER BY p.updatedAt DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<RecentArticleRow>>

    @Query("DELETE FROM cached_article")
    suspend fun clear()
}

/** Projection of a cached article's reading-time hint. */
data class ReadingTimeRow(
    val bookmarkId: String,
    val readingTimeMinutes: Int,
)

/** Projection of a cached article's metadata (no body), for the offline list. */
data class CachedBookmarkRow(
    val bookmarkId: String,
    val title: String,
    val url: String?,
    val siteName: String?,
    val author: String?,
    val excerpt: String?,
    val imageUrl: String?,
    val faviconUrl: String?,
    val createdAt: Long,
    val datePublished: Long?,
    val readingTimeMinutes: Int?,
    val archived: Boolean,
    val favourited: Boolean,
)

/** Projection for the library's "recently opened" strip. */
data class RecentArticleRow(
    val bookmarkId: String,
    val title: String,
    val imageUrl: String?,
    val fraction: Float,
    val lastOpened: Long,
)

@Dao
interface PendingOpDao {

    /** Queue an op, replacing any existing one for the same bookmark+field. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(op: PendingOpEntity)

    /** Oldest first, so replays preserve the order the user made them. */
    @Query("SELECT * FROM pending_op ORDER BY createdAt ASC")
    suspend fun all(): List<PendingOpEntity>

    @Query("DELETE FROM pending_op WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pending_op SET attempts = :attempts WHERE id = :id")
    suspend fun setAttempts(id: Long, attempts: Int)
}

@Dao
interface ReadingStatsDao {

    @Query("SELECT * FROM reading_day WHERE date = :date")
    suspend fun get(date: String): ReadingDayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(day: ReadingDayEntity)

    @Query("SELECT * FROM reading_day ORDER BY date DESC")
    fun observeAll(): Flow<List<ReadingDayEntity>>

    @Query("DELETE FROM reading_day")
    suspend fun clear()
}
