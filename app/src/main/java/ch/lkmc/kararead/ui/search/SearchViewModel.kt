package ch.lkmc.kararead.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import ch.lkmc.kararead.data.model.Bookmark
import ch.lkmc.kararead.data.model.BookmarkSource
import ch.lkmc.kararead.data.model.QueueSort
import ch.lkmc.kararead.data.model.Tag
import ch.lkmc.kararead.data.repository.KarakeepRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/** State of the browse-by-tag section in the empty search view. */
data class TagsUi(
    val loading: Boolean = true,
    val tags: List<Tag> = emptyList(),
    val total: Int = 0,
    val error: String? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: KarakeepRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _tags = MutableStateFlow(TagsUi())
    /** Tags for browsing, shown when no query is entered. */
    val tags: StateFlow<TagsUi> = _tags

    val progress: StateFlow<Map<String, Float>> =
        repository.allProgress().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val readingTimes: StateFlow<Map<String, Int>> =
        repository.cachedReadingTimes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        loadTags()
    }

    fun loadTags() {
        _tags.value = _tags.value.copy(loading = true, error = null)
        viewModelScope.launch {
            // Bound the wait so a non-returning request can't leave the UI
            // spinning forever; surface a concrete error so failures are visible.
            runCatching { withTimeout(20_000) { repository.getTags() } }
                .onSuccess { list ->
                    // Cap the chip cloud: rendering every tag (libraries can have
                    // thousands) in a non-lazy FlowRow blows up layout/draw and
                    // OOMs. getTags() is sorted most-used first, so take the top N.
                    _tags.value = TagsUi(
                        loading = false,
                        tags = list.take(MAX_TAG_CHIPS),
                        total = list.size,
                        error = null,
                    )
                }
                .onFailure { e ->
                    android.util.Log.w("SearchViewModel", "Loading tags failed", e)
                    val reason = when (e) {
                        is kotlinx.coroutines.TimeoutCancellationException ->
                            "timed out after 20s"
                        else -> e.message ?: e::class.simpleName ?: "unknown error"
                    }
                    _tags.value = TagsUi(loading = false, tags = emptyList(), error = reason)
                }
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val results: Flow<PagingData<Bookmark>> =
        _query
            .debounce(300)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                val trimmed = q.trim()
                if (trimmed.length < 2) emptyFlow()
                else repository.bookmarkPager(BookmarkSource.SearchSource(trimmed), QueueSort.NEWEST)
            }
            .cachedIn(viewModelScope)

    fun onQueryChange(value: String) {
        _query.value = value
    }

    private companion object {
        const val MAX_TAG_CHIPS = 50
    }
}
