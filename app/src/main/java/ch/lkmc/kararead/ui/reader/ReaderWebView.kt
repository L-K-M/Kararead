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
    internal var scrollToFraction: ((Float) -> Unit)? = null
    fun page(direction: Int) {
        pageBy?.invoke(direction)
    }

    /** Smoothly scroll the article to a 0..1 position (used to follow narration). */
    fun scrollTo(fraction: Float) {
        scrollToFraction?.invoke(fraction)
    }
}

/** A WebView that adds a "Highlight" item to the text-selection action menu. */
private class HighlightWebView(context: Context) : WebView(context) {
    var onHighlightRequested: (() -> Unit)? = null
    /** Confirmed single tap by horizontal zone: -1 left, 0 centre, +1 right. */
    var onTapZone: ((Int) -> Unit)? = null

    // A confirmed single tap (not a scroll, fling, double-tap or long-press).
    // Left/right thirds page; the centre toggles the reading chrome. Native
    // detection is far more reliable than a JS click listener, which the
    // WebView's own gesture handling can swallow.
    private val tapDetector = android.view.GestureDetector(
        context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                val w = width
                val zone = when {
                    w <= 0 -> 0
                    e.x < w * 0.25f -> -1
                    e.x > w * 0.75f -> 1
                    else -> 0
                }
                onTapZone?.invoke(zone)
                return false
            }
        },
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        tapDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode =
        super.startActionMode(wrap(callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode =
        super.startActionMode(wrap(callback), type)

    private fun wrap(inner: ActionMode.Callback) = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val res = inner.onCreateActionMode(mode, menu)
            menu.add(Menu.NONE, HIGHLIGHT_ITEM_ID, 0, "Highlight")
            android.util.Log.d("KrHighlight", "onCreateActionMode: added Highlight item (inner=$res)")
            return res
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
            inner.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            android.util.Log.d("KrHighlight", "onActionItemClicked: id=${item.itemId} (ours=$HIGHLIGHT_ITEM_ID)")
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
    initialAnchor: String?,
    assetLoader: AssetLoader,
    onProgress: (fraction: Float, anchor: String) -> Unit,
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
            onSelection = onSelection,
            onHighlightTap = onHighlightTap,
        )
    }

    // Tracks what we've already pushed into the page. The AndroidView [update]
    // lambda runs on *every* recomposition (and the reader recomposes on each
    // scroll-progress tick), so without this gate we'd re-run the highlight
    // renderer constantly — which rewrites the article's text nodes and
    // collapses/jumps any selection the reader is in the middle of making.
    val applied = remember { AppliedReaderState() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            HighlightWebView(ctx).apply {
                onTapZone = { zone ->
                    when (zone) {
                        -1 -> pager.page(-1) // tap left: page back
                        1 -> pager.page(1)   // tap right: page forward
                        else -> onTap()      // centre: toggle chrome
                    }
                }
                onHighlightRequested = {
                    android.util.Log.d("KrHighlight", "onHighlightRequested -> krCaptureSelection")
                    evaluateJavascript(
                        "window.krCaptureSelection ? window.krCaptureSelection() : 'NO_FN';",
                    ) { result -> android.util.Log.d("KrHighlight", "krCaptureSelection returned $result") }
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                // Allow pinch-to-zoom (no on-screen buttons) so diagrams, wide
                // tables and small images can be enlarged — an accessibility win.
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                // Self-hosted servers (especially via the http fallback) often
                // serve http-hosted images even on an https page; let them load
                // rather than be blocked as mixed content. Bodies are sanitized
                // and the asset loader fetches images itself.
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.textZoom = 100
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                setBackgroundColor(safeColor(palette.background))

                bridge.onReady = {
                    applied.ready = true
                    restore(initialProgress, initialAnchor)
                    // Apply the latest desired state now that the document (and
                    // its JS hooks) actually exist; the [update] lambda may have
                    // run before the page finished loading.
                    applied.desiredPrefs?.let { p ->
                        applied.appliedPrefs = p
                        evaluateJavascript(
                            ReaderHtmlBuilder.applyPrefsScript(ReaderHtmlBuilder.paletteFor(p.theme), p),
                            null,
                        )
                    }
                    applied.appliedHighlights = applied.desiredHighlights
                    applyHighlights(applied.desiredHighlights)
                }
                addJavascriptInterface(bridge, "AndroidReader")
                pager.pageBy = { dir -> evaluateJavascript("window.krPageBy && window.krPageBy($dir);", null) }
                pager.scrollToFraction = { f ->
                    evaluateJavascript("window.krSmoothToFraction && window.krSmoothToFraction($f);", null)
                }

                // Diagnostic: surface the reader page's console.log/error in logcat
                // (tag KrHighlightJS) so the highlight capture path can be traced.
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(cm: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.i(
                            "KrHighlightJS",
                            "${cm.message()} @${cm.sourceId()}:${cm.lineNumber()}",
                        )
                        return true
                    }
                }
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
            // Record the latest desired state; it's pushed to the page once it's
            // ready (above) and thereafter only when it actually changes — never
            // on the recompositions that merely report scroll progress.
            applied.desiredPrefs = prefs
            applied.desiredHighlights = highlightsJson
            webView.setBackgroundColor(safeColor(palette.background))
            if (applied.ready) {
                if (applied.appliedPrefs != prefs) {
                    applied.appliedPrefs = prefs
                    webView.evaluateJavascript(ReaderHtmlBuilder.applyPrefsScript(palette, prefs), null)
                }
                if (applied.appliedHighlights != highlightsJson) {
                    applied.appliedHighlights = highlightsJson
                    webView.applyHighlights(highlightsJson)
                }
            }
        },
        onRelease = {
            pager.pageBy = null
            pager.scrollToFraction = null
            it.destroy()
        },
    )
}

private fun WebView.restore(fraction: Float, anchor: String?) {
    if (fraction <= 0.001f && anchor.isNullOrEmpty()) return
    // Defer a touch so the initial layout has settled; prefer the block anchor
    // (which re-pins itself as late images load) and fall back to the fraction.
    postDelayed({
        if (!anchor.isNullOrEmpty()) {
            val arg = org.json.JSONObject.quote(anchor)
            evaluateJavascript("window.krRestoreAnchor && window.krRestoreAnchor($arg);", null)
        } else {
            evaluateJavascript("window.krRestore && window.krRestore($fraction);", null)
        }
    }, 120)
}

private fun WebView.applyHighlights(json: String) {
    // JSON is an array literal; safe to inline as a JS string argument.
    val arg = org.json.JSONObject.quote(json)
    evaluateJavascript("window.krApplyHighlights && window.krApplyHighlights($arg);", null)
}

private fun safeColor(hex: String): Int =
    runCatching { AndroidColor.parseColor(hex) }.getOrDefault(AndroidColor.WHITE)

/**
 * Mutable scratch state remembered across recompositions so the [AndroidView]
 * `update` lambda can tell "the preferences/highlights actually changed" apart
 * from "we recomposed for an unrelated reason" (most often a scroll-progress
 * tick). `desired*` is the newest value seen during composition; `applied*` is
 * what has actually been pushed into the WebView. `ready` flips true once the
 * document has loaded and its JS hooks exist.
 */
private class AppliedReaderState {
    var ready = false
    var desiredPrefs: ReaderPreferences? = null
    var appliedPrefs: ReaderPreferences? = null
    var desiredHighlights: String = "[]"
    var appliedHighlights: String? = null
}

/** JS → Android bridge for scroll progress. */
internal class ReaderBridge(
    private val onProgress: (fraction: Float, anchor: String) -> Unit,
    private val onScrollDirection: (Boolean) -> Unit,
    private val onSelection: (text: String, start: Int, end: Int) -> Unit,
    private val onHighlightTap: (id: String) -> Unit,
) {
    var onReady: (() -> Unit)? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @JavascriptInterface
    fun onProgress(fraction: Double, anchor: String, up: Boolean) {
        mainHandler.post {
            onProgress(fraction.toFloat(), anchor)
            onScrollDirection(up)
        }
    }

    @JavascriptInterface
    fun onReady() {
        mainHandler.post { onReady?.invoke() }
    }

    @JavascriptInterface
    fun onSelection(text: String, start: Int, end: Int) {
        android.util.Log.d("KrHighlight", "bridge.onSelection start=$start end=$end len=${text.length}")
        mainHandler.post { onSelection(text, start, end) }
    }

    @JavascriptInterface
    fun onHighlightTap(id: String) {
        mainHandler.post { onHighlightTap(id) }
    }
}
