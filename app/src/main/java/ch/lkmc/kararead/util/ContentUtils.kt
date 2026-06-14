package ch.lkmc.kararead.util

import org.jsoup.Jsoup
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Parse an ISO-8601 date-time string to epoch millis, or null. */
fun parseIsoToMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        // Fall back to offset/local date-time variants.
        runCatching {
            Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(value)).toEpochMilli()
        }.getOrNull()
    }
}

private const val WORDS_PER_MINUTE = 220.0

/** Estimate reading time in whole minutes (min 1) from plain text. */
fun estimateReadingMinutes(text: String?): Int? {
    if (text.isNullOrBlank()) return null
    val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    if (words == 0) return null
    return maxOf(1, Math.round(words / WORDS_PER_MINUTE).toInt())
}

/** Strip HTML to plain text for word counting / excerpts. */
fun htmlToPlainText(html: String?): String? {
    if (html.isNullOrBlank()) return null
    return runCatching { Jsoup.parse(html).text() }.getOrNull()
}

/** Build a short excerpt from text, trimmed at a word boundary. */
fun excerptFrom(text: String?, maxChars: Int = 220): String? {
    val clean = text?.trim()?.replace(Regex("\\s+"), " ") ?: return null
    if (clean.isEmpty()) return null
    if (clean.length <= maxChars) return clean
    val cut = clean.substring(0, maxChars)
    val lastSpace = cut.lastIndexOf(' ')
    return (if (lastSpace > 0) cut.substring(0, lastSpace) else cut).trimEnd() + "…"
}
