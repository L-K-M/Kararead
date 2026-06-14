package ch.lkmc.kararead.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.kararead.data.model.AppThemeMode
import ch.lkmc.kararead.data.prefs.SettingsRepository
import ch.lkmc.kararead.data.repository.KarakeepRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val readLaterName: String? = null,
    val cachedCount: Int = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val repository: KarakeepRepository,
) : ViewModel() {

    private val cachedCount = MutableStateFlow(0)

    val state: StateFlow<SettingsUiState> =
        combine(
            settings.connection,
            settings.appThemeMode,
            settings.dynamicColor,
            settings.readLaterList,
            cachedCount,
        ) { conn, theme, dynamic, readLater, count ->
            SettingsUiState(
                serverUrl = conn.serverUrl,
                themeMode = theme,
                dynamicColor = dynamic,
                readLaterName = readLater?.second,
                cachedCount = count,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    init {
        refreshCacheCount()
    }

    fun refreshCacheCount() {
        viewModelScope.launch { cachedCount.value = repository.cachedCount() }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { settings.setAppThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    fun clearCache() {
        viewModelScope.launch {
            repository.clearCache()
            refreshCacheCount()
        }
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
