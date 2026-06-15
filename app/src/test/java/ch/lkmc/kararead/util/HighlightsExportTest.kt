package ch.lkmc.kararead.util

import ch.lkmc.kararead.data.model.Highlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightsExportTest {

    private fun hl(start: Int, text: String?, note: String? = null) =
        Highlight(
            id = "h$start",
            bookmarkId = "bm",
            startOffset = start,
            endOffset = start + (text?.length ?: 0),
            color = "yellow",
            text = text,
            note = note,
        )

    @Test
    fun `renders title, url and quotes in reading order`() {
        val md = highlightsToMarkdown(
            title = "My Article",
            url = "https://example.com/a",
            highlights = listOf(hl(50, "second"), hl(10, "first")),
        )
        assertEquals(
            """
            # My Article
            https://example.com/a

            > first

            > second
            """.trimIndent() + "\n",
            md,
        )
    }

    @Test
    fun `includes a note under its highlight and multi-line quotes are prefixed`() {
        val md = highlightsToMarkdown(
            title = "T",
            url = null,
            highlights = listOf(hl(0, "line one\nline two", note = "my note")),
        )
        assertTrue(md.contains("> line one\n> line two"))
        assertTrue(md.contains("\n\nmy note"))
    }

    @Test
    fun `skips blank highlights`() {
        val md = highlightsToMarkdown("T", null, listOf(hl(0, "   "), hl(5, null)))
        assertEquals("# T\n", md)
    }
}
