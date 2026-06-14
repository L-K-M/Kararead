package ch.lkmc.kararead.reader

import android.webkit.WebResourceResponse
import ch.lkmc.kararead.data.remote.ApiProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serves images and other media referenced inside article HTML that live on the
 * Karakeep server (e.g. cached `/api/assets/..`), injecting the bearer token the
 * WebView wouldn't otherwise send. Third-party remote URLs are left to the
 * WebView to load normally (returns null → default handling).
 */
@Singleton
class AssetLoader @Inject constructor(
    private val apiProvider: ApiProvider,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun intercept(url: String): WebResourceResponse? {
        val auth = apiProvider.authHeaderForUrl(url) ?: return null
        return try {
            val request = Request.Builder().url(url).header("Authorization", auth).build()
            val response = client.newCall(request).execute()
            val body = response.body ?: return null
            if (!response.isSuccessful) {
                body.close()
                return null
            }
            val rawType = response.header("Content-Type") ?: "application/octet-stream"
            val mime = rawType.substringBefore(";").trim().ifEmpty { "application/octet-stream" }
            WebResourceResponse(mime, null, body.byteStream())
        } catch (_: Exception) {
            null
        }
    }
}
