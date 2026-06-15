package ch.lkmc.kararead.ui.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.kararead.ui.components.LoadingState
import ch.lkmc.kararead.ui.components.MessageState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(
    onOpenTag: (id: String, name: String) -> Unit,
    viewModel: TagsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Tags") }) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> LoadingState()
                state.error != null -> MessageState(
                    title = "Couldn't load tags",
                    subtitle = state.error,
                    emoji = "⚠️",
                    actionLabel = "Retry",
                    onAction = viewModel::refresh,
                )
                state.tags.isEmpty() -> MessageState(
                    title = "No tags yet",
                    subtitle = "Tag bookmarks in Karakeep and they'll show up here.",
                    emoji = "🏷️",
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.tags, key = { it.id }) { tag ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenTag(tag.id, tag.name) }
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("#", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                tag.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                tag.count.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }
}
