package ch.lkmc.kararead.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import ch.lkmc.kararead.data.model.Bookmark
import kotlinx.coroutines.launch

/**
 * Shared, reusable list of bookmarks backed by Paging 3, with swipe actions,
 * pull-to-refresh and load-state handling. Used by Library, List detail and
 * Search.
 */
@Composable
fun BookmarkList(
    items: LazyPagingItems<Bookmark>,
    progressFor: (String) -> Float,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    enableSwipe: Boolean = true,
    onArchive: ((Bookmark) -> Unit)? = null,
    onFavourite: ((Bookmark) -> Unit)? = null,
    emptyTitle: String = "Nothing here yet",
    emptySubtitle: String? = null,
    emptyEmoji: String? = "✨",
) {
    val refreshing = items.loadState.refresh is LoadState.Loading
    val scope = rememberCoroutineScope()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { items.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            items.loadState.refresh is LoadState.Error -> {
                val e = (items.loadState.refresh as LoadState.Error).error
                MessageState(
                    title = "Couldn't load",
                    subtitle = e.message ?: "Check your connection and try again.",
                    emoji = "⚠️",
                    actionLabel = "Retry",
                    onAction = { items.retry() },
                )
            }

            !refreshing && items.itemCount == 0 -> {
                MessageState(title = emptyTitle, subtitle = emptySubtitle, emoji = emptyEmoji)
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(
                        count = items.itemCount,
                        key = items.itemKey { it.id },
                    ) { index ->
                        val bookmark = items[index] ?: return@items
                        if (enableSwipe) {
                            SwipeRow(
                                bookmark = bookmark,
                                onArchive = onArchive,
                                onFavourite = onFavourite,
                            ) {
                                BookmarkCard(
                                    bookmark = bookmark,
                                    progress = progressFor(bookmark.id),
                                    onClick = { onOpen(bookmark.id) },
                                )
                            }
                        } else {
                            BookmarkCard(
                                bookmark = bookmark,
                                progress = progressFor(bookmark.id),
                                onClick = { onOpen(bookmark.id) },
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }

                    if (items.loadState.append is LoadState.Loading) {
                        item { LoadingRow() }
                    }
                    if (items.loadState.append is LoadState.Error) {
                        item {
                            Text(
                                "Tap to retry loading more",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

// Stable key extension for LazyPagingItems
private fun LazyPagingItems<Bookmark>.itemKey(key: (Bookmark) -> Any): (Int) -> Any = { index ->
    this[index]?.let(key) ?: "placeholder-$index"
}

@Composable
private fun SwipeRow(
    bookmark: Bookmark,
    onArchive: ((Bookmark) -> Unit)?,
    onFavourite: ((Bookmark) -> Unit)?,
    content: @Composable () -> Unit,
) {
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { onArchive?.invoke(bookmark); false }
                SwipeToDismissBoxValue.StartToEnd -> { onFavourite?.invoke(bookmark); false }
                else -> false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = onFavourite != null,
        enableDismissFromEndToStart = onArchive != null,
        backgroundContent = { SwipeBackground(state.dismissDirection, bookmark.favourited) },
        content = { content() },
    )
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, favourited: Boolean) {
    val color = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.tertiaryContainer
        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val icon = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> Icons.Filled.Archive
        else -> Icons.Filled.Star
    }
    val label = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> "Archive"
        SwipeToDismissBoxValue.StartToEnd -> if (favourited) "Unfavourite" else "Favourite"
        else -> ""
    }
    val alignment = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 28.dp),
        contentAlignment = alignment,
    ) {
        if (direction != SwipeToDismissBoxValue.Settled) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.padding(8.dp),
            strokeWidth = 2.dp,
        )
    }
}
