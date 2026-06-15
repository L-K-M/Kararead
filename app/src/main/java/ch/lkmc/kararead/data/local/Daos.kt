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

    @Query("DELETE FROM cached_article")
    suspend fun clear()
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
