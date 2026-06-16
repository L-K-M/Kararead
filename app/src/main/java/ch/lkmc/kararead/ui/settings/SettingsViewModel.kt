package ch.lkmc.kararead.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.kararead.data.model.AppThemeMode
import ch.lkmc.kararead.data.model.OfflinePreferences
import ch.lkmc.kararead.data.prefs.SettingsRepository
import ch.lkmc.kararead.data.repository.KarakeepRepository
import ch.lkmc.kararead.util.ReadingStats
import ch.lkmc.kararead.work.OfflineSync
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val repository: KarakeepRepository,
    private val offlineSync: OfflineSync,
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
            val updated = transform(settings.offlinePreferencesOnce())
            settings.setOfflinePreferences(updated)
            // Scheduling is also driven by the app-level observer, but kick a
            // download immediately when the user just enabled offline reading.
            if (updated.enabled) offlineSync.runNow()
        }
    }

    /** Manual "Download now". */
    fun downloadOfflineNow() = offlineSync.runNow()

    fun clearCache() {
        viewModelScope.launch { repository.clearCache() }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.clearCache()
            settings.clearConnection()
            settings.setReadLaterList(null, null)
            onDone()
        }
    }
}
