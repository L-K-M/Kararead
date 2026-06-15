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
    fun `streak breaks after two missing days`() {
        val stats = computeReadingStats(mapOf(day(2) to plenty, day(3) to plenty), today)
        assertEquals(0, stats.currentStreakDays)
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
