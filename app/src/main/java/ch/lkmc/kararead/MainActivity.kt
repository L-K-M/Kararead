package ch.lkmc.kararead

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ch.lkmc.kararead.data.model.AppThemeMode
import ch.lkmc.kararead.data.prefs.SettingsRepository
import ch.lkmc.kararead.ui.navigation.KararreadNavHost
import ch.lkmc.kararead.ui.navigation.Routes
import ch.lkmc.kararead.ui.theme.KararreadTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RootUiState(
    val loading: Boolean = true,
    val isConnected: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

@dagger.hilt.android.lifecycle.HiltViewModel
class RootViewModel @Inject constructor(
    settings: SettingsRepository,
) : ViewModel() {

    private val ready = MutableStateFlow(false)

    val state: StateFlow<RootUiState> =
        combine(
            settings.connection,
            settings.appThemeMode,
            settings.dynamicColor,
            ready,
        ) { conn, theme, dynamic, isReady ->
            RootUiState(
                loading = !isReady,
                isConnected = conn.isComplete,
                themeMode = theme,
                dynamicColor = dynamic,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, RootUiState())

    init {
        viewModelScope.launch { ready.value = true }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val rootViewModel: RootViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        splash.setKeepOnScreenCondition { rootViewModel.state.value.loading }

        setContent {
            val state by rootViewModel.state.collectAsStateWithLifecycle()
            KararreadTheme(themeMode = state.themeMode, dynamicColor = state.dynamicColor) {
                if (!state.loading) {
                    val start = if (state.isConnected) Routes.LIBRARY else Routes.ONBOARDING
                    KararreadNavHost(startDestination = start)
                }
            }
        }
    }
}
