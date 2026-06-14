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
}
