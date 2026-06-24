package ch.lkmc.kararead.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import ch.lkmc.kararead.data.model.Bookmark
import ch.lkmc.kararead.data.model.prettyHost
import ch.lkmc.kararead.util.formatShortDate
import ch.lkmc.kararead.util.minutesLeft

/**
 * A single article in a list. Laid out title-first, magazine-style: the serif
 * headline leads, followed by one restrained "source · reading-status" line and
 * a short excerpt. Generous whitespace and a single muted metadata line keep the
 * row calm and uncluttered.
 */
@Composable
fun BookmarkCard(
    bookmark: Bookmark,
    progress: Float,
    modifier: Modifier = Modifier,
    offline: Boolean = false,
    readingTimeOverride: Int? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val isRead = progress >= 0.98f || bookmark.archived
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = bookmark.displayTitle,
                style = MaterialTheme.typography.titleLarge,
                color = if (isRead) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(6.dp))
            MetaLine(
                bookmark = bookmark,
                progress = progress,
                isRead = isRead,
                offline = offline,
                readingTimeOverride = readingTimeOverride,
            )

            if (!bookmark.excerpt.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = bookmark.excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!bookmark.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(bookmark.imageUrl).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

/**
 * The one muted line under the title: `favicon  source · reading-status`, with a
 * favourite star / offline badge trailing. The source name can ellipsize, but the
 * reading-status (the actionable part) is always kept whole.
 */
@Composable
private fun MetaLine(
    bookmark: Bookmark,
    progress: Float,
    isRead: Boolean,
    offline: Boolean,
    readingTimeOverride: Int? = null,
) {
    val source = bookmark.siteName ?: bookmark.url?.let { prettyHost(it) } ?: ""
    val status = readingStatus(bookmark, progress, isRead, readingTimeOverride)
    val color = MaterialTheme.colorScheme.onSurfaceVariant

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!bookmark.faviconUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(bookmark.faviconUrl).build(),
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(6.dp))
        }
        if (source.isNotBlank()) {
            Text(
                text = source,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (status != null) {
            Text(
                text = if (source.isNotBlank()) " · $status" else status,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
            )
        }
        if (bookmark.favourited) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.Star,
                contentDescription = "Favourited",
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (offline) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.DownloadDone,
                contentDescription = "Available offline",
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The reading-status fragment: "Read", "6 of 11 min left", "5 min", or a percent
 * when the length isn't known yet. Falls back to a date when there's nothing else
 * to say, so the line is never empty.
 */
private fun readingStatus(
    bookmark: Bookmark,
    progress: Float,
    isRead: Boolean,
    readingTimeOverride: Int?,
): String? {
    // Reading time is null for list/search results (fetched without content);
    // fall back to a value cached from when the article was opened.
    val total = bookmark.readingTimeMinutes ?: readingTimeOverride
    val started = progress in 0.01f..0.98f
    return when {
        isRead -> "Read"
        started && total != null -> when (val left = minutesLeft(total, progress)) {
            null -> "${(progress * 100).toInt()}%"
            0 -> "Almost done"
            else -> "$left of $total min left"
        }
        started -> "${(progress * 100).toInt()}%"
        total != null -> "$total min"
        else -> formatShortDate(bookmark.datePublished ?: bookmark.createdAt)
    }
}
