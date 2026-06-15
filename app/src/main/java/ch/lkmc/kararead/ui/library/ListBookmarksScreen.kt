package ch.lkmc.kararead.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import ch.lkmc.kararead.ui.components.BookmarkList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListBookmarksScreen(
    listId: String,
    listName: String,
    onOpenReader: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ListBookmarksViewModel = hiltViewModel(),
) {
    val items = viewModel.bookmarks.collectAsLazyPagingItems()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val readingTimes by viewModel.readingTimes.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listName.ifBlank { "List" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        BookmarkList(
            items = items,
            progressFor = { progress[it] ?: 0f },
            readingTimeFor = { readingTimes[it] },
            onOpen = onOpenReader,
            onArchive = viewModel::archive,
            onFavourite = viewModel::favourite,
            emptyTitle = "This list is empty",
            emptySubtitle = "Add bookmarks to it in Karakeep.",
            emptyEmoji = "📭",
            modifier = Modifier.padding(padding).fillMaxSize(),
        )
    }
}
