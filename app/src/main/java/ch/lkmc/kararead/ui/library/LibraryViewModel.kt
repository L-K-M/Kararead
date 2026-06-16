package ch.lkmc.kararead.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import ch.lkmc.kararead.data.model.Bookmark
import ch.lkmc.kararead.data.model.BookmarkSource
import ch.lkmc.kararead.data.model.QueueSort
import ch.lkmc.kararead.data.model.RecentArticle
import ch.lkmc.kararead.data.prefs.SettingsRepository
import ch.lkmc.kararead.data.repository.KarakeepRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab { INBOX, FAVOURITES, ARCHIVE, READ_LATER }

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.INBOX,
    val sort: QueueSort = QueueSort.NEWEST,
    val readLaterName: String? = null,
    val currentStreakDays: Int = 0,
)

sealed interface LibraryEvent {
    data class Archived(val bookmark: Bookmark) : LibraryEvent
    data class Message(val text: String) : LibraryEvent
    data class Open(val bookmarkId: String) : LibraryEvent
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: KarakeepRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val tab = MutableStateFlow(LibraryTab.INBOX)
    private val sort = MutableStateFlow(QueueSort.NEWEST)
    private val readLater = MutableStateFlow<Pair<String, String>?>(null)
    private val hiddenIds = MutableStateFlow<Set<String>>(emptySet())

    private val events = Channel<LibraryEvent>(Channel.BUFFERED)
    val eventFlow: Flow<LibraryEvent> = events.receiveAsFlow()

    val uiState: StateFlow<LibraryUiState> =
        combine(tab, sort, readLater, repository.readingStats()) { t, s, rl, stats ->
            LibraryUiState(
                tab = t,
                sort = s,
                readLaterName = rl?.second,
                currentStreakDays = stats.currentStreakDays,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    val progress: StateFlow<Map<String, Float>> =
        repository.allProgress().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val cachedIds: StateFlow<Set<String>> =
        repository.cachedIds().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val readingTimes: StateFlow<Map<String, Int>> =
        repository.cachedReadingTimes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val recents: StateFlow<List<RecentArticle>> =
        repository.recentlyOpened().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val pagingFlow: Flow<PagingData<Bookmark>> =
        combine(tab, sort, readLater) { t, s, rl -> Triple(t, s, rl) }
            .flatMapLatest { (t, s, rl) ->
                val source = when (t) {
                    LibraryTab.INBOX -> BookmarkSource.Inbox
                    LibraryTab.FAVOURITES -> BookmarkSource.Favourites
                    LibraryTab.ARCHIVE -> BookmarkSource.Archive
                    LibraryTab.READ_LATER ->
                        rl?.let { BookmarkSource.ListSource(it.first, it.second) }
                            ?: BookmarkSource.Inbox
                }
                repository.bookmarkPager(source, s)
            }
            .cachedIn(viewModelScope)

    val bookmarks: Flow<PagingData<Bookmark>> =
        combine(pagingFlow, hiddenIds) { data, hidden ->
            data.filter { it.id !in hidden }
        }

    init {
        viewModelScope.launch {
            settings.queueSort.collect { sort.value = it }
        }
        viewModelScope.launch {
            settings.readLaterList.collect { rl ->
                readLater.value = rl
                // Default to the read-later list as the home tab if configured.
                if (rl != null && tab.value == LibraryTab.INBOX) tab.value = LibraryTab.READ_LATER
            }
        }
        // Hide articles archived elsewhere (e.g. finished from the reader's
        // "Done · Next") from the unread reading queues, so a finished article
        // doesn't linger in the cached list when you come back. Only the Inbox /
        // Read-later queues are unread-only; Favourites and Archive can legitimately
        // contain archived items, so leave those untouched.
        viewModelScope.launch {
            repository.archivedIds.collect { id ->
                if (tab.value == LibraryTab.INBOX || tab.value == LibraryTab.READ_LATER) {
                    hiddenIds.update { it + id }
                }
            }
        }
    }

    fun selectTab(newTab: LibraryTab) {
        if (newTab == tab.value) return
        // Optimistic hides are scoped to the queue they happened in; a fresh
        // queue starts with nothing hidden (otherwise an item archived in the
        // Inbox would stay invisible in the Archive tab).
        clearHidden()
        tab.value = newTab
    }

    fun setSort(newSort: QueueSort) {
        viewModelScope.launch { settings.setQueueSort(newSort) }
    }

    fun archive(bookmark: Bookmark) {
        // Toggle: in the Archive tab we un-archive; elsewhere we archive (mark read).
        val archiving = tab.value != LibraryTab.ARCHIVE
        hiddenIds.update { it + bookmark.id }
        viewModelScope.launch {
            runCatching { repository.setArchived(bookmark.id, archiving) }
                .onFailure {
                    hiddenIds.update { ids -> ids - bookmark.id }
                    events.send(LibraryEvent.Message("Couldn't update — try again."))
                }
                .onSuccess {
                    if (archiving) events.send(LibraryEvent.Archived(bookmark))
                }
        }
    }

    fun undoArchive(bookmark: Bookmark) {
        viewModelScope.launch {
            runCatching { repository.setArchived(bookmark.id, false) }
                .onSuccess { hiddenIds.update { it - bookmark.id } }
        }
    }

    fun favourite(bookmark: Bookmark) {
        val newValue = !bookmark.favourited
        viewModelScope.launch {
            runCatching { repository.setFavourited(bookmark.id, newValue) }
                .onSuccess {
                    events.send(LibraryEvent.Message(if (newValue) "Favourited" else "Removed favourite"))
                    // If we're viewing favourites and just removed one, hide it.
                    if (!newValue && tab.value == LibraryTab.FAVOURITES) {
                        hiddenIds.update { it + bookmark.id }
                    }
                }
                .onFailure { events.send(LibraryEvent.Message("Couldn't update — try again.")) }
        }
    }

    /**
     * For empty states: open a random article to read anyway — a favourite, or
     * failing that something from the archive.
     */
    fun surpriseMe() {
        viewModelScope.launch {
            val id = repository.randomBookmarkId(BookmarkSource.Favourites)
                ?: repository.randomBookmarkId(BookmarkSource.Archive)
            if (id != null) {
                events.send(LibraryEvent.Open(id))
            } else {
                events.send(LibraryEvent.Message("Nothing to surprise you with yet."))
            }
        }
    }

    fun clearHidden() = hiddenIds.update { emptySet() }
}
