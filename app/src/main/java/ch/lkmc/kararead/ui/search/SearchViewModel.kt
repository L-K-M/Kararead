package ch.lkmc.kararead.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import ch.lkmc.kararead.data.model.Bookmark
import ch.lkmc.kararead.data.model.BookmarkSource
import ch.lkmc.kararead.data.model.QueueSort
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
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: KarakeepRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val progress: StateFlow<Map<String, Float>> =
        repository.allProgress().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

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
