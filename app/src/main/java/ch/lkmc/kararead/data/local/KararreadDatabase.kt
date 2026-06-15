package ch.lkmc.kararead.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ReadingProgressEntity::class, CachedArticleEntity::class, ReadingDayEntity::class],
    version = 4,
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

        /** v3 → v4: add the nullable reading_progress.anchor column. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN anchor TEXT")
            }
        }
    }
}
