package ch.lkmc.kararead.data.model

/**
 * Domain models — the clean, UI-facing representation decoupled from the
 * Karakeep wire format (see [ch.lkmc.kararead.data.remote.dto]).
 */

/** A bookmark as the reader cares about it (metadata; body lives in [ReaderArticle]). */
data class Bookmark(
    val id: String,
    val title: String,
    val url: String?,
    val siteName: String?,
    val author: String?,
    val excerpt: String?,
    val faviconUrl: String?,
    /** Resolved hero image URL (asset URL or remote), already absolute. */
    val imageUrl: String?,
    val createdAt: Long,
    val datePublished: Long?,
    val archived: Boolean,
    val favourited: Boolean,
    val tags: List<String>,
    val note: String?,
    val summary: String?,
    /** Estimated minutes to read, when known from cached content. */
    val readingTimeMinutes: Int?,
    val contentType: ContentType,
) {
    val displayTitle: String
        get() = title.ifBlank { url?.let { prettyHost(it) } ?: "Untitled" }
}

enum class ContentType { LINK, TEXT, ASSET, UNKNOWN }

/** Full article ready for the reader: metadata + readable HTML body. */
data class ReaderArticle(
    val bookmark: Bookmark,
    val htmlContent: String?,
    val textContent: String?,
)

/** Where a queue of bookmarks comes from. */
sealed interface BookmarkSource {
    data object Inbox : BookmarkSource          // unread (archived = false)
    data object Favourites : BookmarkSource
    data object Archive : BookmarkSource         // already read / done
    data class ListSource(val listId: String, val name: String) : BookmarkSource
    data class TagSource(val tagId: String, val name: String) : BookmarkSource
    data class SearchSource(val query: String) : BookmarkSource
}

data class KarakeepList(
    val id: String,
    val name: String,
    val icon: String,
    val description: String?,
    val type: String,
    val parentId: String?,
)

data class Tag(
    val id: String,
    val name: String,
    val count: Int,
)

data class Highlight(
    val id: String,
    val bookmarkId: String,
    val startOffset: Int,
    val endOffset: Int,
    val color: String,
    val text: String?,
    val note: String?,
)

data class CurrentUser(val id: String, val name: String?, val email: String?)

/** Connection settings the user supplies during onboarding. */
data class ConnectionSettings(
    val serverUrl: String,
    val apiKey: String,
    /** Optional second server tried automatically when the main one is unreachable. */
    val fallbackUrl: String = "",
) {
    val isComplete: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
    val hasFallback: Boolean get() = fallbackUrl.isNotBlank()
}

/** Reader typography & appearance preferences. */
data class ReaderPreferences(
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val font: ReaderFont = ReaderFont.SERIF,
    val fontScale: Float = 1.0f,        // 0.7 .. 2.0 multiplier
    val lineHeight: Float = 1.6f,       // unitless
    val horizontalMargin: Int = 20,     // dp, mapped to px in CSS
    val justify: Boolean = false,
    val keepScreenOn: Boolean = false,
    val volumeKeyPaging: Boolean = true,
    val pagedMode: Boolean = false,
)

enum class ReaderTheme { LIGHT, SEPIA, DARK, BLACK }

/**
 * Reader typeface options. The stacks lean on families Android's WebView ships
 * (Noto Serif, Roboto + its condensed/casual variants, monospace) so each looks
 * distinct without bundling font files.
 */
enum class ReaderFont(val cssStack: String) {
    SERIF("Georgia, 'Noto Serif', 'Times New Roman', serif"),
    SANS("'Roboto', 'Helvetica Neue', sans-serif"),
    CONDENSED("'Roboto Condensed', 'sans-serif-condensed', sans-serif"),
    SLAB("'Roboto Slab', 'Rockwell', Georgia, serif"),
    HUMANIST("'Optima', 'Gill Sans', 'Segoe UI', sans-serif"),
    MONO("'JetBrains Mono', 'Courier New', monospace"),
    CASUAL("'casual', 'Comic Sans MS', cursive"),
    SYSTEM("system-ui, sans-serif"),
}

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

enum class QueueSort { NEWEST, OLDEST }

/**
 * Automatic offline-download preferences. Kararead keeps the top [keepCount]
 * unread articles downloaded so there's always something to read offline, and
 * removes them as you mark them read.
 */
data class OfflinePreferences(
    val enabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val keepCount: Int = 20,
)

/** Local, client-side reading progress (Karakeep has no server-side field). */
data class ReadingProgress(
    val bookmarkId: String,
    val fraction: Float,   // 0f .. 1f scroll position
    val updatedAt: Long,
    val anchor: String? = null,   // block anchor for accurate restore
)

/** A recently-opened article, for the library's quick "jump back in" strip. */
data class RecentArticle(
    val id: String,
    val title: String,
    val imageUrl: String?,
    val fraction: Float,
)

/** Strip protocol/www for compact display, e.g. "https://www.bbc.com/x" -> "bbc.com". */
fun prettyHost(url: String): String = runCatching {
    val host = java.net.URI(url).host ?: return url
    host.removePrefix("www.")
}.getOrDefault(url)
