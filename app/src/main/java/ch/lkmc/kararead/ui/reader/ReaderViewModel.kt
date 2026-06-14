package ch.lkmc.kararead.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.kararead.data.model.ReaderArticle
import ch.lkmc.kararead.data.model.ReaderFont
import ch.lkmc.kararead.data.model.ReaderPreferences
import ch.lkmc.kararead.data.model.ReaderTheme
import ch.lkmc.kararead.data.prefs.SettingsRepository
import ch.lkmc.kararead.data.remote.ApiProvider
import ch.lkmc.kararead.data.repository.KarakeepRepository
import ch.lkmc.kararead.reader.AssetLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val article: ReaderArticle? = null,
    val favourited: Boolean = false,
    val archived: Boolean = false,
    val initialProgress: Float = 0f,
    val progress: Float = 0f,
    val offline: Boolean = false,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val repository: KarakeepRepository,
    private val settings: SettingsRepository,
    private val apiProvider: ApiProvider,
    val assetLoader: AssetLoader,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val bookmarkId: String = savedStateHandle.get<String>("bookmarkId").orEmpty()

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state

    val readerPrefs: StateFlow<ReaderPreferences> =
        settings.readerPreferences.stateIn(
            viewModelScope, SharingStarted.Eagerly, ReaderPreferences(),
        )

    val serverOrigin: String? get() = apiProvider.serverOrigin

    private var saveJob: Job? = null
    private var lastSaved = 0f

    init {
        load()
    }

    fun load(forceRefresh: Boolean = false) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val initial = repository.getProgressOnce(bookmarkId)
            runCatching { repository.getArticle(bookmarkId, forceRefresh) }
                .onSuccess { article ->
                    _state.update {
                        it.copy(
                            loading = false,
                            article = article,
                            favourited = article.bookmark.favourited,
                            archived = article.bookmark.archived,
                            initialProgress = initial,
                            progress = initial,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "Couldn't load this article.")
                    }
                }
        }
    }

    /** Called frequently from the WebView scroll bridge; persists with debounce. */
    fun onProgress(fraction: Float) {
        _state.update { it.copy(progress = fraction) }
        if (kotlin.math.abs(fraction - lastSaved) < 0.01f) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            lastSaved = fraction
            repository.saveProgress(bookmarkId, fraction)
        }
    }

    fun toggleFavourite() {
        val newValue = !_state.value.favourited
        _state.update { it.copy(favourited = newValue) }
        viewModelScope.launch {
            runCatching { repository.setFavourited(bookmarkId, newValue) }
                .onFailure { _state.update { s -> s.copy(favourited = !newValue) } }
        }
    }

    /** Mark as read/done (archive) or restore. */
    fun toggleArchived() {
        val newValue = !_state.value.archived
        _state.update { it.copy(archived = newValue) }
        viewModelScope.launch {
            runCatching { repository.setArchived(bookmarkId, newValue) }
                .onFailure { _state.update { s -> s.copy(archived = !newValue) } }
        }
    }

    // --- Reader preference setters ---

    fun setTheme(theme: ReaderTheme) = updatePrefs { it.copy(theme = theme) }
    fun setFont(font: ReaderFont) = updatePrefs { it.copy(font = font) }
    fun setFontScale(scale: Float) = updatePrefs { it.copy(fontScale = scale.coerceIn(0.7f, 2.0f)) }
    fun setLineHeight(value: Float) = updatePrefs { it.copy(lineHeight = value.coerceIn(1.2f, 2.2f)) }
    fun setMargin(value: Int) = updatePrefs { it.copy(horizontalMargin = value.coerceIn(0, 48)) }
    fun setJustify(value: Boolean) = updatePrefs { it.copy(justify = value) }
    fun setKeepScreenOn(value: Boolean) = updatePrefs { it.copy(keepScreenOn = value) }

    private fun updatePrefs(transform: (ReaderPreferences) -> ReaderPreferences) {
        viewModelScope.launch {
            settings.updateReaderPreferences(transform(readerPrefs.value))
        }
    }

    fun url(): String? = _state.value.article?.bookmark?.url
}
