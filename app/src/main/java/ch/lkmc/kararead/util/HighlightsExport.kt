package ch.lkmc.kararead.util

import ch.lkmc.kararead.data.model.Highlight

/**
 * Render an article's highlights as Markdown — a title, the source URL, then each
 * highlight as a blockquote (with its note, if any), in reading order.
 */
fun highlightsToMarkdown(title: String, url: String?, highlights: List<Highlight>): String {
    val quotes = highlights
        .sortedBy { it.startOffset }
        .mapNotNull { h ->
            val text = h.text?.trim().orEmpty()
            if (text.isEmpty()) {
                null
            } else {
                buildString {
                    append("> ").append(text.replace("\n", "\n> "))
                    h.note?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\n\n").append(it) }
                }
            }
        }

    return buildString {
        append("# ").append(title).append('\n')
        if (!url.isNullOrBlank()) append(url).append('\n')
        if (quotes.isNotEmpty()) {
            append('\n')
            append(quotes.joinToString("\n\n"))
            append('\n')
        }
    }
}

/** One article's worth of highlights, for combined export. */
data class HighlightCollection(
    val title: String,
    val url: String?,
    val highlights: List<Highlight>,
)

/**
 * Render several articles' highlights into a single Markdown document, each
 * article as its own section separated by a horizontal rule. Articles whose
 * highlights are all note-less and text-less are skipped.
 */
fun highlightsToMarkdown(collections: List<HighlightCollection>): String =
    collections
        .map { highlightsToMarkdown(it.title, it.url, it.highlights).trimEnd() }
        // Keep only sections that actually rendered a quote (skip title-only ones).
        .filter { it.contains("> ") }
        .joinToString("\n\n---\n\n")
        .let { if (it.isEmpty()) it else it + "\n" }
