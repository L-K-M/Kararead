package ch.lkmc.kararead.reader

import ch.lkmc.kararead.data.model.Bookmark
import ch.lkmc.kararead.data.model.ContentType
import ch.lkmc.kararead.data.model.ReaderArticle
import ch.lkmc.kararead.data.model.ReaderFont
import ch.lkmc.kararead.data.model.ReaderPreferences
import ch.lkmc.kararead.data.model.ReaderTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderHtmlBuilderTest {

    private fun article(html: String?) = ReaderArticle(
        bookmark = Bookmark(
            id = "1", title = "My Title", url = "https://example.com",
            siteName = "Example", author = "Jane", excerpt = null,
            faviconUrl = null, imageUrl = null, createdAt = 0L, datePublished = null,
            archived = false, favourited = false, tags = emptyList(),
            note = null, summary = null, readingTimeMinutes = 5, contentType = ContentType.LINK,
        ),
        htmlContent = html,
        textContent = null,
    )

    @Test
    fun `build embeds title byline and body`() {
        val out = ReaderHtmlBuilder.build(article("<p>Body text</p>"), ReaderPreferences())
        assertTrue(out.contains("My Title"))
        assertTrue(out.contains("Jane"))
        assertTrue(out.contains("5 min read"))
        assertTrue(out.contains("Body text"))
    }

    @Test
    fun `build strips script tags from content`() {
        val out = ReaderHtmlBuilder.build(
            article("<p>safe</p><script>alert('xss')</script>"),
            ReaderPreferences(),
        )
        assertFalse(out.contains("alert('xss')"))
        assertTrue(out.contains("safe"))
    }

    @Test
    fun `build escapes html in the title`() {
        val art = article("<p>x</p>").copy(
            bookmark = article("<p>x</p>").bookmark.copy(title = "<b>boom</b>"),
        )
        val out = ReaderHtmlBuilder.build(art, ReaderPreferences())
        assertTrue(out.contains("&lt;b&gt;boom&lt;/b&gt;"))
    }

    @Test
    fun `server-relative image survives sanitization when a base uri is given`() {
        val html = "<p>x</p><img src=\"/api/assets/abc\" alt=\"hero\">"
        // Without a base URI, the protocol-restricted relative src is dropped.
        val withoutBase = ReaderHtmlBuilder.build(article(html), ReaderPreferences())
        assertFalse(withoutBase.contains("/api/assets/abc"))
        // With the server origin as base, it is absolutized and kept.
        val withBase = ReaderHtmlBuilder.build(
            article(html), ReaderPreferences(), baseUri = "https://srv.example.com",
        )
        assertTrue(withBase.contains("https://srv.example.com/api/assets/abc"))
    }

    @Test
    fun `variableCss reflects preferences`() {
        val prefs = ReaderPreferences(
            theme = ReaderTheme.SEPIA, font = ReaderFont.MONO,
            fontScale = 1.5f, lineHeight = 2.0f, horizontalMargin = 30, justify = true,
        )
        val palette = ReaderHtmlBuilder.paletteFor(prefs.theme)
        val css = ReaderHtmlBuilder.variableCss(palette, prefs)
        assertTrue(css.contains("--kr-align: justify"))
        assertTrue(css.contains("--kr-margin: 30px"))
        assertTrue(css.contains("--kr-line-height: 2.0"))
        assertTrue(css.contains(palette.background))
    }

    @Test
    fun `reader script handles in-page anchor links`() {
        // The built document should intercept same-document fragment links and
        // scroll within the article rather than letting them navigate away.
        val out = ReaderHtmlBuilder.build(article("<p>x</p>"), ReaderPreferences())
        assertTrue(out.contains("scrollIntoView"))
        assertTrue(out.contains("In-page anchor"))
    }

    @Test
    fun `empty content shows a friendly placeholder`() {
        val out = ReaderHtmlBuilder.build(article(null), ReaderPreferences())
        assertTrue(out.contains("no readable content"))
    }

    @Test
    fun `each theme has a distinct background`() {
        val backgrounds = ReaderTheme.entries.map { ReaderHtmlBuilder.paletteFor(it).background }
        assertTrue(backgrounds.toSet().size == ReaderTheme.entries.size)
    }
}
