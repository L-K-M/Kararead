package ch.lkmc.kararead.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.kararead.data.model.Bookmark
import ch.lkmc.kararead.data.model.KarakeepList

/**
 * A bottom sheet for filing an article into (or out of) the user's manual
 * Karakeep lists (H7). Self-contained: it brings its own [ListPickerViewModel],
 * so any host only needs to decide when to show it. Toggling is optimistic and
 * reverts with a message on failure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToListSheet(
    bookmark: Bookmark,
    onDismiss: () -> Unit,
    viewModel: ListPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(bookmark.id) { viewModel.load(bookmark.id) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.messages.collect {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("Add to list", style = MaterialTheme.typography.titleMedium)
                Text(
                    bookmark.displayTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            when {
                state.loading -> CenteredNote { CircularProgressIndicator(Modifier.size(28.dp)) }
                state.error != null -> CenteredNote {
                    Text(
                        state.error ?: "Couldn't load lists",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.lists.isEmpty() -> CenteredNote {
                    Text(
                        "No manual lists yet — create one in Karakeep.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> state.lists.forEach { list ->
                    ListToggleRow(
                        list = list,
                        checked = list.id in state.memberOf,
                        onToggle = { viewModel.toggle(bookmark.id, list) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ListToggleRow(list: KarakeepList, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (list.icon.isNotBlank()) {
            Text(list.icon, style = MaterialTheme.typography.titleMedium)
        } else {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            list.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        // The whole row toggles; the checkbox is a status indicator, not a
        // separate tap target (so onCheckedChange is null and the row owns it).
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun CenteredNote(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
