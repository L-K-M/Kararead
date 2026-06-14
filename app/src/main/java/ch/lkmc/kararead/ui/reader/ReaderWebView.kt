package ch.lkmc.kararead.ui.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import ch.lkmc.kararead.data.model.ReaderArticle
import ch.lkmc.kararead.data.model.ReaderPreferences
import ch.lkmc.kararead.reader.AssetLoader
import ch.lkmc.kararead.reader.ReaderHtmlBuilder

/**
 * Renders an article in a WebView using a hand-tuned reader stylesheet.
 * Reports/restores scroll progress through a JS bridge and injects auth for
 * server-hosted images.
 */
// JavascriptInterface: ReaderBridge's methods are annotated with
// @JavascriptInterface; lint can't track the type through the Compose closure.
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ReaderWebView(
    article: ReaderArticle,
    prefs: ReaderPreferences,
    baseUrl: String?,
    initialProgress: Float,
    assetLoader: AssetLoader,
    onProgress: (Float) -> Unit,
    onScrollDirection: (up: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = remember(prefs.theme) { ReaderHtmlBuilder.paletteFor(prefs.theme) }
    // Build the document once per article; preference changes are applied via JS.
    val html = remember(article.bookmark.id, baseUrl) {
        ReaderHtmlBuilder.build(article, prefs, baseUri = baseUrl)
    }

    val bridge: ReaderBridge = remember {
        ReaderBridge(onProgress = onProgress, onScrollDirection = onScrollDirection)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.textZoom = 100
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                setBackgroundColor(safeColor(palette.background))

                bridge.onReady = { restore(initialProgress) }
                addJavascriptInterface(bridge, "AndroidReader")

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        return assetLoader.intercept(request.url.toString())
                            ?: super.shouldInterceptRequest(view, request)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url
                        return if (url.scheme == "http" || url.scheme == "https") {
                            runCatching {
                                view.context.startActivity(
                                    Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                            true
                        } else {
                            false
                        }
                    }
                }
                loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
        update = { webView ->
            webView.setBackgroundColor(safeColor(palette.background))
            val script = ReaderHtmlBuilder.applyPrefsScript(palette, prefs)
            webView.evaluateJavascript(script, null)
        },
        onRelease = { it.destroy() },
    )
}

private fun WebView.restore(fraction: Float) {
    if (fraction <= 0.001f) return
    // Defer a touch so layout (and most images) have settled.
    postDelayed({ evaluateJavascript("window.krRestore && window.krRestore($fraction);", null) }, 120)
}

private fun safeColor(hex: String): Int =
    runCatching { AndroidColor.parseColor(hex) }.getOrDefault(AndroidColor.WHITE)

/** JS → Android bridge for scroll progress. */
internal class ReaderBridge(
    private val onProgress: (Float) -> Unit,
    private val onScrollDirection: (Boolean) -> Unit,
) {
    var onReady: (() -> Unit)? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @JavascriptInterface
    fun onProgress(fraction: Double, up: Boolean) {
        mainHandler.post {
            onProgress(fraction.toFloat())
            onScrollDirection(up)
        }
    }

    @JavascriptInterface
    fun onReady() {
        mainHandler.post { onReady?.invoke() }
    }
}
