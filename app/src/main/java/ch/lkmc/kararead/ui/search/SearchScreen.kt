package ch.lkmc.kararead.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import ch.lkmc.kararead.ui.components.BookmarkList
import ch.lkmc.kararead.ui.components.MessageState

@Composable
fun SearchScreen(
    onOpenReader: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val results = viewModel.results.collectAsLazyPagingItems()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search your library…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
        )

        if (query.trim().length < 2) {
            MessageState(
                title = "Search your reading",
                subtitle = "Find articles by title, content, #tag, or qualifiers like is:fav.",
                emoji = "🔍",
            )
        } else {
            Text(
                "Tip: try #tag, is:fav, or url:example.com",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            BookmarkList(
                items = results,
                progressFor = { progress[it] ?: 0f },
                onOpen = onOpenReader,
                enableSwipe = false,
                emptyTitle = "No results",
                emptySubtitle = "Try a different search.",
                emptyEmoji = "🤔",
            )
        }
    }
}
