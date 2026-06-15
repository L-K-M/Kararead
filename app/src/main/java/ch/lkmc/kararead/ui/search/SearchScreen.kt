package ch.lkmc.kararead.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import ch.lkmc.kararead.ui.components.BookmarkList

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onOpenReader: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
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
            // No query yet: offer browsing by tag.
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    "Search by title, content, #tag, or qualifiers like is:fav.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Text(
                    "Browse by tag",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
                when {
                    tags.loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                "Loading tags…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                    tags.error != null -> {
                        Text(
                            "Couldn't load tags: ${tags.error}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = viewModel::loadTags) { Text("Retry") }
                    }
                    tags.tags.isEmpty() -> {
                        Text(
                            "No tags yet. Add tags to bookmarks in Karakeep and they'll appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            tags.tags.forEach { tag ->
                                AssistChip(
                                    onClick = { viewModel.onQueryChange(tagQuery(tag.name)) },
                                    label = { Text("#${tag.name}") },
                                )
                            }
                        }
                    }
                }
            }
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

/** A Karakeep search expression for a tag; quote multi-word names. */
private fun tagQuery(name: String): String =
    if (name.any { it.isWhitespace() }) "#\"$name\"" else "#$name"
