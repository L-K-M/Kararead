package ch.lkmc.kararead.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import ch.lkmc.kararead.data.model.QueueSort
import ch.lkmc.kararead.ui.components.BookmarkList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenReader: (String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val items = viewModel.bookmarks.collectAsLazyPagingItems()
    val snackbarHost = remember { SnackbarHostState() }
    var sortMenuOpen by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is LibraryEvent.Archived -> {
                    val result = snackbarHost.showSnackbar(
                        message = "Archived",
                        actionLabel = "Undo",
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.undoArchive(event.bookmark)
                    }
                }
                is LibraryEvent.Message -> snackbarHost.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(titleFor(ui)) },
                actions = {
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
            BookmarkList(
                items = items,
                progressFor = { progress[it] ?: 0f },
                onOpen = onOpenReader,
                onArchive = viewModel::archive,
                onFavourite = viewModel::favourite,
                emptyTitle = emptyTitleFor(ui),
                emptySubtitle = emptySubtitleFor(ui),
                emptyEmoji = if (ui.tab == LibraryTab.INBOX || ui.tab == LibraryTab.READ_LATER) "✨" else "📭",
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
    LibraryTab.INBOX, LibraryTab.READ_LATER -> "Save something in Karakeep and it'll appear here."
    LibraryTab.FAVOURITES -> "Tap the star on an article to keep it here."
    LibraryTab.ARCHIVE -> "Articles you finish will land here."
}
