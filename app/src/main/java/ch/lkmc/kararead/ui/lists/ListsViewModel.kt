package ch.lkmc.kararead.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.kararead.data.model.KarakeepList
import ch.lkmc.kararead.data.prefs.SettingsRepository
import ch.lkmc.kararead.data.repository.KarakeepRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ListsUiState(
    val loading: Boolean = true,
    val lists: List<KarakeepList> = emptyList(),
    val error: String? = null,
    val readLaterListId: String? = null,
)

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val repository: KarakeepRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ListsUiState())
    val state: StateFlow<ListsUiState> = _state

    init {
        refresh()
        viewModelScope.launch {
            settings.readLaterList.collect { rl ->
                _state.value = _state.value.copy(readLaterListId = rl?.first)
            }
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { repository.getLists() }
                .onSuccess { lists ->
                    _state.value = _state.value.copy(
                        loading = false,
                        lists = lists.sortedBy { it.name.lowercase() },
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = it.message ?: "Couldn't load lists.",
                    )
                }
        }
    }

    fun setReadLater(list: KarakeepList?) {
        viewModelScope.launch {
            settings.setReadLaterList(list?.id, list?.name)
        }
    }
}
