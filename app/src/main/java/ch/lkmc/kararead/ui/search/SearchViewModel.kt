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
import javax.inject.Inject

/** State of the browse-by-tag section in the empty search view. */
data class TagsUi(
    val loading: Boolean = true,
    val tags: List<Tag> = emptyList(),
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

    init {
        loadTags()
    }

    fun loadTags() {
        _tags.value = _tags.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { repository.getTags() }
                .onSuccess { list ->
                    _tags.value = TagsUi(loading = false, tags = list, error = null)
                }
                .onFailure { e ->
                    _tags.value = TagsUi(
                        loading = false,
                        tags = emptyList(),
                        error = e.message ?: "Couldn't load tags.",
                    )
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
}
