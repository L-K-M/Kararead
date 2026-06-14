package ch.lkmc.kararead.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.kararead.data.model.Tag
import ch.lkmc.kararead.data.repository.KarakeepRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TagsUiState(
    val loading: Boolean = true,
    val tags: List<Tag> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class TagsViewModel @Inject constructor(
    private val repository: KarakeepRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TagsUiState())
    val state: StateFlow<TagsUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { repository.getTags() }
                .onSuccess { tags ->
                    // getTags() already sorts by descending count.
                    _state.value = TagsUiState(loading = false, tags = tags.filter { it.count > 0 })
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = it.message ?: "Couldn't load tags.",
                    )
                }
        }
    }
}
