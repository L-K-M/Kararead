package ch.lkmc.kararead.util

import java.time.LocalDate

/** A snapshot of the reader's habit stats, surfaced as gentle encouragement. */
data class ReadingStats(
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val todayMinutes: Int = 0,
    val daysReadTotal: Int = 0,
) {
    val hasAny: Boolean get() = daysReadTotal > 0 || todayMinutes > 0
}

/** A day counts toward a streak once at least this much was read. */
const val STREAK_MIN_SECONDS = 30L

/**
 * Compute streak/minutes stats from per-day reading seconds.
 *
 * @param secondsByDate reading seconds keyed by ISO local date (`yyyy-MM-dd`).
 * @param today the current local date (injectable for tests).
 *
 * The current streak counts consecutive qualifying days ending today — or
 * yesterday, so a streak isn't considered broken until a whole day passes
 * without reading.
 */
fun computeReadingStats(
    secondsByDate: Map<String, Long>,
    today: LocalDate = LocalDate.now(),
): ReadingStats {
    val qualifying: Set<LocalDate> = secondsByDate
        .filterValues { it >= STREAK_MIN_SECONDS }
        .keys
        .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        .toSet()

    // Current streak: walk back from today (grace: allow today itself missing).
    var current = 0
    run {
        var day = if (today in qualifying) today else today.minusDays(1)
        if (day in qualifying) {
            while (day in qualifying) {
                current++
                day = day.minusDays(1)
            }
        }
    }

    // Longest streak across all recorded days.
    var longest = 0
    for (day in qualifying) {
        if (day.minusDays(1) in qualifying) continue // not a run start
        var run = 0
        var d = day
        while (d in qualifying) {
            run++
            d = d.plusDays(1)
        }
        if (run > longest) longest = run
    }

    val todaySeconds = secondsByDate[today.toString()] ?: 0L
    return ReadingStats(
        currentStreakDays = current,
        longestStreakDays = longest,
        todayMinutes = (todaySeconds / 60).toInt(),
        daysReadTotal = qualifying.size,
    )
}
