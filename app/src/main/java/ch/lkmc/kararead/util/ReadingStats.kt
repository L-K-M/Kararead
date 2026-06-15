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

/** A day's reading minutes for the stats chart. */
data class DayMinutes(
    val date: LocalDate,
    val minutes: Int,
    val isToday: Boolean,
)

/**
 * A continuous series of the last [days] calendar days ending today (oldest
 * first), filling days with no reading as zero — for the stats bar chart.
 */
fun recentDaysSeries(
    secondsByDate: Map<String, Long>,
    days: Int,
    today: LocalDate = LocalDate.now(),
): List<DayMinutes> = (days - 1 downTo 0).map { offset ->
    val date = today.minusDays(offset.toLong())
    val secs = secondsByDate[date.toString()] ?: 0L
    DayMinutes(date = date, minutes = (secs / 60).toInt(), isToday = offset == 0)
}

/** Minutes read over the trailing [days] days (inclusive of today). */
fun minutesInLastDays(
    secondsByDate: Map<String, Long>,
    days: Int,
    today: LocalDate = LocalDate.now(),
): Int {
    val cutoff = today.minusDays((days - 1).toLong())
    return secondsByDate.entries.sumOf { (date, secs) ->
        val d = runCatching { LocalDate.parse(date) }.getOrNull()
        if (d != null && !d.isBefore(cutoff) && !d.isAfter(today)) (secs / 60).toInt() else 0
    }
}

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
