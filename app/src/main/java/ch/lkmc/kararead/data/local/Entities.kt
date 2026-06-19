package ch.lkmc.kararead.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local, client-side reading progress for an article (Karakeep has none). */
@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val bookmarkId: String,
    val fraction: Float,
    val updatedAt: Long,
    /** Block-anchor ("<index>:<fraction>") for layout-shift-resilient restore. */
    val anchor: String? = null,
)

/** Offline cache of a fetched article so it reopens instantly / works offline. */
@Entity(tableName = "cached_article")
data class CachedArticleEntity(
    @PrimaryKey val bookmarkId: String,
    val title: String,
    val url: String?,
    val siteName: String?,
    val author: String?,
    val excerpt: String?,
    val imageUrl: String?,
    val faviconUrl: String?,
    val html: String?,
    val text: String?,
    val createdAt: Long,
    val datePublished: Long?,
    val readingTimeMinutes: Int?,
    val archived: Boolean = false,
    val favourited: Boolean = false,
    val cachedAt: Long,
)

/**
 * A bookmark mutation (archive / favourite) made while offline, queued to be
 * replayed against the server when connectivity returns — the "outbox". The
 * local cache is updated optimistically when the op is queued, so lists reflect
 * the change immediately; this row just remembers what still owes the server.
 *
 * One row per (bookmark, field): re-toggling the same field replaces the queued
 * op (last write wins) via the unique index, so undo offline simply overwrites.
 */
@Entity(
    tableName = "pending_op",
    indices = [Index(value = ["bookmarkId", "type"], unique = true)],
)
data class PendingOpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookmarkId: String,
    /** Which field this op sets — [TYPE_ARCHIVED] or [TYPE_FAVOURITED]. */
    val type: String,
    /** The value to set the field to. */
    val value: Boolean,
    val createdAt: Long,
    /** Failed online replays so far; the op is dropped once it hits the cap. */
    val attempts: Int = 0,
) {
    companion object {
        const val TYPE_ARCHIVED = "archived"
        const val TYPE_FAVOURITED = "favourited"
    }
}

/**
 * One row per calendar day the user read, with the seconds spent reading that
 * day. Powers the reading streak and "minutes read today".
 */
@Entity(tableName = "reading_day")
data class ReadingDayEntity(
    /** Local date as ISO `yyyy-MM-dd`. */
    @PrimaryKey val date: String,
    val seconds: Long,
    val updatedAt: Long,
)
