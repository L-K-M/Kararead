package ch.lkmc.kararead.reader

import android.content.Context
import android.webkit.WebResourceResponse
import ch.lkmc.kararead.data.remote.ApiProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serves images referenced inside article HTML through a persistent on-disk
 * cache so opened (and prefetched) articles are fully illustrated offline.
 *
 * - Every http(s) image the WebView requests is fetched via this client and
 *   cached; the same cache is read (cache-only) when there's no connection.
 * - For images hosted on the Karakeep server, the bearer token is injected.
 * - Responses are rewritten to be cacheable regardless of the origin's headers,
 *   so even cache-averse CDNs end up available offline.
 */
@Singleton
class AssetLoader @Inject constructor(
    @ApplicationContext context: Context,
    private val apiProvider: ApiProvider,
) {
    private val assets = context.assets

    private val cache = Cache(
        directory = context.cacheDir.resolve("reader_images"),
        maxSize = 150L * 1024 * 1024,
    )

    private val client = OkHttpClient.Builder()
        .cache(cache)
        .addNetworkInterceptor(ForceCacheInterceptor())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** WebView resource hook: bundled fonts, then images (cached). */
    fun intercept(url: String): WebResourceResponse? {
        fontAsset(url)?.let { return it }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val builder = requestFor(url)
        return try {
            toWebResponse(client.newCall(builder.build()).execute())
        } catch (_: Exception) {
            // Offline (or the host is unreachable) — try the cache only.
            runCatching {
                toWebResponse(
                    client.newCall(builder.cacheControl(CacheControl.FORCE_CACHE).build()).execute(),
                )
            }.getOrNull()
        }
    }

    /**
     * Download every image referenced by [html] (resolved against [baseUrl]) into
     * the cache, so a background-prefetched article reads fully offline. Best
     * effort and bounded; failures are ignored.
     */
    fun prefetchImages(html: String?, baseUrl: String?) {
        if (html.isNullOrBlank()) return
        val urls = runCatching {
            Jsoup.parse(html, baseUrl.orEmpty())
                .select("img[src]")
                .map { it.absUrl("src") }
        }.getOrDefault(emptyList())
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .take(MAX_IMAGES_PER_ARTICLE)

        for (u in urls) {
            runCatching {
                client.newCall(requestFor(u).build()).execute().use { resp ->
                    resp.body?.bytes() // read fully so the cache stores it
                }
            }
        }
    }

    /** Serve a bundled reader font referenced as `…/__krfont/<file>`. */
    private fun fontAsset(url: String): WebResourceResponse? {
        val marker = "/__krfont/"
        val i = url.indexOf(marker)
        if (i < 0) return null
        val name = url.substring(i + marker.length).substringBefore('?').substringBefore('#')
        if (name.isEmpty() || name.contains('/')) return null
        return runCatching {
            WebResourceResponse("font/ttf", null, assets.open("fonts/$name"))
        }.getOrNull()
    }

    private fun requestFor(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        apiProvider.authHeaderForUrl(url)?.let { builder.header("Authorization", it) }
        return builder
    }

    private fun toWebResponse(response: Response): WebResourceResponse? {
        val body = response.body
        if (!response.isSuccessful || body == null) {
            response.close()
            return null
        }
        val rawType = response.header("Content-Type") ?: "application/octet-stream"
        val mime = rawType.substringBefore(";").trim().ifEmpty { "application/octet-stream" }
        return WebResourceResponse(mime, null, body.byteStream())
    }

    /** Make every response cacheable so offline reading isn't at the origin's mercy. */
    private class ForceCacheInterceptor : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): Response =
            chain.proceed(chain.request()).newBuilder()
                .removeHeader("Pragma")
                .removeHeader("Cache-Control")
                .header("Cache-Control", "public, max-age=31536000")
                .build()
    }

    companion object {
        private const val MAX_IMAGES_PER_ARTICLE = 30
    }
}
