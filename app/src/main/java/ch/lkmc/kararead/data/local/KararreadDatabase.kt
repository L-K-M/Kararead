package ch.lkmc.kararead.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ReadingProgressEntity::class, CachedArticleEntity::class, ReadingDayEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class KararreadDatabase : RoomDatabase() {
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun cachedArticleDao(): CachedArticleDao
    abstract fun readingStatsDao(): ReadingStatsDao

    companion object {
        const val NAME = "kararead.db"

        /** v2 → v3: add the reading_day table; preserves progress + cache. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS reading_day (" +
                        "date TEXT NOT NULL PRIMARY KEY, " +
                        "seconds INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)",
                )
            }
        }
    }
}
