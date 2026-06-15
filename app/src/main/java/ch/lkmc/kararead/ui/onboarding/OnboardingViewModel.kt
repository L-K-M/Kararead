package ch.lkmc.kararead.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.kararead.data.model.ConnectionSettings
import ch.lkmc.kararead.data.prefs.SettingsRepository
import ch.lkmc.kararead.data.repository.ConnectionResult
import ch.lkmc.kararead.data.repository.KarakeepRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val serverUrl: String = "",
    val fallbackUrl: String = "",
    val apiKey: String = "",
    val isConnecting: Boolean = false,
    val error: String? = null,
    val connectedAs: String? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repository: KarakeepRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state

    fun onServerUrlChange(value: String) = _state.update { it.copy(serverUrl = value, error = null) }
    fun onFallbackUrlChange(value: String) = _state.update { it.copy(fallbackUrl = value, error = null) }
    fun onApiKeyChange(value: String) = _state.update { it.copy(apiKey = value, error = null) }

    fun connect(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.serverUrl.isBlank() || s.apiKey.isBlank()) {
            _state.update { it.copy(error = "Enter both a server URL and an API key.") }
            return
        }
        _state.update { it.copy(isConnecting = true, error = null) }
        viewModelScope.launch {
            val settingsToTest = ConnectionSettings(
                serverUrl = s.serverUrl.trim(),
                apiKey = s.apiKey.trim(),
                fallbackUrl = s.fallbackUrl.trim(),
            )
            when (val result = repository.testConnection(settingsToTest)) {
                is ConnectionResult.Success -> {
                    settings.saveConnection(settingsToTest)
                    _state.update { it.copy(isConnecting = false, connectedAs = result.userLabel) }
                    onSuccess()
                }
                is ConnectionResult.Unauthorized ->
                    _state.update { it.copy(isConnecting = false, error = result.message) }
                is ConnectionResult.Failure ->
                    _state.update { it.copy(isConnecting = false, error = result.message) }
            }
        }
    }
}
