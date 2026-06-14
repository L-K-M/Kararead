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

private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

/** Extract the first http(s) URL from shared text (which may include a title). */
fun extractFirstUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    return URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')
}

/** Compact, friendly date label: "today", "yesterday", "3d ago", or "MMM d". */
fun formatShortDate(millis: Long?, now: Long = System.currentTimeMillis()): String? {
    if (millis == null || millis <= 0) return null
    val days = (now - millis) / (24L * 60 * 60 * 1000)
    return when {
        days < 0 -> null
        days == 0L -> "today"
        days == 1L -> "yesterday"
        days < 7 -> "${days}d ago"
        else -> runCatching {
            val dt = java.time.Instant.ofEpochMilli(millis)
                .atZone(java.time.ZoneId.systemDefault())
            val pattern = if (now - millis > 330L * 24 * 60 * 60 * 1000) "MMM d, yyyy" else "MMM d"
            dt.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
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

/**
 * Estimated whole minutes left to read, given the total reading time and the
 * current scroll fraction (0..1). Null when unknown; 0 when effectively done.
 */
fun minutesLeft(totalMinutes: Int?, fraction: Float): Int? {
    if (totalMinutes == null || totalMinutes <= 0) return null
    val remaining = totalMinutes * (1f - fraction.coerceIn(0f, 1f))
    if (remaining <= 0.5f) return 0
    return Math.round(remaining)
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
