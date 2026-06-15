package ch.lkmc.kararead.ui.highlights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.kararead.data.model.Bookmark
import ch.lkmc.kararead.data.model.Highlight
import ch.lkmc.kararead.data.repository.KarakeepRepository
import ch.lkmc.kararead.util.HighlightCollection
import ch.lkmc.kararead.util.highlightsToMarkdown
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Highlights for one article, with the article's metadata for display. */
data class HighlightGroup(
    val bookmarkId: String,
    val title: String,
    val url: String?,
    val highlights: List<Highlight>,
)

data class HighlightsUiState(
    val loading: Boolean = true,
    val groups: List<HighlightGroup> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class HighlightsViewModel @Inject constructor(
    private val repository: KarakeepRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HighlightsUiState())
    val state: StateFlow<HighlightsUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { loadGroups() }
                .onSuccess { groups -> _state.value = HighlightsUiState(loading = false, groups = groups) }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = it.message ?: "Couldn't load highlights.",
                    )
                }
        }
    }

    private suspend fun loadGroups(): List<HighlightGroup> = coroutineScope {
        val all = repository.getAllHighlights()
        // Preserve the server's order (newest first) when grouping by article.
        val byBookmark = all.groupBy { it.bookmarkId }
        val metas: Map<String, Bookmark?> = byBookmark.keys
            .map { id -> async { id to runCatching { repository.getBookmarkMeta(id) }.getOrNull() } }
            .awaitAll()
            .toMap()
        byBookmark.map { (bookmarkId, highlights) ->
            val meta = metas[bookmarkId]
            HighlightGroup(
                bookmarkId = bookmarkId,
                title = meta?.displayTitle ?: "Untitled",
                url = meta?.url,
                highlights = highlights.sortedBy { it.startOffset },
            )
        }
    }

    /** All highlights rendered as a single Markdown document, or null if empty. */
    fun exportMarkdown(): String? {
        val groups = _state.value.groups
        if (groups.isEmpty()) return null
        val md = highlightsToMarkdown(
            groups.map { HighlightCollection(it.title, it.url, it.highlights) },
        )
        return md.ifBlank { null }
    }
}
