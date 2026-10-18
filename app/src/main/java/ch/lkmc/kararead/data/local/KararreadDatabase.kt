package ch.lkmc.kararead.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ReadingProgressEntity::class,
        CachedArticleEntity::class,
        ReadingDayEntity::class,
        PendingOpEntity::class,
        CachedHighlightEntity::class,
        HighlightOpEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class KararreadDatabase : RoomDatabase() {
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun cachedArticleDao(): CachedArticleDao
    abstract fun readingStatsDao(): ReadingStatsDao
    abstract fun pendingOpDao(): PendingOpDao
    abstract fun cachedHighlightDao(): CachedHighlightDao
    abstract fun highlightOpDao(): HighlightOpDao

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

        /** v4 → v5: add the pending_op outbox for offline archive/favourite. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_op (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "bookmarkId TEXT NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "value INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "attempts INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_op_bookmarkId_type " +
                        "ON pending_op (bookmarkId, type)",
                )
            }
        }

        /** v5 → v6: add the highlight cache and its outbox (offline highlights). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cached_highlight (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "bookmarkId TEXT NOT NULL, " +
                        "startOffset INTEGER NOT NULL, " +
                        "endOffset INTEGER NOT NULL, " +
                        "color TEXT NOT NULL, " +
                        "text TEXT, " +
                        "note TEXT, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "synced INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cached_highlight_bookmarkId " +
                        "ON cached_highlight (bookmarkId)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS highlight_op (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "highlightId TEXT NOT NULL, " +
                        "bookmarkId TEXT NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "startOffset INTEGER NOT NULL, " +
                        "endOffset INTEGER NOT NULL, " +
                        "color TEXT NOT NULL, " +
                        "text TEXT, " +
                        "note TEXT, " +
                        "createdAt INTEGER NOT NULL, " +
                        "attempts INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_highlight_op_highlightId " +
                        "ON highlight_op (highlightId)",
                )
            }
        }
    }
}
