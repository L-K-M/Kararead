package ch.lkmc.kararead.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import ch.lkmc.kararead.data.model.QueueSort
import ch.lkmc.kararead.ui.components.BookmarkList
import ch.lkmc.kararead.ui.components.MessageStackHost
import ch.lkmc.kararead.ui.components.MessageStackState
import coil.compose.AsyncImage
import coil.request.ImageRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenReader: (String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val cachedIds by viewModel.cachedIds.collectAsStateWithLifecycle()
    val readingTimes by viewModel.readingTimes.collectAsStateWithLifecycle()
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val offlineBookmarks by viewModel.offlineBookmarks.collectAsStateWithLifecycle()
    val items = viewModel.bookmarks.collectAsLazyPagingItems()
    val messageStack = remember { MessageStackState() }
    var sortMenuOpen by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is LibraryEvent.Archived -> messageStack.show(
                    text = "Archived",
                    actionLabel = "Undo",
                    durationMillis = MessageStackState.ACTION_MILLIS,
                    onAction = { viewModel.undoArchive(event.bookmark) },
                )
                is LibraryEvent.Message -> messageStack.show(event.text)
                is LibraryEvent.Open -> onOpenReader(event.bookmarkId)
            }
        }
    }

    Scaffold(
        snackbarHost = { MessageStackHost(messageStack) },
        topBar = {
            TopAppBar(
                title = { Text(titleFor(ui)) },
                actions = {
                    if (items.itemCount > 0) {
                        IconButton(onClick = {
                            val index = (0 until items.itemCount).random()
                            items[index]?.let { onOpenReader(it.id) }
                        }) {
                            Icon(Icons.Filled.Shuffle, contentDescription = "Surprise me")
                        }
                    }
                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                    }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Newest first") },
                            onClick = { viewModel.setSort(QueueSort.NEWEST); sortMenuOpen = false },
                            trailingIcon = {
                                if (ui.sort == QueueSort.NEWEST) {
                                    Icon(Icons.Filled.Check, null)
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Oldest first") },
                            onClick = { viewModel.setSort(QueueSort.OLDEST); sortMenuOpen = false },
                            trailingIcon = {
                                if (ui.sort == QueueSort.OLDEST) {
                                    Icon(Icons.Filled.Check, null)
                                }
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FilterRow(ui = ui, onSelect = viewModel::selectTab)
            if (recents.isNotEmpty()) {
                RecentsStrip(recents = recents, onOpen = onOpenReader)
            }
            BookmarkList(
                items = items,
                progressFor = { progress[it] ?: 0f },
                isCached = { it in cachedIds },
                readingTimeFor = { readingTimes[it] },
                offlineFallback = offlineBookmarks,
                onOpen = onOpenReader,
                onArchive = viewModel::archive,
                onFavourite = viewModel::favourite,
                archiveIsRestore = ui.tab == LibraryTab.ARCHIVE,
                emptyTitle = emptyTitleFor(ui),
                emptySubtitle = emptySubtitleFor(ui),
                emptyEmoji = if (ui.tab == LibraryTab.INBOX || ui.tab == LibraryTab.READ_LATER) "✨" else "📭",
                emptyActionLabel = "🎲 Surprise me".takeIf {
                    ui.tab == LibraryTab.INBOX || ui.tab == LibraryTab.READ_LATER
                },
                onEmptyAction = viewModel::surpriseMe.takeIf {
                    ui.tab == LibraryTab.INBOX || ui.tab == LibraryTab.READ_LATER
                },
            )
        }
    }
}

@Composable
private fun RecentsStrip(
    recents: List<ch.lkmc.kararead.data.model.RecentArticle>,
    onOpen: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(recents, key = { it.id }) { article ->
            RecentThumb(article = article, onClick = { onOpen(article.id) })
        }
    }
}

@Composable
private fun RecentThumb(
    article: ch.lkmc.kararead.data.model.RecentArticle,
    onClick: () -> Unit,
) {
    val hasCover = !article.imageUrl.isNullOrBlank()
    // Cover-less articles get a stable colourful tile (keyed off the id) with the
    // title inside, so they stay recognisable instead of being blank grey boxes.
    val accent = remember(article.id) { ch.lkmc.kararead.ui.components.accentColorFor(article.id) }
    Box(
        Modifier
            .width(112.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (hasCover) MaterialTheme.colorScheme.surfaceVariant else accent)
            .clickable(onClick = onClick),
    ) {
        if (hasCover) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(article.imageUrl).crossfade(true).build(),
                contentDescription = article.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            // No cover: show the title on the colourful tile.
            Text(
                text = article.title,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .matchParentSize()
                    .padding(8.dp),
            )
        }
        // Thin reading-progress bar pinned to the bottom edge.
        if (article.fraction > 0.01f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(article.fraction.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun FilterRow(ui: LibraryUiState, onSelect: (LibraryTab) -> Unit) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ui.readLaterName != null) {
            FilterChip(
                selected = ui.tab == LibraryTab.READ_LATER,
                onClick = { onSelect(LibraryTab.READ_LATER) },
                label = { Text(ui.readLaterName) },
            )
        }
        FilterChip(
            selected = ui.tab == LibraryTab.INBOX,
            onClick = { onSelect(LibraryTab.INBOX) },
            label = { Text("Inbox") },
        )
        FilterChip(
            selected = ui.tab == LibraryTab.FAVOURITES,
            onClick = { onSelect(LibraryTab.FAVOURITES) },
            label = { Text("Favourites") },
        )
        FilterChip(
            selected = ui.tab == LibraryTab.ARCHIVE,
            onClick = { onSelect(LibraryTab.ARCHIVE) },
            label = { Text("Archive") },
        )
    }
}

private fun titleFor(ui: LibraryUiState): String = when (ui.tab) {
    LibraryTab.INBOX -> "Inbox"
    LibraryTab.FAVOURITES -> "Favourites"
    LibraryTab.ARCHIVE -> "Archive"
    LibraryTab.READ_LATER -> ui.readLaterName ?: "Read later"
}

private fun emptyTitleFor(ui: LibraryUiState): String = when (ui.tab) {
    LibraryTab.INBOX, LibraryTab.READ_LATER -> "You're all caught up"
    LibraryTab.FAVOURITES -> "No favourites yet"
    LibraryTab.ARCHIVE -> "Nothing archived"
}

private fun emptySubtitleFor(ui: LibraryUiState): String? = when (ui.tab) {
    LibraryTab.INBOX, LibraryTab.READ_LATER -> {
        val base = "Save something in Karakeep and it'll appear here."
        if (ui.currentStreakDays > 0) {
            "$base\n🔥 ${ui.currentStreakDays}-day reading streak — keep it going!"
        } else {
            base
        }
    }
    LibraryTab.FAVOURITES -> "Tap the star on an article to keep it here."
    LibraryTab.ARCHIVE -> "Articles you finish will land here."
}
