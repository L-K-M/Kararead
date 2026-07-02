package ch.lkmc.kararead.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.kararead.data.model.AppThemeMode
import ch.lkmc.kararead.data.model.ConnectionSettings
import ch.lkmc.kararead.data.model.OfflinePreferences
import ch.lkmc.kararead.data.prefs.SettingsRepository
import ch.lkmc.kararead.data.remote.ApiProvider
import ch.lkmc.kararead.data.repository.ConnectionResult
import ch.lkmc.kararead.data.repository.KarakeepRepository
import ch.lkmc.kararead.util.ReadingStats
import ch.lkmc.kararead.work.OfflineSync
import ch.lkmc.kararead.work.PendingOpSync
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val fallbackUrl: String = "",
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val accentColor: Int = 0,
    val readLaterName: String? = null,
    val cachedCount: Int = 0,
    val offline: OfflinePreferences = OfflinePreferences(),
    val stats: ReadingStats = ReadingStats(),
    /** SAF tree URI (string) of the highlights export folder, or null if unset. */
    val highlightsFolder: String? = null,
)

/** Outcome of an in-place connection edit, surfaced to the edit dialog. */
sealed interface ConnectionEditResult {
    data object Success : ConnectionEditResult
    data class Failure(val message: String) : ConnectionEditResult
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val repository: KarakeepRepository,
    private val apiProvider: ApiProvider,
    private val offlineSync: OfflineSync,
    private val pendingOpSync: PendingOpSync,
) : ViewModel() {

    private val base = combine(
        settings.connection,
        settings.appThemeMode,
        settings.dynamicColor,
        settings.readLaterList,
        settings.offlinePreferences,
    ) { conn, theme, dynamic, readLater, offline ->
        SettingsUiState(
            serverUrl = conn.serverUrl,
            fallbackUrl = conn.fallbackUrl,
            themeMode = theme,
            dynamicColor = dynamic,
            readLaterName = readLater?.second,
            offline = offline,
        )
    }

    val state: StateFlow<SettingsUiState> =
        combine(
            base,
            repository.cachedIds().map { it.size },
            repository.readingStats(),
            settings.accentColor,
            settings.highlightsFolderUri,
        ) { s, cachedCount, stats, accent, folder ->
            s.copy(
                cachedCount = cachedCount,
                stats = stats,
                accentColor = accent,
                highlightsFolder = folder,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    /**
     * Test and (on success) persist an edited server connection without signing
     * out — so a rotated API key or moved server can be fixed in place, keeping
     * the cache, reading progress and stats. A blank [apiKey] keeps the current
     * key (we never surface it back to the UI). On a failed test the live client
     * is restored to the saved connection, so a bad edit can't strand the app
     * pointed at an unreachable server (fixes the testConnection side-effect).
     */
    fun updateConnection(
        serverUrl: String,
        fallbackUrl: String,
        apiKey: String,
        onResult: (ConnectionEditResult) -> Unit,
    ) {
        viewModelScope.launch {
            val current = settings.connectionOnce()
            val candidate = ConnectionSettings(
                serverUrl = serverUrl.trim(),
                apiKey = apiKey.trim().ifBlank { current.apiKey },
                fallbackUrl = fallbackUrl.trim(),
            )
            if (candidate.serverUrl.isBlank() || candidate.apiKey.isBlank()) {
                onResult(ConnectionEditResult.Failure("Enter a server URL and an API key."))
                return@launch
            }
            when (val result = repository.testConnection(candidate)) {
                is ConnectionResult.Success -> {
                    settings.saveConnection(candidate)
                    onResult(ConnectionEditResult.Success)
                }
                is ConnectionResult.Unauthorized -> {
                    if (current.isComplete) apiProvider.configure(current)
                    onResult(ConnectionEditResult.Failure(result.message))
                }
                is ConnectionResult.Failure -> {
                    if (current.isComplete) apiProvider.configure(current)
                    onResult(ConnectionEditResult.Failure(result.message))
                }
            }
        }
    }

    fun setHighlightsFolder(uri: String?) {
        viewModelScope.launch { settings.setHighlightsFolderUri(uri) }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { settings.setAppThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    fun setAccentColor(argb: Int) {
        viewModelScope.launch { settings.setAccentColor(argb) }
    }

    fun setOfflineEnabled(enabled: Boolean) = updateOffline { it.copy(enabled = enabled) }
    fun setOfflineWifiOnly(wifiOnly: Boolean) = updateOffline { it.copy(wifiOnly = wifiOnly) }
    fun setOfflineKeepCount(count: Int) = updateOffline { it.copy(keepCount = count) }

    private fun updateOffline(transform: (OfflinePreferences) -> OfflinePreferences) {
        viewModelScope.launch {
            val previous = settings.offlinePreferencesOnce()
            val updated = transform(previous)
            settings.setOfflinePreferences(updated)
            // Scheduling is also driven by the app-level observer, but kick a
            // download immediately when the user just ENABLED offline reading.
            // Only on that transition — kicking on every pref change used to
            // fire an unconstrained download the moment someone turned ON
            // "Only on Wi-Fi" while on mobile data — and with the user's
            // network constraint applied.
            if (updated.enabled && !previous.enabled) offlineSync.runNow(updated.wifiOnly)
        }
    }

    /** Manual "Download now" — still honors the wifi-only preference. */
    fun downloadOfflineNow() {
        viewModelScope.launch {
            offlineSync.runNow(settings.offlinePreferencesOnce().wifiOnly)
        }
    }

    fun clearCache() {
        viewModelScope.launch { repository.clearCache() }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            // Everything local is per-account: queued outbox ops would replay
            // against the next server, and progress/stats would bleed across.
            pendingOpSync.cancel()
            offlineSync.cancelAll()
            repository.clearLocalState()
            settings.clearConnection()
            settings.setReadLaterList(null, null)
            onDone()
        }
    }
}
