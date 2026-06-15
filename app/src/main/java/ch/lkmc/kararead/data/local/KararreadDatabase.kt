package ch.lkmc.kararead.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ReadingProgressEntity::class, CachedArticleEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class KararreadDatabase : RoomDatabase() {
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun cachedArticleDao(): CachedArticleDao

    companion object {
        const val NAME = "kararead.db"
    }
}
