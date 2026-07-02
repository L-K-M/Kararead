package ch.lkmc.kararead.util

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A snapshot of the reader's habit stats, surfaced as gentle encouragement. */
data class ReadingStats(
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val todayMinutes: Int = 0,
    val daysReadTotal: Int = 0,
    /**
     * True when the live streak is currently being kept alive by a forgiven
     * quiet day in the past week — so the UI can gently acknowledge it rather
     * than let the user wonder why a missed day didn't reset the count.
     */
    val streakForgivenRecently: Boolean = false,
) {
    val hasAny: Boolean get() = daysReadTotal > 0 || todayMinutes > 0
}

/** A day counts toward a streak once at least this much was read. */
const val STREAK_MIN_SECONDS = 30L

/**
 * How often the streak forgives a "quiet day". A calm app doesn't punish one
 * missed day: a single non-reading day is bridged without breaking the streak,
 * but no more than one forgiven day per this many days — so a whole quiet week
 * still ends it. (D4-adjacent "streak forgiveness token".)
 */
const val STREAK_FORGIVE_EVERY_DAYS = 7L

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

/** One cell of the reading heatmap. [future] cells (after today) render blank. */
data class HeatmapDay(
    val date: LocalDate,
    val minutes: Int,
    val isToday: Boolean,
    val future: Boolean,
)

/**
 * A GitHub-style reading heatmap: [weeks] Monday-started columns ending with the
 * week that contains today, each a full 7 days (Mon…Sun). Days after today fill
 * out the final column as [HeatmapDay.future] so the grid stays rectangular.
 * Returned in column-major order (week by week, each week Mon→Sun).
 */
fun readingHeatmap(
    secondsByDate: Map<String, Long>,
    weeks: Int = 13,
    today: LocalDate = LocalDate.now(),
): List<HeatmapDay> {
    // Monday of the current week (ISO: Mon=1 … Sun=7), then back to the grid's
    // first column so the last column holds today.
    val startOfThisWeek = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val gridStart = startOfThisWeek.minusWeeks((weeks - 1).toLong())
    return (0 until weeks * 7).map { i ->
        val date = gridStart.plusDays(i.toLong())
        val secs = secondsByDate[date.toString()] ?: 0L
        HeatmapDay(
            date = date,
            minutes = (secs / 60).toInt(),
            isToday = date == today,
            future = date.isAfter(today),
        )
    }
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
 * without reading. A single quiet day is forgiven (see [streakEndingAt] and
 * [STREAK_FORGIVE_EVERY_DAYS]) so one missed day doesn't reset the count.
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

    // Current streak: walk back from today (grace: today may still be missing
    // because the day isn't over), forgiving up to one quiet day per week.
    val anchor = if (today in qualifying) today else today.minusDays(1)
    val currentWalk = streakEndingAt(anchor, qualifying)

    // Longest streak across all recorded days, using the same forgiving walk.
    // Every qualifying day is a candidate end; the maximum is reached at each
    // run's true end, so scanning them all finds the longest.
    val longest = qualifying.maxOfOrNull { streakEndingAt(it, qualifying).count } ?: 0

    // A live streak that leans on a quiet day forgiven within the past week.
    val forgivenRecently = currentWalk.count > 0 && currentWalk.latestForgiven?.let {
        ChronoUnit.DAYS.between(it, today) <= STREAK_FORGIVE_EVERY_DAYS
    } == true

    val todaySeconds = secondsByDate[today.toString()] ?: 0L
    return ReadingStats(
        currentStreakDays = currentWalk.count,
        longestStreakDays = longest,
        todayMinutes = (todaySeconds / 60).toInt(),
        daysReadTotal = qualifying.size,
        streakForgivenRecently = forgivenRecently,
    )
}

/** Result of a backward streak walk: reading days counted, and the most recent quiet day forgiven. */
private data class StreakWalk(val count: Int, val latestForgiven: LocalDate?)

/**
 * The length of the reading streak ending on [end], walking backward through
 * [qualifying] days and forgiving at most one quiet (non-reading) day per
 * rolling [STREAK_FORGIVE_EVERY_DAYS]-day window. Only actual reading days are
 * counted — a forgiven day keeps the streak alive but doesn't inflate it, and
 * two quiet days closer together than the window still end the streak.
 */
private fun streakEndingAt(end: LocalDate, qualifying: Set<LocalDate>): StreakWalk {
    var count = 0
    var lastForgiven: LocalDate? = null
    var bridgedForgiven: LocalDate? = null // most-recent quiet day that keeps the streak alive
    var day = end
    while (true) {
        when {
            day in qualifying -> {
                count++
                day = day.minusDays(1)
            }
            // Forgive a quiet day only if the last one we forgave is at least a
            // week further along — at most one per window (walking backward,
            // lastForgiven is always the later date).
            lastForgiven == null ||
                ChronoUnit.DAYS.between(day, lastForgiven) >= STREAK_FORGIVE_EVERY_DAYS -> {
                // The quiet day only *matters* if reading resumes just behind it;
                // a forgiven day past the streak's last read is speculative and
                // never actually held anything together.
                if (bridgedForgiven == null && day.minusDays(1) in qualifying) {
                    bridgedForgiven = day
                }
                lastForgiven = day
                day = day.minusDays(1)
            }
            else -> return StreakWalk(count, bridgedForgiven)
        }
    }
}
