package ch.lkmc.kararead.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ch.lkmc.kararead.data.model.AppThemeMode
import ch.lkmc.kararead.data.model.ConnectionSettings
import ch.lkmc.kararead.data.model.OfflinePreferences
import ch.lkmc.kararead.data.model.QueueSort
import ch.lkmc.kararead.data.model.ReaderFont
import ch.lkmc.kararead.data.model.ReaderPreferences
import ch.lkmc.kararead.data.model.ReaderTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// General preferences live here; secrets (the API key) live in a separate,
// backup-excluded store (see AndroidManifest backup rules).
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("kararead_settings")
private val Context.secretsStore: DataStore<Preferences> by preferencesDataStore("kararead_secrets")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val FALLBACK_URL = stringPreferencesKey("fallback_url")
        val API_KEY = stringPreferencesKey("api_key")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val READER_FONT = stringPreferencesKey("reader_font")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val LINE_HEIGHT = floatPreferencesKey("line_height")
        val MARGIN = intPreferencesKey("h_margin")
        val JUSTIFY = booleanPreferencesKey("justify")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val VOLUME_KEY_PAGING = booleanPreferencesKey("volume_key_paging")
        val PAGED_MODE = booleanPreferencesKey("paged_mode")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val APP_THEME = stringPreferencesKey("app_theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ACCENT_COLOR = intPreferencesKey("accent_color")
        val QUEUE_SORT = stringPreferencesKey("queue_sort")
        val READ_LATER_LIST_ID = stringPreferencesKey("read_later_list_id")
        val READ_LATER_LIST_NAME = stringPreferencesKey("read_later_list_name")
        val OFFLINE_ENABLED = booleanPreferencesKey("offline_enabled")
        val OFFLINE_WIFI_ONLY = booleanPreferencesKey("offline_wifi_only")
        val OFFLINE_KEEP_COUNT = intPreferencesKey("offline_keep_count")
    }

    // Combine BOTH stores so the flow re-emits when either the URL/fallback or
    // the (separately stored) API key changes. Reading the key inside a map over
    // only the settings store would miss key writes — which left the API client
    // un-configured right after a first sign-in ("Not connected").
    val connection: Flow<ConnectionSettings> =
        combine(context.settingsStore.data, context.secretsStore.data) { prefs, secrets ->
            ConnectionSettings(
                serverUrl = prefs[Keys.SERVER_URL] ?: "",
                apiKey = secrets[Keys.API_KEY] ?: "",
                fallbackUrl = prefs[Keys.FALLBACK_URL] ?: "",
            )
        }

    suspend fun connectionOnce(): ConnectionSettings {
        val prefs = context.settingsStore.data.first()
        val key = context.secretsStore.data.first()[Keys.API_KEY] ?: ""
        return ConnectionSettings(
            serverUrl = prefs[Keys.SERVER_URL] ?: "",
            apiKey = key,
            fallbackUrl = prefs[Keys.FALLBACK_URL] ?: "",
        )
    }

    suspend fun saveConnection(settings: ConnectionSettings) {
        // Write the secret first so the combined flow's final emission (driven by
        // the settings-store write) already carries a complete, ready connection.
        context.secretsStore.edit { it[Keys.API_KEY] = settings.apiKey }
        context.settingsStore.edit {
            it[Keys.SERVER_URL] = settings.serverUrl
            if (settings.fallbackUrl.isBlank()) {
                it.remove(Keys.FALLBACK_URL)
            } else {
                it[Keys.FALLBACK_URL] = settings.fallbackUrl
            }
        }
    }

    suspend fun clearConnection() {
        context.secretsStore.edit { it.remove(Keys.API_KEY) }
        context.settingsStore.edit {
            it.remove(Keys.SERVER_URL)
            it.remove(Keys.FALLBACK_URL)
        }
    }

    val readerPreferences: Flow<ReaderPreferences> = context.settingsStore.data.map { p ->
        ReaderPreferences(
            theme = p[Keys.READER_THEME]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                ?: ReaderTheme.LIGHT,
            font = p[Keys.READER_FONT]?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() }
                ?: ReaderFont.SERIF,
            fontScale = p[Keys.FONT_SCALE] ?: 1.0f,
            lineHeight = p[Keys.LINE_HEIGHT] ?: 1.6f,
            horizontalMargin = p[Keys.MARGIN] ?: 20,
            justify = p[Keys.JUSTIFY] ?: false,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: false,
            volumeKeyPaging = p[Keys.VOLUME_KEY_PAGING] ?: true,
            pagedMode = p[Keys.PAGED_MODE] ?: false,
        )
    }

    suspend fun updateReaderPreferences(prefs: ReaderPreferences) {
        context.settingsStore.edit { p ->
            p[Keys.READER_THEME] = prefs.theme.name
            p[Keys.READER_FONT] = prefs.font.name
            p[Keys.FONT_SCALE] = prefs.fontScale
            p[Keys.LINE_HEIGHT] = prefs.lineHeight
            p[Keys.MARGIN] = prefs.horizontalMargin
            p[Keys.JUSTIFY] = prefs.justify
            p[Keys.KEEP_SCREEN_ON] = prefs.keepScreenOn
            p[Keys.VOLUME_KEY_PAGING] = prefs.volumeKeyPaging
            p[Keys.PAGED_MODE] = prefs.pagedMode
        }
    }

    /** Preferred TTS voice id (engine voice name), or null for the engine default. */
    val ttsVoice: Flow<String?> = context.settingsStore.data.map { it[Keys.TTS_VOICE] }

    suspend fun setTtsVoice(id: String) {
        context.settingsStore.edit { it[Keys.TTS_VOICE] = id }
    }

    val appThemeMode: Flow<AppThemeMode> = context.settingsStore.data.map { p ->
        p[Keys.APP_THEME]?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() }
            ?: AppThemeMode.SYSTEM
    }

    suspend fun setAppThemeMode(mode: AppThemeMode) {
        context.settingsStore.edit { it[Keys.APP_THEME] = mode.name }
    }

    val dynamicColor: Flow<Boolean> = context.settingsStore.data.map { it[Keys.DYNAMIC_COLOR] ?: true }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    /** Manual accent (ARGB) used when dynamic color is off. 0 = the app default. */
    val accentColor: Flow<Int> = context.settingsStore.data.map { it[Keys.ACCENT_COLOR] ?: 0 }

    suspend fun setAccentColor(argb: Int) {
        context.settingsStore.edit { it[Keys.ACCENT_COLOR] = argb }
    }

    val queueSort: Flow<QueueSort> = context.settingsStore.data.map { p ->
        p[Keys.QUEUE_SORT]?.let { runCatching { QueueSort.valueOf(it) }.getOrNull() } ?: QueueSort.NEWEST
    }

    suspend fun setQueueSort(sort: QueueSort) {
        context.settingsStore.edit { it[Keys.QUEUE_SORT] = sort.name }
    }

    val offlinePreferences: Flow<OfflinePreferences> = context.settingsStore.data.map { p ->
        OfflinePreferences(
            enabled = p[Keys.OFFLINE_ENABLED] ?: true,
            wifiOnly = p[Keys.OFFLINE_WIFI_ONLY] ?: true,
            keepCount = (p[Keys.OFFLINE_KEEP_COUNT] ?: 20).coerceIn(5, 100),
        )
    }

    suspend fun setOfflinePreferences(prefs: OfflinePreferences) {
        context.settingsStore.edit { p ->
            p[Keys.OFFLINE_ENABLED] = prefs.enabled
            p[Keys.OFFLINE_WIFI_ONLY] = prefs.wifiOnly
            p[Keys.OFFLINE_KEEP_COUNT] = prefs.keepCount.coerceIn(5, 100)
        }
    }

    suspend fun offlinePreferencesOnce(): OfflinePreferences = offlinePreferences.first()

    /** The list the user designates as their "read it later" home, if any. */
    val readLaterList: Flow<Pair<String, String>?> = context.settingsStore.data.map { p ->
        val id = p[Keys.READ_LATER_LIST_ID]
        val name = p[Keys.READ_LATER_LIST_NAME]
        if (id != null && name != null) id to name else null
    }

    suspend fun setReadLaterList(id: String?, name: String?) {
        context.settingsStore.edit { p ->
            if (id == null || name == null) {
                p.remove(Keys.READ_LATER_LIST_ID)
                p.remove(Keys.READ_LATER_LIST_NAME)
            } else {
                p[Keys.READ_LATER_LIST_ID] = id
                p[Keys.READ_LATER_LIST_NAME] = name
            }
        }
    }
}
