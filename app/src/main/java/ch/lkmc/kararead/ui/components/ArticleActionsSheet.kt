package ch.lkmc.kararead.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.kararead.data.model.Bookmark
import ch.lkmc.kararead.data.model.prettyHost

/**
 * Long-press action menu for an article row. A bottom sheet titled with the
 * article, offering the same actions reachable by swipe (favourite, archive)
 * plus open / share / open-original — useful where swipe is unavailable (e.g.
 * search) or simply more discoverable than the swipe gestures.
 *
 * Actions whose callback is absent are hidden, so the sheet only ever shows what
 * the host actually supports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleActionsSheet(
    bookmark: Bookmark,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onArchive: ((Bookmark) -> Unit)?,
    onFavourite: ((Bookmark) -> Unit)?,
    archiveIsRestore: Boolean,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(
                    bookmark.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val source = bookmark.siteName ?: bookmark.url?.let { prettyHost(it) }
                if (!source.isNullOrBlank()) {
                    Text(
                        source,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            androidx.compose.material3.HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            ActionRow(Icons.Filled.AutoStories, "Open") {
                onDismiss(); onOpen(bookmark.id)
            }
            if (onFavourite != null) {
                val fav = bookmark.favourited
                ActionRow(
                    if (fav) Icons.Filled.Star else Icons.Filled.StarBorder,
                    if (fav) "Unfavourite" else "Favourite",
                ) { onDismiss(); onFavourite(bookmark) }
            }
            if (onArchive != null) {
                ActionRow(
                    if (archiveIsRestore) Icons.Filled.Unarchive else Icons.Filled.Archive,
                    if (archiveIsRestore) "Move to inbox" else "Archive",
                ) { onDismiss(); onArchive(bookmark) }
            }
            val url = bookmark.url
            if (!url.isNullOrBlank()) {
                ActionRow(Icons.Filled.IosShare, "Share link") {
                    onDismiss(); shareText(context, url, bookmark.displayTitle)
                }
                ActionRow(Icons.AutoMirrored.Filled.OpenInNew, "Open original") {
                    onDismiss()
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
