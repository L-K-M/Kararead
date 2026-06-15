package ch.lkmc.kararead.ui.tags

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import ch.lkmc.kararead.data.model.Bookmark
import ch.lkmc.kararead.data.model.BookmarkSource
import ch.lkmc.kararead.data.model.QueueSort
import ch.lkmc.kararead.data.repository.KarakeepRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagBookmarksViewModel @Inject constructor(
    private val repository: KarakeepRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val tagId: String = savedStateHandle.get<String>("tagId").orEmpty()
    val tagName: String = Uri.decode(savedStateHandle.get<String>("tagName").orEmpty())

    private val hiddenIds = MutableStateFlow<Set<String>>(emptySet())

    val progress: StateFlow<Map<String, Float>> =
        repository.allProgress().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val source = BookmarkSource.TagSource(tagId, tagName)

    private val pagingFlow: Flow<PagingData<Bookmark>> =
        repository.bookmarkPager(source, QueueSort.NEWEST).cachedIn(viewModelScope)

    val bookmarks: Flow<PagingData<Bookmark>> =
        combine(pagingFlow, hiddenIds) { data, hidden -> data.filter { it.id !in hidden } }

    fun favourite(bookmark: Bookmark) {
        viewModelScope.launch { runCatching { repository.setFavourited(bookmark.id, !bookmark.favourited) } }
    }

    fun archive(bookmark: Bookmark) {
        hiddenIds.update { it + bookmark.id }
        viewModelScope.launch {
            runCatching { repository.setArchived(bookmark.id, true) }
                .onFailure { hiddenIds.update { ids -> ids - bookmark.id } }
        }
    }
}
