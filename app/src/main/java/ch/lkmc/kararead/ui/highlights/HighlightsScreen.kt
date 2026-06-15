package ch.lkmc.kararead.ui.highlights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.kararead.ui.components.LoadingState
import ch.lkmc.kararead.ui.components.MessageState
import ch.lkmc.kararead.ui.components.shareText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsScreen(
    onOpenReader: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: HighlightsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Highlights") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.groups.isNotEmpty()) {
                        IconButton(onClick = {
                            viewModel.exportMarkdown()?.let { shareText(context, it, "Highlights") }
                        }) {
                            Icon(Icons.Filled.IosShare, contentDescription = "Export all highlights")
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.groups.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when {
                state.loading && state.groups.isEmpty() -> LoadingState()
                state.error != null && state.groups.isEmpty() -> MessageState(
                    title = "Couldn't load highlights",
                    subtitle = state.error,
                    emoji = "⚠️",
                    actionLabel = "Retry",
                    onAction = viewModel::refresh,
                )
                state.groups.isEmpty() -> MessageState(
                    title = "No highlights yet",
                    subtitle = "Select text while reading and tap Highlight to save a quote here.",
                    emoji = "✍️",
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    state.groups.forEach { group ->
                        item(key = "h-${group.bookmarkId}") {
                            ArticleHeader(
                                title = group.title,
                                count = group.highlights.size,
                                onClick = { onOpenReader(group.bookmarkId) },
                            )
                        }
                        items(
                            group.highlights,
                            key = { it.id },
                        ) { hl ->
                            HighlightRow(
                                text = hl.text,
                                note = hl.note,
                                onClick = { onOpenReader(group.bookmarkId) },
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleHeader(title: String, count: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (count == 1) "1 highlight" else "$count highlights",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HighlightRow(text: String?, note: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
    ) {
        Text(
            text ?: "(highlight)",
            style = MaterialTheme.typography.bodyMedium,
        )
        note?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
