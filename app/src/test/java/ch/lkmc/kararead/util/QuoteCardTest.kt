package ch.lkmc.kararead.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteCardTest {

    @Test
    fun `quote body trims, collapses whitespace and adds quotation marks`() {
        val out = QuoteCard.quoteBody("  hello\n  world  ")
        assertEquals("“hello world”", out)
    }

    @Test
    fun `quote body keeps existing opening quotation mark`() {
        assertEquals("“already quoted”", QuoteCard.quoteBody("“already quoted”"))
        // A straight opening quote is also treated as already quoted.
        assertEquals("\"straight\"", QuoteCard.quoteBody("\"straight\""))
    }

    @Test
    fun `quote body is empty for blank input`() {
        assertEquals("", QuoteCard.quoteBody(null))
        assertEquals("", QuoteCard.quoteBody("   \n  "))
    }

    @Test
    fun `attribution combines title and site host`() {
        assertEquals(
            "The Title · example.com",
            QuoteCard.attribution("The Title", "https://www.example.com/article"),
        )
    }

    @Test
    fun `attribution omits a site that just repeats the title`() {
        assertEquals(
            "example.com",
            QuoteCard.attribution("example.com", "https://example.com/x"),
        )
    }

    @Test
    fun `attribution falls back gracefully without a url or title`() {
        assertEquals("Untitled", QuoteCard.attribution(null, null))
        assertEquals("A Title", QuoteCard.attribution("A Title", ""))
    }

    @Test
    fun `file name is filesystem safe and stable`() {
        assertEquals("quote-abc123.png", QuoteCard.fileName("abc123"))
        assertEquals("quote-a_b_c.png", QuoteCard.fileName("a/b:c"))
        assertTrue(QuoteCard.fileName("").startsWith("quote-"))
    }
}
