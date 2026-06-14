package ch.lkmc.kararead.data.remote

import ch.lkmc.kararead.data.model.ConnectionSettings
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the live [KarakeepApi]. The server URL and API key are user-supplied at
 * runtime, so the Retrofit instance is (re)built whenever connection settings
 * change rather than provided as a static singleton.
 */
@Singleton
class ApiProvider @Inject constructor() {

    @Volatile
    private var state: State? = null

    private data class State(
        val settings: ConnectionSettings,
        val api: KarakeepApi,
        val client: OkHttpClient,
        val baseApiUrl: String, // e.g. https://host/api/v1/
    )

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    /** Rebuild the client when connection settings change. No-op if unchanged. */
    @Synchronized
    fun configure(settings: ConnectionSettings) {
        if (!settings.isComplete) {
            state = null
            return
        }
        if (state?.settings == settings) return

        val baseApiUrl = normalizeBaseUrl(settings.serverUrl)
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
            redactHeader("Authorization")
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(settings.apiKey))
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseApiUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        state = State(settings, retrofit.create(KarakeepApi::class.java), client, baseApiUrl)
    }

    fun isConfigured(): Boolean = state != null

    /** The current API, or throw [NotConnectedException] if not configured. */
    fun api(): KarakeepApi = state?.api ?: throw NotConnectedException()

    fun authHeader(): String? = state?.settings?.apiKey?.let { "Bearer $it" }

    /** Absolute URL for a binary asset (image/screenshot/favicon). */
    fun assetUrl(assetId: String): String? =
        state?.let { "${it.baseApiUrl}assets/$assetId" }

    val baseApiUrl: String? get() = state?.baseApiUrl

    /** Host of the configured server, used to scope auth headers for images. */
    val serverHost: String?
        get() = state?.baseApiUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() }

    /** Scheme + authority, e.g. "https://bookmarks.example.com", used as WebView base URL. */
    val serverOrigin: String?
        get() = state?.baseApiUrl?.let {
            runCatching {
                val u = java.net.URI(it)
                val port = if (u.port != -1) ":${u.port}" else ""
                "${u.scheme}://${u.host}$port"
            }.getOrNull()
        }

    /** Bearer header for a URL, but only if it targets the configured server. */
    fun authHeaderForUrl(url: String): String? {
        val host = serverHost ?: return null
        val requestHost = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        return if (requestHost.equals(host, ignoreCase = true)) authHeader() else null
    }

    companion object {
        /**
         * Turn a user-entered server URL into a Retrofit base URL ending in
         * `/api/v1/`. Tolerant of trailing slashes and a pasted `/api/v1`.
         */
        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            url = url.trimEnd('/')
            // Strip an accidentally-pasted api suffix, then add the canonical one.
            url = url.removeSuffix("/api/v1").removeSuffix("/api")
            return "$url/api/v1/"
        }
    }
}

/** Adds the bearer token to every request. */
class AuthInterceptor(private val apiKey: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .build()
        return chain.proceed(request)
    }
}

class NotConnectedException : IllegalStateException("Not connected to a Karakeep server")
