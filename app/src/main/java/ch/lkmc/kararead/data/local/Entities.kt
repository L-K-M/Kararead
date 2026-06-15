package ch.lkmc.kararead.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local, client-side reading progress for an article (Karakeep has none). */
@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val bookmarkId: String,
    val fraction: Float,
    val updatedAt: Long,
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
