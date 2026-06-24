package ch.lkmc.kararead.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import ch.lkmc.kararead.data.model.Bookmark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
    isCached: (String) -> Boolean = { false },
    readingTimeFor: (String) -> Int? = { null },
    offlineFallback: List<Bookmark> = emptyList(),
    enableSwipe: Boolean = true,
    onArchive: ((Bookmark) -> Unit)? = null,
    onFavourite: ((Bookmark) -> Unit)? = null,
    archiveIsRestore: Boolean = false,
    emptyTitle: String = "Nothing here yet",
    emptySubtitle: String? = null,
    emptyEmoji: String? = "✨",
    emptyActionLabel: String? = null,
    onEmptyAction: (() -> Unit)? = null,
) {
    val refreshing = items.loadState.refresh is LoadState.Loading
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()
    val haptics = LocalHapticFeedback.current

    // Long-pressing a row opens an action sheet for that article (a discoverable
    // alternative to the swipe gestures, and the only way to reach these actions
    // where swipe is disabled, e.g. search).
    var actionsFor by remember { mutableStateOf<Bookmark?>(null) }
    val onLongPress: (Bookmark) -> Unit = { bookmark ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        actionsFor = bookmark
    }
    actionsFor?.let { bookmark ->
        ArticleActionsSheet(
            bookmark = bookmark,
            onDismiss = { actionsFor = null },
            onOpen = onOpen,
            onArchive = onArchive,
            onFavourite = onFavourite,
            archiveIsRestore = archiveIsRestore,
        )
    }

    // Haptic detent the instant the pull passes the refresh threshold, so you
    // can feel that releasing will refresh — before letting go. Re-arms if you
    // ease back under the threshold and past it again.
    LaunchedEffect(pullState) {
        snapshotFlow { pullState.distanceFraction >= 1f }.collect { pastThreshold ->
            if (pastThreshold) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    // Reveal the rows a pull-to-refresh prepends. Two things hide them: Paging
    // applies the refreshed page a beat *after* the loading state clears (a
    // race), and the LazyColumn keeps its old scroll anchor, so the new rows
    // land above the fold. So: remember the top item when a refresh starts, and
    // once it finishes wait (briefly) for the top item to actually change before
    // scrolling up to it. If nothing new arrives, bail without yanking the list.
    var wasRefreshing by remember { mutableStateOf(false) }
    var topIdBeforeRefresh by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            if (!wasRefreshing) topIdBeforeRefresh = items.itemSnapshotList.firstOrNull()?.id
            wasRefreshing = true
            return@LaunchedEffect
        }
        if (!wasRefreshing) return@LaunchedEffect
        wasRefreshing = false
        if (items.itemCount == 0) return@LaunchedEffect
        val landed = withTimeoutOrNull(2000L) {
            snapshotFlow { items.itemSnapshotList.firstOrNull()?.id }
                .first { it != null && it != topIdBeforeRefresh }
        }
        if (landed != null) listState.animateScrollToItem(0)
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { items.refresh() },
        state = pullState,
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            items.loadState.refresh is LoadState.Error -> {
                // Offline: rather than a full-screen error, fall back to whatever
                // articles are downloaded, under a quiet "you're offline" banner.
                // Only show the hard error when there's nothing cached to read.
                if (offlineFallback.isNotEmpty()) {
                    OfflineFallbackList(
                        bookmarks = offlineFallback,
                        progressFor = progressFor,
                        isCached = isCached,
                        readingTimeFor = readingTimeFor,
                        onOpen = onOpen,
                        onRetry = { items.retry() },
                        listState = listState,
                        enableSwipe = enableSwipe,
                        onArchive = onArchive,
                        onFavourite = onFavourite,
                        archiveIsRestore = archiveIsRestore,
                        onLongPress = onLongPress,
                    )
                } else {
                    val e = (items.loadState.refresh as LoadState.Error).error
                    MessageState(
                        title = "Couldn't load",
                        subtitle = e.message ?: "Check your connection and try again.",
                        emoji = "⚠️",
                        actionLabel = "Retry",
                        onAction = { items.retry() },
                    )
                }
            }

            !refreshing && items.itemCount == 0 -> {
                MessageState(
                    title = emptyTitle,
                    subtitle = emptySubtitle,
                    emoji = emptyEmoji,
                    actionLabel = emptyActionLabel,
                    onAction = onEmptyAction,
                )
            }

            else -> {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(
                        count = items.itemCount,
                        key = items.itemKey { it.id },
                    ) { index ->
                        val bookmark = items[index] ?: return@items
                        // animateItem gives archived/removed rows a fade-out and
                        // slides the rest up, instead of an abrupt disappearance.
                        Column(Modifier.animateItem()) {
                            if (enableSwipe) {
                                SwipeRow(
                                    bookmark = bookmark,
                                    onArchive = onArchive,
                                    onFavourite = onFavourite,
                                    archiveIsRestore = archiveIsRestore,
                                ) {
                                    BookmarkCard(
                                        bookmark = bookmark,
                                        progress = progressFor(bookmark.id),
                                        offline = isCached(bookmark.id),
                                        readingTimeOverride = readingTimeFor(bookmark.id),
                                        onClick = { onOpen(bookmark.id) },
                                        onLongClick = { onLongPress(bookmark) },
                                    )
                                }
                            } else {
                                BookmarkCard(
                                    bookmark = bookmark,
                                    progress = progressFor(bookmark.id),
                                    offline = isCached(bookmark.id),
                                    readingTimeOverride = readingTimeFor(bookmark.id),
                                    onClick = { onOpen(bookmark.id) },
                                    onLongClick = { onLongPress(bookmark) },
                                )
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
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

/**
 * List of downloaded articles shown when the live listing can't be fetched.
 * Tapping a row opens it from cache; a quiet banner up top notes the offline
 * state and offers a retry. Swipe-to-archive/favourite still work: the change is
 * applied to the cache straight away and queued (an outbox) to sync once we're
 * back online, so the list behaves the same offline as on.
 */
@Composable
private fun OfflineFallbackList(
    bookmarks: List<Bookmark>,
    progressFor: (String) -> Float,
    isCached: (String) -> Boolean,
    readingTimeFor: (String) -> Int?,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    enableSwipe: Boolean,
    onArchive: ((Bookmark) -> Unit)?,
    onFavourite: ((Bookmark) -> Unit)?,
    archiveIsRestore: Boolean,
    onLongPress: (Bookmark) -> Unit,
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item { OfflineBanner(onRetry = onRetry) }
        items(bookmarks, key = { it.id }) { bookmark ->
            // animateItem slides the row away when an offline archive/favourite
            // drops it from the list, instead of an abrupt disappearance.
            Column(Modifier.animateItem()) {
                if (enableSwipe) {
                    SwipeRow(
                        bookmark = bookmark,
                        onArchive = onArchive,
                        onFavourite = onFavourite,
                        archiveIsRestore = archiveIsRestore,
                    ) {
                        BookmarkCard(
                            bookmark = bookmark,
                            progress = progressFor(bookmark.id),
                            offline = isCached(bookmark.id),
                            readingTimeOverride = readingTimeFor(bookmark.id),
                            onClick = { onOpen(bookmark.id) },
                        )
                    }
                } else {
                    BookmarkCard(
                        bookmark = bookmark,
                        progress = progressFor(bookmark.id),
                        offline = isCached(bookmark.id),
                        readingTimeOverride = readingTimeFor(bookmark.id),
                        onClick = { onOpen(bookmark.id) },
                    )
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun OfflineBanner(onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "You're offline — showing downloaded articles",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Retry",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onRetry),
        )
    }
}

@Composable
private fun SwipeRow(
    bookmark: Bookmark,
    onArchive: ((Bookmark) -> Unit)?,
    onFavourite: ((Bookmark) -> Unit)?,
    archiveIsRestore: Boolean,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { onArchive?.invoke(bookmark); false }
                SwipeToDismissBoxValue.StartToEnd -> { onFavourite?.invoke(bookmark); false }
                else -> false
            }
        },
    )
    // Haptic detent: tick the instant the swipe passes the trigger threshold
    // (targetValue leaves Settled), so you feel that releasing will fire the
    // action — felt mid-drag, before letting go, rather than after.
    LaunchedEffect(state) {
        snapshotFlow { state.targetValue }.collect { target ->
            if (target != SwipeToDismissBoxValue.Settled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = onFavourite != null,
        enableDismissFromEndToStart = onArchive != null,
        backgroundContent = {
            SwipeBackground(state.dismissDirection, bookmark.favourited, archiveIsRestore)
        },
        content = { content() },
    )
}

@Composable
private fun SwipeBackground(
    direction: SwipeToDismissBoxValue,
    favourited: Boolean,
    archiveIsRestore: Boolean,
) {
    val color = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.tertiaryContainer
        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val icon = when (direction) {
        SwipeToDismissBoxValue.EndToStart ->
            if (archiveIsRestore) Icons.Filled.Unarchive else Icons.Filled.Archive
        else -> Icons.Filled.Star
    }
    val label = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> if (archiveIsRestore) "Restore" else "Archive"
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
