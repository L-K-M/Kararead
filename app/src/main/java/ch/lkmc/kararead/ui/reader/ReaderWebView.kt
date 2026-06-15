package ch.lkmc.kararead.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
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
 * Lets the host (e.g. the reader screen's volume-key handler) page the WebView
 * up or down without holding a direct reference to the view. [direction] is -1
 * to page up (towards the start) and +1 to page down.
 */
class ReaderPager {
    internal var pageBy: ((Int) -> Unit)? = null
    fun page(direction: Int) {
        pageBy?.invoke(direction)
    }
}

/** A WebView that adds a "Highlight" item to the text-selection action menu. */
private class HighlightWebView(context: Context) : WebView(context) {
    var onHighlightRequested: (() -> Unit)? = null

    override fun startActionMode(callback: ActionMode.Callback): ActionMode =
        super.startActionMode(wrap(callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode =
        super.startActionMode(wrap(callback), type)

    private fun wrap(inner: ActionMode.Callback) = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val res = inner.onCreateActionMode(mode, menu)
            menu.add(Menu.NONE, HIGHLIGHT_ITEM_ID, 0, "Highlight")
            return res
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
            inner.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId == HIGHLIGHT_ITEM_ID) {
                onHighlightRequested?.invoke()
                mode.finish()
                return true
            }
            return inner.onActionItemClicked(mode, item)
        }

        override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)
    }

    companion object {
        private const val HIGHLIGHT_ITEM_ID = 0x6B72 // 'kr'
    }
}

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
    onTap: () -> Unit,
    onSelection: (text: String, start: Int, end: Int) -> Unit,
    onHighlightTap: (id: String) -> Unit,
    highlightsJson: String,
    pager: ReaderPager,
    modifier: Modifier = Modifier,
) {
    val palette = remember(prefs.theme) { ReaderHtmlBuilder.paletteFor(prefs.theme) }
    // Build the document once per article; preference changes are applied via JS.
    val html = remember(article.bookmark.id, baseUrl) {
        ReaderHtmlBuilder.build(article, prefs, baseUri = baseUrl)
    }

    val bridge: ReaderBridge = remember {
        ReaderBridge(
            onProgress = onProgress,
            onScrollDirection = onScrollDirection,
            onTap = onTap,
            onSelection = onSelection,
            onHighlightTap = onHighlightTap,
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            HighlightWebView(ctx).apply {
                onHighlightRequested = {
                    evaluateJavascript("window.krCaptureSelection && window.krCaptureSelection();", null)
                }
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

                bridge.onReady = {
                    restore(initialProgress)
                    applyHighlights(highlightsJson)
                }
                addJavascriptInterface(bridge, "AndroidReader")
                pager.pageBy = { dir -> evaluateJavascript("window.krPageBy && window.krPageBy($dir);", null) }

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
            webView.applyHighlights(highlightsJson)
        },
        onRelease = {
            pager.pageBy = null
            it.destroy()
        },
    )
}

private fun WebView.restore(fraction: Float) {
    if (fraction <= 0.001f) return
    // Defer a touch so layout (and most images) have settled.
    postDelayed({ evaluateJavascript("window.krRestore && window.krRestore($fraction);", null) }, 120)
}

private fun WebView.applyHighlights(json: String) {
    // JSON is an array literal; safe to inline as a JS string argument.
    val arg = org.json.JSONObject.quote(json)
    evaluateJavascript("window.krApplyHighlights && window.krApplyHighlights($arg);", null)
}

private fun safeColor(hex: String): Int =
    runCatching { AndroidColor.parseColor(hex) }.getOrDefault(AndroidColor.WHITE)

/** JS → Android bridge for scroll progress. */
internal class ReaderBridge(
    private val onProgress: (Float) -> Unit,
    private val onScrollDirection: (Boolean) -> Unit,
    private val onTap: () -> Unit,
    private val onSelection: (text: String, start: Int, end: Int) -> Unit,
    private val onHighlightTap: (id: String) -> Unit,
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

    @JavascriptInterface
    fun onTap() {
        mainHandler.post { onTap() }
    }

    @JavascriptInterface
    fun onSelection(text: String, start: Int, end: Int) {
        mainHandler.post { onSelection(text, start, end) }
    }

    @JavascriptInterface
    fun onHighlightTap(id: String) {
        mainHandler.post { onHighlightTap(id) }
    }
}
