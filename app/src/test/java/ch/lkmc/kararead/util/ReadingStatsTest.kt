package ch.lkmc.kararead.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ReadingStatsTest {

    private val today = LocalDate.of(2026, 6, 15)
    private fun day(offset: Long) = today.minusDays(offset).toString()
    private val plenty = 600L // well over the streak threshold

    @Test
    fun `empty input yields zeroed stats`() {
        val stats = computeReadingStats(emptyMap(), today)
        assertEquals(0, stats.currentStreakDays)
        assertEquals(0, stats.todayMinutes)
        assertEquals(0, stats.daysReadTotal)
        assertEquals(false, stats.hasAny)
    }

    @Test
    fun `counts a consecutive run ending today`() {
        val stats = computeReadingStats(
            mapOf(day(0) to plenty, day(1) to plenty, day(2) to plenty),
            today,
        )
        assertEquals(3, stats.currentStreakDays)
        assertEquals(10, stats.todayMinutes)
        assertEquals(3, stats.daysReadTotal)
    }

    @Test
    fun `streak survives a missing today if yesterday counts`() {
        val stats = computeReadingStats(mapOf(day(1) to plenty, day(2) to plenty), today)
        assertEquals(2, stats.currentStreakDays)
        assertEquals(0, stats.todayMinutes)
    }

    @Test
    fun `two consecutive quiet days break the streak`() {
        // Read today, then a two-day silence: one day is forgiven, the second
        // (within the same week) ends the streak — so the earlier run doesn't
        // stay attached to today.
        val stats = computeReadingStats(
            mapOf(day(0) to plenty, day(3) to plenty, day(4) to plenty),
            today,
        )
        assertEquals(1, stats.currentStreakDays)  // just today
        assertEquals(2, stats.longestStreakDays)  // the day3–day4 run
    }

    @Test
    fun `a single quiet day inside the run is forgiven`() {
        // Read today, skipped yesterday, read the two days before that.
        val stats = computeReadingStats(
            mapOf(day(0) to plenty, day(2) to plenty, day(3) to plenty),
            today,
        )
        assertEquals(3, stats.currentStreakDays)
    }

    @Test
    fun `a quiet yesterday is forgiven even when today is still blank`() {
        // Nothing read today (the day isn't over), yesterday was the quiet day,
        // and the two days before it count — the streak stays alive.
        val stats = computeReadingStats(mapOf(day(2) to plenty, day(3) to plenty), today)
        assertEquals(2, stats.currentStreakDays)
    }

    @Test
    fun `only one quiet day is forgiven per week`() {
        // Two gaps three days apart: the first is forgiven, the second (still
        // within the week) breaks the streak despite later reading days.
        val stats = computeReadingStats(
            mapOf(
                day(0) to plenty, day(2) to plenty, day(4) to plenty, day(5) to plenty,
            ),
            today,
        )
        assertEquals(2, stats.currentStreakDays) // today + day2, then the day3 gap stops it
    }

    @Test
    fun `a bridged quiet day is flagged, an unbroken run is not`() {
        val forgiven = computeReadingStats(
            mapOf(day(0) to plenty, day(2) to plenty, day(3) to plenty), today,
        )
        assertEquals(true, forgiven.streakForgivenRecently)

        // A clean run ending today never leaned on forgiveness.
        val clean = computeReadingStats(
            mapOf(day(0) to plenty, day(1) to plenty, day(2) to plenty), today,
        )
        assertEquals(false, clean.streakForgivenRecently)
    }

    @Test
    fun `quiet days more than a week apart are each forgiven`() {
        val data = buildMap {
            put(day(0), plenty)
            // day1 is a quiet day (forgiven)
            for (d in 2L..7L) put(day(d), plenty)
            // day8 is a second quiet day, a full week after the first (forgiven)
            put(day(9), plenty)
            put(day(10), plenty)
        }
        val stats = computeReadingStats(data, today)
        assertEquals(9, stats.currentStreakDays) // 9 reading days, 2 forgiven
    }

    @Test
    fun `days below the threshold do not count`() {
        val stats = computeReadingStats(mapOf(day(0) to 5L), today)
        assertEquals(0, stats.currentStreakDays)
        assertEquals(0, stats.daysReadTotal)
    }

    @Test
    fun `recentDaysSeries returns a continuous run ending today, filling gaps`() {
        val series = recentDaysSeries(
            mapOf(day(0) to 600L, day(2) to 1200L),
            days = 4,
            today = today,
        )
        assertEquals(4, series.size)
        assertEquals(today.minusDays(3), series.first().date) // oldest first
        assertEquals(today, series.last().date)
        assertEquals(true, series.last().isToday)
        assertEquals(10, series.last().minutes) // today: 600s → 10 min
        assertEquals(0, series[2].minutes) // yesterday: no reading
        assertEquals(20, series[1].minutes) // 2 days ago: 1200s → 20 min
    }

    @Test
    fun `minutesInLastDays sums only the trailing window`() {
        val data = mapOf(day(0) to 600L, day(3) to 600L, day(10) to 600L)
        assertEquals(20, minutesInLastDays(data, days = 7, today = today)) // day0 + day3
    }

    @Test
    fun `heatmap is a full week-aligned grid ending with today's week`() {
        // today = 2026-06-15 is a Monday, so it starts the final column.
        val data = mapOf(day(0) to 300L, day(7) to 600L)
        val grid = readingHeatmap(data, weeks = 4, today = today)

        assertEquals(28, grid.size)
        val todayCell = grid[21] // first day of the last (4th) week
        assertEquals(today, todayCell.date)
        assertEquals(true, todayCell.isToday)
        assertEquals(5, todayCell.minutes) // 300s → 5 min
        // A week ago (also a Monday) sits one column earlier.
        assertEquals(10, grid[14].minutes) // 600s → 10 min
        // The rest of the final column is after today.
        assertEquals(true, grid[22].future)
        assertEquals(false, todayCell.future)
    }

    @Test
    fun `longest streak spans a historical run`() {
        val stats = computeReadingStats(
            mapOf(
                day(10) to plenty, day(11) to plenty, day(12) to plenty, day(13) to plenty,
                day(0) to plenty,
            ),
            today,
        )
        assertEquals(1, stats.currentStreakDays)
        assertEquals(4, stats.longestStreakDays)
        assertEquals(5, stats.daysReadTotal)
    }
}
