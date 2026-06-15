package ch.lkmc.kararead.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.kararead.data.model.Highlight
import ch.lkmc.kararead.data.model.ReaderArticle
import ch.lkmc.kararead.data.model.ReaderFont
import ch.lkmc.kararead.data.model.ReaderPreferences
import ch.lkmc.kararead.data.model.ReaderTheme
import ch.lkmc.kararead.data.prefs.SettingsRepository
import ch.lkmc.kararead.data.remote.ApiProvider
import ch.lkmc.kararead.data.repository.KarakeepRepository
import ch.lkmc.kararead.reader.AssetLoader
import ch.lkmc.kararead.tts.ArticleSpeaker
import ch.lkmc.kararead.tts.SpeechState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
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
    private val speaker: ArticleSpeaker,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val speech: StateFlow<SpeechState> = speaker.state

    private val bookmarkId: String = savedStateHandle.get<String>("bookmarkId").orEmpty()

    private val _highlights = MutableStateFlow<List<Highlight>>(emptyList())
    val highlights: StateFlow<List<Highlight>> = _highlights

    /** Highlights serialized as `[{id,start,end}]` for the WebView renderer. */
    val highlightsJson: StateFlow<String> =
        _highlights.map { list ->
            JSONArray().apply {
                list.forEach { h ->
                    put(
                        JSONObject()
                            .put("id", h.id)
                            .put("start", h.startOffset)
                            .put("end", h.endOffset),
                    )
                }
            }.toString()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, "[]")

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state

    val readerPrefs: StateFlow<ReaderPreferences> =
        settings.readerPreferences.stateIn(
            viewModelScope, SharingStarted.Eagerly, ReaderPreferences(),
        )

    val serverOrigin: String? get() = apiProvider.serverOrigin

    private var saveJob: Job? = null
    private var lastSaved = 0f

    // True once the user has toggled read/favourite in this session, so a late
    // live-state refresh never clobbers their optimistic change.
    private var userChangedReadState = false

    init {
        load()
    }

    fun load(forceRefresh: Boolean = false) {
        userChangedReadState = false
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
                    // The article may have come from the cache; reconcile the
                    // read/favourite flags with the live server state when we can.
                    repository.refreshReadState(bookmarkId)?.let { (archived, favourited) ->
                        if (!userChangedReadState) {
                            _state.update { it.copy(archived = archived, favourited = favourited) }
                        }
                    }
                    runCatching { repository.getHighlights(bookmarkId) }
                        .onSuccess { _highlights.value = it }
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
        userChangedReadState = true
        val newValue = !_state.value.favourited
        _state.update { it.copy(favourited = newValue) }
        viewModelScope.launch {
            runCatching { repository.setFavourited(bookmarkId, newValue) }
                .onFailure { _state.update { s -> s.copy(favourited = !newValue) } }
        }
    }

    /** Mark as read/done (archive) or restore. */
    fun toggleArchived() {
        userChangedReadState = true
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
    fun setVolumeKeyPaging(value: Boolean) = updatePrefs { it.copy(volumeKeyPaging = value) }

    private fun updatePrefs(transform: (ReaderPreferences) -> ReaderPreferences) {
        viewModelScope.launch {
            settings.updateReaderPreferences(transform(readerPrefs.value))
        }
    }

    /** Record foreground reading time (driven by the screen's lifecycle ticker). */
    fun recordReadingSeconds(seconds: Long) {
        viewModelScope.launch { repository.addReadingSeconds(seconds) }
    }

    // --- Highlights ---

    /** Create a highlight from a captured text selection. */
    fun addHighlight(text: String, start: Int, end: Int) {
        if (end <= start) return
        viewModelScope.launch {
            runCatching { repository.createHighlight(bookmarkId, start, end, text.trim()) }
                .onSuccess { created -> _highlights.update { it + created } }
        }
    }

    fun removeHighlight(id: String) {
        _highlights.update { list -> list.filterNot { it.id == id } }
        viewModelScope.launch { runCatching { repository.deleteHighlight(id) } }
    }

    fun highlight(id: String): Highlight? = _highlights.value.firstOrNull { it.id == id }

    // --- Text-to-speech ---

    /** Whether the current article has readable text to narrate. */
    val canListen: Boolean get() = !_state.value.article?.textContent.isNullOrBlank()

    fun listen() = speaker.start(_state.value.article?.textContent)
    fun toggleSpeech() = speaker.togglePlayPause()
    fun skipSpeech(delta: Int) = speaker.skipBy(delta)
    fun stopSpeech() = speaker.stop()

    override fun onCleared() {
        speaker.shutdown()
        super.onCleared()
    }

    fun url(): String? = _state.value.article?.bookmark?.url
}
