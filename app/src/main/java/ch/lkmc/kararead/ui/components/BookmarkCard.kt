package ch.lkmc.kararead.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import ch.lkmc.kararead.data.model.Bookmark
import ch.lkmc.kararead.data.model.prettyHost

@Composable
fun BookmarkCard(
    bookmark: Bookmark,
    progress: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val isRead = progress >= 0.98f || bookmark.archived
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            // Source line: favicon + host
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!bookmark.faviconUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(bookmark.faviconUrl).build(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = bookmark.siteName ?: bookmark.url?.let { prettyHost(it) } ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bookmark.favourited) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Favourited",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = bookmark.displayTitle,
                style = MaterialTheme.typography.titleLarge,
                color = if (isRead) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            if (!bookmark.excerpt.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = bookmark.excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(6.dp))
            MetaLine(bookmark = bookmark, progress = progress, isRead = isRead)
        }

        if (!bookmark.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(bookmark.imageUrl).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

@Composable
private fun MetaLine(bookmark: Bookmark, progress: Float, isRead: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val readingTime = bookmark.readingTimeMinutes
        val date = ch.lkmc.kararead.util.formatShortDate(bookmark.datePublished ?: bookmark.createdAt)
        val base = when {
            isRead -> "Read"
            progress > 0.01f -> "${(progress * 100).toInt()}% · ${readingTime?.let { "$it min" } ?: "in progress"}"
            readingTime != null -> "$readingTime min read"
            else -> ""
        }
        val label = listOfNotNull(base.ifEmpty { null }, date).joinToString(" · ")
        if (progress in 0.01f..0.98f) {
            ProgressRing(progress = progress, size = 14.dp)
            Spacer(Modifier.width(6.dp))
        }
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
