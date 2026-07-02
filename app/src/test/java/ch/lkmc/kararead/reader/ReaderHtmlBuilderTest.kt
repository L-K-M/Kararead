package ch.lkmc.kararead.reader

import ch.lkmc.kararead.data.model.Bookmark
import ch.lkmc.kararead.data.model.ContentType
import ch.lkmc.kararead.data.model.ReaderArticle
import ch.lkmc.kararead.data.model.ReaderFont
import ch.lkmc.kararead.data.model.ReaderPreferences
import ch.lkmc.kararead.data.model.ReaderTheme
import ch.lkmc.kararead.data.model.resolve
import org.junit.Assert.assertEquals
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
    fun `safe-top inset is applied as content head-room`() {
        // The host passes the status-bar inset + bar height so the title clears
        // the overlaid, edge-to-edge top app bar.
        val out = ReaderHtmlBuilder.build(
            article("<p>x</p>"), ReaderPreferences(), safeTopPx = 120,
        )
        assertTrue(out.contains("--kr-safe-top: 120px"))
    }

    @Test
    fun `safe-top falls back to the bare top margin when unknown`() {
        val css = ReaderHtmlBuilder.variableCss(
            ReaderHtmlBuilder.paletteFor(ReaderTheme.LIGHT), ReaderPreferences(),
        )
        assertTrue(css.contains("--kr-safe-top: 28px"))
    }

    @Test
    fun `paging keeps a line of overlap and clears the next-article badge`() {
        val out = ReaderHtmlBuilder.build(article("<p>x</p>"), ReaderPreferences())
        // Page distance = viewport - one line - whatever the badge covers.
        assertTrue(out.contains("krLineHeightPx"))
        assertTrue(out.contains("krBottomCoverPx"))
        assertTrue(out.contains("--kr-page-bottom-cover"))
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
    fun `selection capture reports a selection at most once`() {
        // Some OEM selection toolbars re-invoke the action in a loop; the capture
        // must report a given selection only once (guarded by krReported).
        val out = ReaderHtmlBuilder.build(article("<p>x</p>"), ReaderPreferences())
        assertTrue(out.contains("krCaptureSelection"))
        assertTrue(out.contains("krReported"))
    }

    @Test
    fun `selection capture keeps a fallback for the cleared action-mode selection`() {
        // The native "Highlight" action clears the live selection before our async
        // capture runs, so a cloned fallback range must be kept.
        val out = ReaderHtmlBuilder.build(article("<p>x</p>"), ReaderPreferences())
        assertTrue(out.contains("krCaptureSelection"))
        assertTrue(out.contains("krLastRange"))
        assertTrue(out.contains("cloneRange"))
    }

    @Test
    fun `empty content shows a friendly placeholder`() {
        val out = ReaderHtmlBuilder.build(article(null), ReaderPreferences())
        assertTrue(out.contains("no readable content"))
    }

    @Test
    fun `reader script can jump to a saved highlight`() {
        val out = ReaderHtmlBuilder.build(article("<p>x</p>"), ReaderPreferences())
        assertTrue(out.contains("krScrollToHighlight"))
        assertTrue(out.contains("mark.kr-hl[data-id="))
    }

    @Test
    fun `table of contents lists headings with document-order indices`() {
        val body = "<h2>One</h2><p>a</p><h3>One-a</h3><p>b</p><h2>Two</h2>"
        val toc = ReaderHtmlBuilder.tableOfContents(body)
        assertEquals(3, toc.size)
        assertEquals("One", toc[0].text)
        assertEquals(2, toc[0].level)
        assertEquals(0, toc[0].index)
        assertEquals(3, toc[1].level) // h3
        assertEquals(1, toc[1].index)
        assertEquals("Two", toc[2].text)
        assertEquals(2, toc[2].index)
    }

    @Test
    fun `table of contents skips empty headings but keeps selector indices`() {
        // The empty h2 still occupies index 1 in the DOM's querySelectorAll, so
        // the following heading must report index 2 to line up with the WebView.
        val body = "<h2>First</h2><h2></h2><h2>Third</h2>"
        val toc = ReaderHtmlBuilder.tableOfContents(body)
        assertEquals(2, toc.size)
        assertEquals(0, toc[0].index)
        assertEquals(2, toc[1].index)
    }

    @Test
    fun `empty body has no table of contents`() {
        assertTrue(ReaderHtmlBuilder.tableOfContents(null).isEmpty())
        assertTrue(ReaderHtmlBuilder.tableOfContents("<p>no headings</p>").isEmpty())
    }

    @Test
    fun `element ids survive sanitization so footnote links can resolve`() {
        val html = "<p>See<a href=\"#fn1\">[1]</a></p><p id=\"fn1\">the footnote</p>"
        // In production the server origin is the base URI; fragment hrefs get
        // absolutized against it (the in-page JS matches them via n.hash).
        val out = ReaderHtmlBuilder.build(
            article(html), ReaderPreferences(), baseUri = "https://srv.example.com",
        )
        assertTrue(out.contains("id=\"fn1\""))
        assertTrue(out.contains("#fn1\""))
    }

    @Test
    fun `document does not claim to be english`() {
        // Karakeep has no language field; a hardcoded lang="en" made TalkBack
        // mispronounce every non-English article. Unknown beats wrong.
        val out = ReaderHtmlBuilder.build(article("<p>x</p>"), ReaderPreferences())
        assertFalse(out.contains("lang=\"en\""))
    }

    @Test
    fun `page turns cancel a superseded animation`() {
        val out = ReaderHtmlBuilder.build(article("<p>x</p>"), ReaderPreferences())
        assertTrue(out.contains("krPageAnim"))
    }

    @Test
    fun `each concrete theme has a distinct background`() {
        // AUTO is an alias resolved to a concrete theme before rendering.
        val concrete = ReaderTheme.entries.filter { it != ReaderTheme.AUTO }
        val backgrounds = concrete.map { ReaderHtmlBuilder.paletteFor(it).background }
        assertTrue(backgrounds.toSet().size == concrete.size)
    }

    @Test
    fun `auto resolves with the system setting`() {
        org.junit.Assert.assertEquals(
            ReaderTheme.DARK,
            with(ch.lkmc.kararead.data.model.ReaderTheme.AUTO) { resolve(systemDark = true) },
        )
        org.junit.Assert.assertEquals(
            ReaderTheme.LIGHT,
            with(ch.lkmc.kararead.data.model.ReaderTheme.AUTO) { resolve(systemDark = false) },
        )
        org.junit.Assert.assertEquals(
            ReaderTheme.SEPIA,
            with(ch.lkmc.kararead.data.model.ReaderTheme.SEPIA) { resolve(systemDark = true) },
        )
    }

    @Test
    fun `font size css uses a decimal point regardless of the default locale`() {
        val previous = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            val css = ReaderHtmlBuilder.variableCss(
                ReaderHtmlBuilder.paletteFor(ReaderTheme.LIGHT),
                ReaderPreferences(fontScale = 1.2f),
            )
            // 19 * 1.2 = 22.8 — a German default locale used to emit "22,8px",
            // which is invalid CSS and silently broke the text-size preference.
            assertTrue(css.contains("--kr-font-size: 22.8px"))
            assertFalse(css.contains("22,8"))

            val js = ReaderHtmlBuilder.applyPrefsScript(
                ReaderHtmlBuilder.paletteFor(ReaderTheme.LIGHT),
                ReaderPreferences(fontScale = 1.2f),
            )
            assertTrue(js.contains("'22.8px'"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `font size honors the system font scale`() {
        val css = ReaderHtmlBuilder.variableCss(
            ReaderHtmlBuilder.paletteFor(ReaderTheme.LIGHT),
            ReaderPreferences(fontScale = 1.0f),
            systemFontScale = 1.5f,
        )
        // 19 * 1.0 * 1.5 = 28.5
        assertTrue(css.contains("--kr-font-size: 28.5px"))
    }

    @Test
    fun `highlight color is theme-aware and avoids color-mix`() {
        // Dark themes need a dimmer highlight than light ones, and the value
        // must be plain rgba() so pre-Chromium-111 WebViews still render it.
        val light = ReaderHtmlBuilder.paletteFor(ReaderTheme.LIGHT).highlight
        val dark = ReaderHtmlBuilder.paletteFor(ReaderTheme.DARK).highlight
        assertTrue(light.startsWith("rgba("))
        assertTrue(dark.startsWith("rgba("))
        assertTrue(light != dark)

        val out = ReaderHtmlBuilder.build(article("<p>x</p>"), ReaderPreferences())
        assertTrue(out.contains("--kr-hl:"))
        assertTrue(out.contains("background: var(--kr-hl"))
    }
}
