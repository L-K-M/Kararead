package ch.lkmc.kararead.data.remote

import ch.lkmc.kararead.data.model.ConnectionSettings
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the live [KarakeepApi]. The server URL and API key are user-supplied at
 * runtime, so the Retrofit instance is (re)built whenever connection settings
 * change rather than provided as a static singleton.
 *
 * When a fallback server is configured, a [FailoverInterceptor] transparently
 * retries requests against it whenever the active server is unreachable, and
 * sticks with whichever one is currently answering.
 */
@Singleton
class ApiProvider @Inject constructor() {

    @Volatile
    private var state: State? = null

    /** The base API URL (".../api/v1/") currently answering, for asset/image URLs. */
    @Volatile
    private var activeBaseApiUrl: String? = null

    private data class State(
        val settings: ConnectionSettings,
        val api: KarakeepApi,
        val client: OkHttpClient,
        val primaryBaseUrl: String,
        val fallbackBaseUrl: String?,
        /** Lowercased hosts (primary + fallback) the bearer token may be sent to. */
        val hosts: Set<String>,
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
            activeBaseApiUrl = null
            return
        }
        if (state?.settings == settings) return

        val primaryBaseUrl = normalizeBaseUrl(settings.serverUrl)
        val fallbackBaseUrl = settings.fallbackUrl
            .takeIf { it.isNotBlank() }
            ?.let { normalizeBaseUrl(it) }
            ?.takeIf { it != primaryBaseUrl }

        val primaryHttp = primaryBaseUrl.toHttpUrlOrNull()
        val fallbackHttp = fallbackBaseUrl?.toHttpUrlOrNull()

        activeBaseApiUrl = primaryBaseUrl

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
            redactHeader("Authorization")
        }
        val builder = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(settings.apiKey))
        if (fallbackHttp != null && primaryHttp != null) {
            builder.addInterceptor(
                FailoverInterceptor(primaryHttp, fallbackHttp) { activeBaseApiUrl = it },
            )
        }
        val client = builder
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(primaryBaseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        val hosts = setOfNotNull(primaryHttp?.host?.lowercase(), fallbackHttp?.host?.lowercase())

        state = State(
            settings = settings,
            api = retrofit.create(KarakeepApi::class.java),
            client = client,
            primaryBaseUrl = primaryBaseUrl,
            fallbackBaseUrl = fallbackBaseUrl,
            hosts = hosts,
        )
    }

    fun isConfigured(): Boolean = state != null

    /** The current API, or throw [NotConnectedException] if not configured. */
    fun api(): KarakeepApi = state?.api ?: throw NotConnectedException()

    fun authHeader(): String? = state?.settings?.apiKey?.let { "Bearer $it" }

    /** Absolute URL for a binary asset (image/screenshot/favicon) on the active server. */
    fun assetUrl(assetId: String): String? =
        (activeBaseApiUrl ?: state?.primaryBaseUrl)?.let { "${it}assets/$assetId" }

    val baseApiUrl: String? get() = activeBaseApiUrl ?: state?.primaryBaseUrl

    /** Scheme + authority of the active server, used as the WebView base URL. */
    val serverOrigin: String?
        get() = baseApiUrl?.let {
            runCatching {
                val u = java.net.URI(it)
                val port = if (u.port != -1) ":${u.port}" else ""
                "${u.scheme}://${u.host}$port"
            }.getOrNull()
        }

    /** Bearer header for a URL, but only if it targets a configured server host. */
    fun authHeaderForUrl(url: String): String? {
        val hosts = state?.hosts ?: return null
        val requestHost = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return null
        return if (requestHost in hosts) authHeader() else null
    }

    companion object {
        /**
         * Turn a user-entered server URL into a Retrofit base URL ending in
         * `/api/v1/`. Tolerant of trailing slashes and a pasted `/api/v1`.
         * An explicit `http://` is preserved; a bare host defaults to `https://`.
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

/**
 * Transparently retries a request against a fallback server when the active one
 * is unreachable, and remembers (stickily) which server last answered so we
 * don't keep timing out on a dead one. Only the scheme/host/port are swapped;
 * the path (".../api/v1/...") is preserved.
 */
class FailoverInterceptor(
    private val primary: HttpUrl,
    private val fallback: HttpUrl,
    private val onActiveBaseChanged: (String) -> Unit,
) : Interceptor {

    @Volatile
    private var useFallback = false

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val first = if (useFallback) fallback else primary
        val second = if (useFallback) primary else fallback
        return try {
            chain.proceed(route(request, first))
        } catch (e: IOException) {
            // The active server is unreachable — flip to the other one and retry.
            useFallback = !useFallback
            onActiveBaseChanged(baseApiUrl(second))
            try {
                chain.proceed(route(request, second))
            } catch (e2: IOException) {
                throw e // surface the original failure if both are down
            }
        }
    }

    private fun route(request: Request, base: HttpUrl): Request {
        val newUrl = request.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()
        return request.newBuilder().url(newUrl).build()
    }

    private fun baseApiUrl(base: HttpUrl): String {
        val port = if (base.port == HttpUrl.defaultPort(base.scheme)) "" else ":${base.port}"
        return "${base.scheme}://${base.host}$port/api/v1/"
    }
}

class NotConnectedException : IllegalStateException("Not connected to a Karakeep server")
