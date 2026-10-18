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
            val note = h.note?.trim().orEmpty()
            when {
                text.isNotEmpty() -> buildString {
                    append("> ").append(text.replace("\n", "\n> "))
                    if (note.isNotEmpty()) append("\n\n").append(note)
                }
                // Other Karakeep clients can create text-less highlights that
                // carry a note; dropping them lost the note from every export.
                note.isNotEmpty() -> note
                else -> null
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
        // Keep only sections with any content (text or a note) to render.
        .filter { c -> c.highlights.any { !it.text.isNullOrBlank() || !it.note.isNullOrBlank() } }
        .map { highlightsToMarkdown(it.title, it.url, it.highlights).trimEnd() }
        .joinToString("\n\n---\n\n")
        .let { if (it.isEmpty()) it else it + "\n" }
