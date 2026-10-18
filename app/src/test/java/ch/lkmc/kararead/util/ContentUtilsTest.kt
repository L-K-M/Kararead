package ch.lkmc.kararead.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentUtilsTest {

    @Test
    fun `parseIsoToMillis parses zulu timestamp`() {
        val millis = parseIsoToMillis("2024-01-01T00:00:00.000Z")
        assertEquals(1704067200000L, millis)
    }

    @Test
    fun `parseIsoToMillis parses offset timestamp`() {
        val millis = parseIsoToMillis("2024-01-01T01:00:00+01:00")
        assertEquals(1704067200000L, millis)
    }

    @Test
    fun `parseIsoToMillis returns null for blank or garbage`() {
        assertNull(parseIsoToMillis(null))
        assertNull(parseIsoToMillis(""))
        assertNull(parseIsoToMillis("not-a-date"))
    }

    @Test
    fun `estimateReadingMinutes rounds to whole minutes with a floor of one`() {
        assertNull(estimateReadingMinutes(null))
        assertNull(estimateReadingMinutes("   "))
        assertEquals(1, estimateReadingMinutes("just a few words here"))
        val longText = (1..660).joinToString(" ") { "word" } // ~3 minutes at 220 wpm
        assertEquals(3, estimateReadingMinutes(longText))
    }

    @Test
    fun `htmlToPlainText strips tags`() {
        val text = htmlToPlainText("<p>Hello <b>world</b></p><p>Again</p>")
        assertEquals("Hello world Again", text)
    }

    @Test
    fun `excerptFrom trims at a word boundary and adds ellipsis`() {
        val excerpt = excerptFrom("one two three four five", maxChars = 11)
        assertTrue(excerpt!!.endsWith("…"))
        assertTrue(excerpt.length <= 12)
        assertTrue(excerpt.startsWith("one two"))
    }

    @Test
    fun `excerptFrom returns whole short text unchanged`() {
        assertEquals("short", excerptFrom("short", maxChars = 100))
    }

    @Test
    fun `minutesLeft scales with progress`() {
        assertNull(minutesLeft(null, 0f))
        assertNull(minutesLeft(0, 0.5f))
        assertEquals(10, minutesLeft(10, 0f))
        assertEquals(5, minutesLeft(10, 0.5f))
        assertEquals(0, minutesLeft(10, 1f))
        assertEquals(0, minutesLeft(10, 0.99f))
    }

    @Test
    fun `extractFirstUrl pulls a url out of shared text`() {
        assertEquals(
            "https://example.com/post",
            extractFirstUrl("Check this out: https://example.com/post via Reader"),
        )
        assertEquals(
            "http://x.test/a",
            extractFirstUrl("http://x.test/a"),
        )
        assertNull(extractFirstUrl("no link here"))
        assertNull(extractFirstUrl(null))
    }

    @Test
    fun `extractFirstUrl trims trailing punctuation`() {
        assertEquals(
            "https://example.com/page",
            extractFirstUrl("See (https://example.com/page)."),
        )
    }

    @Test
    fun `formatShortDate gives friendly relative labels`() {
        val now = 1_700_000_000_000L
        assertEquals("today", formatShortDate(now - 1000, now))
        assertEquals("yesterday", formatShortDate(now - 25L * 60 * 60 * 1000, now))
        assertEquals("3d ago", formatShortDate(now - 3L * 24 * 60 * 60 * 1000, now))
        assertNull(formatShortDate(null, now))
    }
}
