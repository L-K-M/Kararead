package ch.lkmc.kararead.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.kararead.data.model.KarakeepList
import ch.lkmc.kararead.data.repository.KarakeepRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for the [AddToListSheet] list picker. */
data class ListPickerState(
    val loading: Boolean = true,
    val lists: List<KarakeepList> = emptyList(),
    /** Ids of lists the article currently belongs to. */
    val memberOf: Set<String> = emptySet(),
    val error: String? = null,
)

@HiltViewModel
class ListPickerViewModel @Inject constructor(
    private val repository: KarakeepRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ListPickerState())
    val state: StateFlow<ListPickerState> = _state

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Which bookmark the current state describes, so re-opens reload correctly. */
    private var loadedFor: String? = null

    fun load(bookmarkId: String) {
        loadedFor = bookmarkId
        _state.value = ListPickerState(loading = true)
        viewModelScope.launch {
            runCatching {
                val lists = repository.getManualLists()
                val member = repository.listsContaining(bookmarkId, lists.map { it.id })
                lists to member
            }.onSuccess { (lists, member) ->
                // Ignore a stale result if the sheet was reopened for another article.
                if (loadedFor != bookmarkId) return@onSuccess
                _state.value = ListPickerState(loading = false, lists = lists, memberOf = member)
            }.onFailure {
                if (loadedFor != bookmarkId) return@onFailure
                _state.value = ListPickerState(
                    loading = false,
                    error = it.message ?: "Couldn't load lists",
                )
            }
        }
    }

    /**
     * Add or remove [bookmarkId] to/from [list], optimistically. Reverts and
     * reports if the server rejects the change.
     */
    fun toggle(bookmarkId: String, list: KarakeepList) {
        val wasMember = list.id in _state.value.memberOf
        _state.update { s ->
            s.copy(memberOf = if (wasMember) s.memberOf - list.id else s.memberOf + list.id)
        }
        viewModelScope.launch {
            val result = runCatching {
                if (wasMember) {
                    repository.removeBookmarkFromList(bookmarkId, list.id)
                } else {
                    repository.addBookmarkToList(bookmarkId, list.id)
                }
            }
            if (result.isFailure) {
                _state.update { s ->
                    s.copy(memberOf = if (wasMember) s.memberOf + list.id else s.memberOf - list.id)
                }
                _messages.trySend("Couldn't update \"${list.name}\"")
            } else {
                _messages.trySend(if (wasMember) "Removed from ${list.name}" else "Added to ${list.name}")
            }
        }
    }
}
