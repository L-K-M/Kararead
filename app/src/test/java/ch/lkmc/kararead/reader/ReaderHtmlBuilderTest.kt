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
        // AUTO and SUNSET are aliases resolved to a concrete theme before
        // rendering, so paletteFor maps them onto an existing palette.
        val aliases = setOf(ReaderTheme.AUTO, ReaderTheme.SUNSET)
        val concrete = ReaderTheme.entries.filter { it !in aliases }
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
    fun `sunset follows the clock from light through sepia to dark`() {
        val sunset = ReaderTheme.SUNSET
        // Daytime reads as plain light paper.
        assertEquals(ReaderTheme.LIGHT, sunset.resolve(systemDark = false, hourOfDay = 9))
        assertEquals(ReaderTheme.LIGHT, sunset.resolve(systemDark = false, hourOfDay = 16))
        // Golden hour warms to sepia.
        assertEquals(ReaderTheme.SEPIA, sunset.resolve(systemDark = false, hourOfDay = 18))
        // After nightfall it goes dark, regardless of the system setting.
        assertEquals(ReaderTheme.DARK, sunset.resolve(systemDark = false, hourOfDay = 22))
        assertEquals(ReaderTheme.DARK, sunset.resolve(systemDark = false, hourOfDay = 3))
    }

    @Test
    fun `sunset defaults to daytime for hour-agnostic callers`() {
        // The noon default keeps callers that never pass an hour on the light
        // branch (paletteFor also treats SUNSET as a light fallback).
        assertEquals(ReaderTheme.LIGHT, ReaderTheme.SUNSET.resolve(systemDark = true))
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

    @Test
    fun `image inversion is armed only on the dark themes`() {
        fun scanArmed(theme: ReaderTheme, invert: Boolean = true) = ReaderHtmlBuilder.build(
            article("<p>x</p>"),
            ReaderPreferences(theme = theme, invertBrightImages = invert),
        ).contains("krApplyImageInvert(true)")

        assertTrue(scanArmed(ReaderTheme.DARK))
        assertTrue(scanArmed(ReaderTheme.BLACK))
        assertFalse(scanArmed(ReaderTheme.LIGHT))
        assertFalse(scanArmed(ReaderTheme.SEPIA))
        // …and never when the reader has been told not to.
        assertFalse(scanArmed(ReaderTheme.DARK, invert = false))
        assertFalse(scanArmed(ReaderTheme.BLACK, invert = false))
    }

    @Test
    fun `invert strength lands an inverted white on the page background`() {
        // invert(a) maps white to 1-a, so the amount is chosen per theme to put
        // a screenshot's paper exactly on the page instead of punching a black
        // hole in it: 0.9 -> #1a1a1a for Dark, 1 -> #000000 for Black.
        assertEquals("0.9", ReaderHtmlBuilder.paletteFor(ReaderTheme.DARK).imageInvert)
        assertEquals("1", ReaderHtmlBuilder.paletteFor(ReaderTheme.BLACK).imageInvert)
        assertEquals("0", ReaderHtmlBuilder.paletteFor(ReaderTheme.LIGHT).imageInvert)
        assertEquals("0", ReaderHtmlBuilder.paletteFor(ReaderTheme.SEPIA).imageInvert)

        // The CSS carries whatever the palette says, checked through the palette
        // rather than as a second copy of the number: retuning Dark should fail
        // the one assertion above, not a regex here and another further down.
        val dark = ReaderHtmlBuilder.paletteFor(ReaderTheme.DARK)
        val css = ReaderHtmlBuilder.variableCss(dark, ReaderPreferences(theme = ReaderTheme.DARK))
        val strength = Regex.escape(dark.imageInvert)
        assertTrue(Regex("--kr-img-invert:\\s*$strength(?![0-9])").containsMatchIn(css))
    }

    @Test
    fun `the inversion filter only applies under the root class`() {
        // Light themes must not composite a filter at all, so the rule is gated
        // on a class the script only sets when inversion is on.
        val out = ReaderHtmlBuilder.build(
            article("<p>x</p>"), ReaderPreferences(theme = ReaderTheme.DARK),
        )
        assertTrue(out.contains("html.kr-invert-images .kr-article img.kr-bright"))
        assertTrue(out.contains("filter: invert(var(--kr-img-invert, 1)) hue-rotate(180deg)"))
    }

    @Test
    fun `a preference change re-applies the image inversion without a reload`() {
        val dark = ReaderHtmlBuilder.paletteFor(ReaderTheme.DARK)
        val on = ReaderHtmlBuilder.applyPrefsScript(
            dark, ReaderPreferences(theme = ReaderTheme.DARK, invertBrightImages = true),
        )
        assertTrue(on.contains("krApplyImageInvert(true)"))
        assertTrue(on.contains("'--kr-img-invert', '${dark.imageInvert}'"))

        val off = ReaderHtmlBuilder.applyPrefsScript(
            dark, ReaderPreferences(theme = ReaderTheme.DARK, invertBrightImages = false),
        )
        assertTrue(off.contains("krApplyImageInvert(false)"))

        // And switching back to a light theme disarms it even with the
        // preference still on. Worth pinning rather than assuming: the class is
        // the only thing holding the filter off, and were it left set, the
        // companion hue-rotate(180deg) would shift the colours of every marked
        // image on a light page — invert(0) is the no-op, hue-rotate is not.
        val backToLight = ReaderHtmlBuilder.applyPrefsScript(
            ReaderHtmlBuilder.paletteFor(ReaderTheme.LIGHT),
            ReaderPreferences(theme = ReaderTheme.LIGHT, invertBrightImages = true),
        )
        assertTrue(backToLight.contains("krApplyImageInvert(false)"))
    }

    @Test
    fun `the tone sampler and the Kotlin judge share one set of thresholds`() {
        // The page measures pixels, ImageTone judges them — so the levels the
        // sampler counts against have to be the ones the judge was tuned for.
        val out = ReaderHtmlBuilder.build(
            article("<p>x</p>"), ReaderPreferences(theme = ReaderTheme.DARK),
        )
        // Anchored per constant rather than matched as a line: reformatting the
        // embedded JS mustn't break a test that is about the shared numbers, and
        // a bare substring would take `DARK = 96` out of `PEAK_DARK = 964` or
        // out of `s.DARK = 96`.
        fun pinned(name: String, value: Any) = assertTrue(
            "expected JS constant $name = $value",
            Regex("(?<![A-Za-z0-9_.])$name\\s*=\\s*${Regex.escape(value.toString())}(?![0-9.])")
                .containsMatchIn(out),
        )
        pinned("LIGHT", ImageTone.LIGHT_LEVEL)
        pinned("DARK", ImageTone.DARK_LEVEL)
        pinned("VIVID", ImageTone.VIVID_SATURATION)
        pinned("BUCKET_BITS", ImageTone.COLOR_BUCKET_BITS)
        pinned("CHROMA", ImageTone.CHROMA_FLOOR)
    }

    @Test
    fun `the sampler hands the bridge its arguments in the declared order`() {
        // The page calls ReaderBridge.shouldInvertImage positionally, and a JS
        // string has no compile-time link to a Kotlin signature: swap two of
        // these and every image is misjudged with nothing failing. This pins the
        // JS side only — pinning the Kotlin parameter order too would want
        // kotlin-reflect, which isn't a dependency here, so that half rests on
        // the cross-reference in the bridge's KDoc.
        val out = ReaderHtmlBuilder.build(
            article("<p>x</p>"), ReaderPreferences(theme = ReaderTheme.DARK),
        )
        // Anchored on the bound name so the feature-detect mention can't be
        // picked up instead, and stripped of whitespace entirely so only the
        // order is under test.
        val call = out.substringAfter("AndroidReader.shouldInvertImage(", missingDelimiterValue = "")
            .substringBefore(")")
            .replace(Regex("\\s+"), "")
        assertEquals(
            "s.light,s.dark,s.sat,s.vivid,s.border,s.peak,s.peakLevel,s.colors,s.samples",
            call,
        )
    }
}
