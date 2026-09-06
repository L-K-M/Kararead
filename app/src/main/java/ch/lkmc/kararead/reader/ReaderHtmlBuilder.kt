package ch.lkmc.kararead.reader

import ch.lkmc.kararead.data.model.ReaderArticle
import ch.lkmc.kararead.data.model.ReaderPreferences
import ch.lkmc.kararead.data.model.ReaderTheme
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.util.Locale

/** One article heading, for the table of contents. */
data class ReaderHeading(
    /** Ordinal among all h1–h4 in document order (matches the WebView selector). */
    val index: Int,
    /** 1–4; used to indent the entry. */
    val level: Int,
    val text: String,
)

/** Palette for a reader theme (CSS color strings). */
data class ReaderPalette(
    val background: String,
    val surface: String,
    val text: String,
    val secondary: String,
    val link: String,
    val border: String,
    val codeBg: String,
    /** Highlight-mark background (a full CSS color, usually translucent). */
    val highlight: String,
    /** "dark" or "light" — drives the WebView's color-scheme & scrollbar. */
    val scheme: String,
    /**
     * How hard to flip a bright image on this theme (a CSS `invert()` amount).
     * Chosen so pure white lands on [background]: `invert(a)` maps white to
     * `1 - a`, so 0.9 turns #ffffff into #1a1a1a and an inverted screenshot
     * blends into the page instead of punching a black hole in it. "0" on the
     * light themes, which never invert anything.
     */
    val imageInvert: String,
)

object ReaderHtmlBuilder {

    fun paletteFor(theme: ReaderTheme): ReaderPalette = when (theme) {
        // AUTO and SUNSET are resolved to a concrete theme before rendering
        // (ReaderTheme.resolve); falling back to LIGHT keeps an unresolved
        // value harmless.
        ReaderTheme.AUTO,
        ReaderTheme.SUNSET,
        ReaderTheme.LIGHT -> ReaderPalette(
            background = "#fdfdfb", surface = "#f3f3ee", text = "#1b1b1b",
            secondary = "#6b6b6b", link = "#1565c0", border = "#e3e3dd",
            codeBg = "#f0f0ea", highlight = "rgba(255, 213, 79, 0.55)",
            scheme = "light", imageInvert = "0",
        )
        ReaderTheme.SEPIA -> ReaderPalette(
            background = "#f4ecd8", surface = "#ece1c7", text = "#4a3f35",
            // #6f6049 keeps the warm tone but clears WCAG AA (≥4.5:1) on the
            // sepia background — the secondary color styles whole blockquotes.
            secondary = "#6f6049", link = "#9a5b2b", border = "#ddd0b3",
            codeBg = "#e9ddc2", highlight = "rgba(255, 213, 79, 0.55)",
            scheme = "light", imageInvert = "0",
        )
        ReaderTheme.DARK -> ReaderPalette(
            background = "#1a1a1a", surface = "#262626", text = "#d8d4cc",
            secondary = "#9a958c", link = "#82b1e6", border = "#333333",
            // Dimmer amber for dark themes: a 55% wash under light text drops
            // contrast to ~2.6:1; ~25% keeps the marked text clearly readable.
            codeBg = "#222222", highlight = "rgba(255, 213, 79, 0.26)",
            scheme = "dark", imageInvert = "0.9",
        )
        ReaderTheme.BLACK -> ReaderPalette(
            background = "#000000", surface = "#101010", text = "#cbc7bf",
            secondary = "#8a857c", link = "#82b1e6", border = "#222222",
            codeBg = "#0c0c0c", highlight = "rgba(255, 213, 79, 0.22)",
            scheme = "dark", imageInvert = "1",
        )
    }

    /**
     * Sanitize an article body off the UI thread ahead of [build]. Jsoup over
     * a long article costs tens of milliseconds; running it inside composition
     * (where build used to do it) hitched the reader's open transition.
     * Returns null when there is no body (build shows its placeholder).
     */
    fun sanitizeBody(html: String?, baseUri: String?): String? =
        html?.let { sanitize(it, baseUri.orEmpty()) }

    /**
     * The article's headings, for a table of contents. [ReaderHeading.index] is
     * the heading's ordinal among all h1–h4 in document order, matching the same
     * `querySelectorAll` order the WebView's `krScrollToHeading` uses — so an
     * index here scrolls to the right heading there.
     */
    fun tableOfContents(sanitizedBody: String?): List<ReaderHeading> {
        if (sanitizedBody.isNullOrBlank()) return emptyList()
        return runCatching {
            Jsoup.parseBodyFragment(sanitizedBody)
                .select("h1, h2, h3, h4")
                .mapIndexedNotNull { i, el ->
                    val text = el.text().trim()
                    if (text.isEmpty()) {
                        null
                    } else {
                        ReaderHeading(
                            index = i,
                            level = el.tagName().removePrefix("h").toIntOrNull() ?: 2,
                            text = text,
                        )
                    }
                }
        }.getOrDefault(emptyList())
    }

    /** Sanitize crawled HTML: keep rich structure, drop scripts/styles/iframes. */
    private fun sanitize(html: String, baseUri: String): String {
        val safelist = Safelist.relaxed()
            .addTags("figure", "figcaption", "h1", "h2", "section", "article", "mark", "hr")
            .addAttributes("img", "src", "alt", "title", "width", "height")
            .addAttributes("a", "href", "title", "name")
            // Keep element ids: footnote/anchor links look their targets up by
            // id, and stripping them made every in-page link kick the reader
            // out to a browser at the server root.
            .addAttributes(":all", "id")
            .addProtocols("img", "src", "http", "https", "data")
        // Pass the server origin as the base URI so Karakeep's *relative* asset
        // paths (e.g. /api/assets/<id>) resolve to absolute https URLs and
        // survive sanitization — otherwise protocol-restricted img[src] with no
        // base resolves to nothing and the image is silently dropped before the
        // WebView's auth-injecting interceptor ever sees it.
        return Jsoup.clean(html, baseUri, safelist)
    }

    fun build(
        article: ReaderArticle,
        prefs: ReaderPreferences,
        baseUri: String? = null,
        safeTopPx: Int = 0,
        systemFontScale: Float = 1f,
        /** Body already run through [sanitizeBody] (off the UI thread). */
        presanitizedBody: String? = null,
    ): String {
        val palette = paletteFor(prefs.theme)
        val bm = article.bookmark
        val body = presanitizedBody
            ?: article.htmlContent?.let { sanitize(it, baseUri.orEmpty()) }
            ?: "<p class=\"kr-empty\">This article has no readable content yet. " +
            "It may still be processing on the server — try opening the original.</p>"

        val bylineParts = buildList {
            bm.author?.takeIf { it.isNotBlank() }?.let { add(escape(it)) }
            bm.siteName?.takeIf { it.isNotBlank() }?.let { add(escape(it)) }
            bm.readingTimeMinutes?.let { add("$it min read") }
        }
        val byline = bylineParts.joinToString(" &middot; ")

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">
<style>
${fontFaceCss()}
${baseCss()}
${variableCss(palette, prefs, safeTopPx, systemFontScale)}
</style>
</head>
<body>
<div id="kr-content">
  <header class="kr-header">
    <h1 class="kr-title">${escape(bm.displayTitle)}</h1>
    ${if (byline.isNotBlank()) "<p class=\"kr-byline\">$byline</p>" else ""}
  </header>
  <article class="kr-article">
    $body
  </article>
  <footer class="kr-footer">— end —</footer>
</div>
${progressScript()}
${imageToneScript(invertsImages(palette, prefs))}
</body>
</html>
        """.trimIndent()
    }

    /**
     * The reader's base font size in CSS px. Honors both the in-app text-size
     * preference and the system font-scale accessibility setting (the WebView's
     * own scaling is disabled via textZoom=100, so it must be applied here).
     * Formatted with [Locale.ROOT]: the default locale would emit a decimal
     * comma ("22,8px") on many locales — invalid CSS that silently breaks the
     * whole typography scale.
     */
    private fun fontSizeCss(prefs: ReaderPreferences, systemFontScale: Float): String {
        val baseFontPx = 19 * prefs.fontScale * systemFontScale.coerceIn(0.5f, 3f)
        return String.format(Locale.ROOT, "%.1f", baseFontPx)
    }

    /** Runtime-updatable CSS variables, also emitted as JS for live preference changes. */
    fun variableCss(
        palette: ReaderPalette,
        prefs: ReaderPreferences,
        safeTopPx: Int = 0,
        systemFontScale: Float = 1f,
    ): String {
        // Head-room so the title clears the (edge-to-edge, overlaid) top app bar.
        // The host passes the status-bar inset + bar height; fall back to the bare
        // top margin when unknown (e.g. tests).
        val safeTop = if (safeTopPx > 0) safeTopPx else 28
        return """
:root {
  --kr-safe-top: ${safeTop}px;
  --kr-page-bottom-cover: 0px;
  --kr-bg: ${palette.background};
  --kr-surface: ${palette.surface};
  --kr-text: ${palette.text};
  --kr-secondary: ${palette.secondary};
  --kr-link: ${palette.link};
  --kr-border: ${palette.border};
  --kr-code-bg: ${palette.codeBg};
  --kr-hl: ${palette.highlight};
  --kr-font: ${prefs.font.cssStack};
  --kr-font-size: ${fontSizeCss(prefs, systemFontScale)}px;
  --kr-line-height: ${prefs.lineHeight};
  --kr-margin: ${prefs.horizontalMargin}px;
  --kr-align: ${if (prefs.justify) "justify" else "start"};
  --kr-img-invert: ${palette.imageInvert};
  color-scheme: ${palette.scheme};
}
        """.trimIndent()
    }

    /** JS snippet to apply preference changes without reloading the document. */
    fun applyPrefsScript(
        palette: ReaderPalette,
        prefs: ReaderPreferences,
        systemFontScale: Float = 1f,
    ): String {
        return """
(function(){
  var r = document.documentElement.style;
  r.setProperty('--kr-bg', '${palette.background}');
  r.setProperty('--kr-surface', '${palette.surface}');
  r.setProperty('--kr-text', '${palette.text}');
  r.setProperty('--kr-secondary', '${palette.secondary}');
  r.setProperty('--kr-link', '${palette.link}');
  r.setProperty('--kr-border', '${palette.border}');
  r.setProperty('--kr-code-bg', '${palette.codeBg}');
  r.setProperty('--kr-hl', '${palette.highlight}');
  r.setProperty('--kr-font', "${prefs.font.cssStack}");
  r.setProperty('--kr-font-size', '${fontSizeCss(prefs, systemFontScale)}px');
  r.setProperty('--kr-line-height', '${prefs.lineHeight}');
  r.setProperty('--kr-margin', '${prefs.horizontalMargin}px');
  r.setProperty('--kr-align', '${if (prefs.justify) "justify" else "start"}');
  r.setProperty('--kr-img-invert', '${palette.imageInvert}');
  document.documentElement.style.colorScheme = '${palette.scheme}';
  // Theme and toggle both live here, so switching to Dark mid-article starts
  // the (cached, idempotent) image scan without rebuilding the document.
  if (window.krApplyImageInvert) window.krApplyImageInvert(${invertsImages(palette, prefs)});
})();
        """.trimIndent()
    }

    /**
     * True when this theme + preference combination should flip bright images.
     * Only the dark themes have anything to gain, so the light ones never pay
     * for the sampling pass.
     */
    private fun invertsImages(palette: ReaderPalette, prefs: ReaderPreferences): Boolean =
        prefs.invertBrightImages && palette.scheme == "dark"

    /**
     * Finds the article's bright documents — screenshots of text, diagrams,
     * line art on white — and marks them `.kr-bright` so the stylesheet can
     * flip them for a dark page.
     *
     * The sampling happens here because pixels only exist in the renderer; the
     * *judgement* is [ImageTone.shouldInvert] on the Kotlin side, reached
     * through the `AndroidReader` bridge, so it stays unit-testable and there is
     * one set of thresholds rather than two. An image whose pixels can't be read
     * (a cross-origin canvas the CORS probe can't rescue, a decode failure) is
     * simply left alone — never hidden, never half-drawn.
     */
    private fun imageToneScript(initiallyOn: Boolean): String = """
<script>
(function(){
  var LIGHT = ${ImageTone.LIGHT_LEVEL}, DARK = ${ImageTone.DARK_LEVEL};
  var VIVID = ${ImageTone.VIVID_SATURATION}, CHROMA = ${ImageTone.CHROMA_FLOOR};
  var BUCKET_BITS = ${ImageTone.COLOR_BUCKET_BITS};
  var CLEAR_ALPHA = ${ImageTone.CLEAR_ALPHA}, OPAQUE_ALPHA = ${ImageTone.OPAQUE_ALPHA};
  var BUCKET_SHIFT = 8 - BUCKET_BITS, BUCKETS = 1 << (3 * BUCKET_BITS);
  // Sampled pixels per image. 16k is plenty to measure a distribution and
  // costs well under a millisecond to walk.
  var BUDGET = 16384;
  var MIN_SIDE = 32, MIN_AREA = 4096;
  // A hard stop so a pathological article can't spend the whole frame budget.
  var MAX_IMAGES = 40;
  var on = false, judged = 0;

  // Fraction of a ring just inside the edge that is light. The inset skips the
  // hairline border and drop shadow most screenshots are saved with, which
  // would otherwise read as a dark frame.
  function borderLight(d, cw, ch){
    var inset = Math.max(1, Math.round(Math.min(cw, ch) * 0.04));
    if (inset * 2 + 1 >= Math.min(cw, ch)) inset = 0;
    var lo = inset, hiX = cw - 1 - inset, hiY = ch - 1 - inset;
    var light = 0, n = 0;
    function at(x, y){
      var lum = lumAt(d, (y * cw + x) * 4);
      if (lum >= LIGHT) light++;
      n++;
    }
    for (var x = lo; x <= hiX; x++){ at(x, lo); at(x, hiY); }
    for (var y = lo + 1; y < hiY; y++){ at(lo, y); at(hiX, y); }
    return n ? light / n : 0;
  }

  function lumAt(d, o){
    // Rec. 601 luma on the gamma-encoded bytes, which is what "looks bright"
    // means here; the shift keeps it integer.
    return (d[o] * 77 + d[o + 1] * 151 + d[o + 2] * 28) >> 8;
  }

  // Icons, spacers, tracking pixels and inline glyphs are not documents.
  function tooSmall(img){
    var w = img.naturalWidth, h = img.naturalHeight;
    return !w || !h || w < MIN_SIDE || h < MIN_SIDE || w * h < MIN_AREA;
  }

  // Returns null when the image is too small to judge; throws if the canvas is
  // tainted, which the caller answers with a CORS probe.
  function measure(img){
    if (tooSmall(img)) return null;
    var w = img.naturalWidth, h = img.naturalHeight;
    var scale = Math.min(1, Math.sqrt(BUDGET / (w * h)));
    var cw = Math.max(1, Math.round(w * scale)), ch = Math.max(1, Math.round(h * scale));
    var canvas = document.createElement('canvas');
    canvas.width = cw; canvas.height = ch;
    // Readback-only: never in the DOM, never painted. Asking for the CPU
    // backing store spares up to MAX_IMAGES GPU round trips per article.
    var ctx = canvas.getContext('2d', { willReadFrequently: true });
    if (!ctx) return null;
    // Nearest-neighbour, not averaging: smoothing would blend black text into
    // grey and hide the very bimodality we are looking for.
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(img, 0, 0, cw, ch);
    var d = ctx.getImageData(0, 0, cw, ch).data;
    var n = cw * ch, light = 0, dark = 0, vivid = 0, satSum = 0;
    // Alpha is read before it is composited away: it is what tells light
    // artwork on transparency — drawn for a dark page, and made invisible by
    // inverting — from the dark line art on transparency the composite rescues.
    var clear = 0, opaque = 0, opaqueLight = 0;
    var hist = [], b;
    for (b = 0; b < 32; b++) hist[b] = 0;
    // How much of the palette the image spends: a page of text uses a few
    // dozen of these, a colour photograph hundreds.
    var seen = new Uint32Array(BUCKETS);
    for (var i = 0; i < n; i++){
      var o = i * 4, a = d[o + 3];
      if (a < 255){
        // Composite over white, in place so the border ring sees it too: a
        // transparent PNG of dark line art is the case that vanishes completely
        // on a dark page, and sampling it over paper is what gets it flipped
        // into view.
        var paper = 255 * (255 - a);
        d[o] = (d[o] * a + paper) / 255;
        d[o + 1] = (d[o + 1] * a + paper) / 255;
        d[o + 2] = (d[o + 2] * a + paper) / 255;
        if (a < CLEAR_ALPHA) clear++;
      }
      var r = d[o], g = d[o + 1], bl = d[o + 2];
      var lum = lumAt(d, o);
      if (a >= OPAQUE_ALPHA){ opaque++; if (lum >= LIGHT) opaqueLight++; }
      if (lum >= LIGHT) light++; else if (lum <= DARK) dark++;
      var mx = r > g ? (r > bl ? r : bl) : (g > bl ? g : bl);
      var mn = r < g ? (r < bl ? r : bl) : (g < bl ? g : bl);
      // Achromatic below the floor: (mx - mn) / mx explodes towards black, and
      // reading noise in the ink as colour is how a dense screenshot gets
      // mistaken for a colour graphic.
      var sat = mx < CHROMA ? 0 : (mx - mn) / mx;
      satSum += sat;
      if (sat > VIVID) vivid++;
      hist[lum >> 3]++;
      var bucket = ((r >> BUCKET_SHIFT) << (BUCKET_BITS * 2)) |
        ((g >> BUCKET_SHIFT) << BUCKET_BITS) | (bl >> BUCKET_SHIFT);
      seen[bucket]++;
    }
    // A colour has to hold a sliver of the image before it counts as spent:
    // subpixel-antialiased text leaves a pixel or two in hundreds of buckets
    // along its glyph edges, and counting every one read a Windows screenshot
    // as a colour photograph. 1/2048 of the sample is 8 pixels at full budget.
    var floor = Math.max(2, n >> 11), distinct = 0;
    for (b = 0; b < BUCKETS; b++) if (seen[b] >= floor) distinct++;
    // The dominant level, measured over two adjacent 8-level buckets so JPEG
    // noise around a flat background still reads as one peak.
    var peak = 0, peakBand = 0;
    for (b = 0; b < 31; b++){
      var band = hist[b] + hist[b + 1];
      if (band > peak){ peak = band; peakBand = b; }
    }
    return {
      light: light / n, dark: dark / n,
      sat: satSum / n, vivid: vivid / n,
      border: borderLight(d, cw, ch),
      peak: peak / n, peakLevel: peakBand * 8 + 8,
      colors: distinct / BUCKETS,
      samples: n,
      clear: clear / n, opaqueLight: opaque ? opaqueLight / opaque : 0
    };
  }

  function finish(img, s){
    var bright = false;
    if (s && window.AndroidReader && window.AndroidReader.shouldInvertImage){
      try {
        bright = !!window.AndroidReader.shouldInvertImage(
          s.light, s.dark, s.sat, s.vivid, s.border, s.peak, s.peakLevel,
          s.colors, s.samples, s.clear, s.opaqueLight);
      } catch (e) {
        // Cached as 'plain' below, so a broken bridge would disable the feature
        // for the session without a trace. Leave one.
        bright = false;
        if (window.console && console.warn) console.warn('kr: image tone bridge failed', e);
      }
    }
    img.setAttribute('data-kr-tone', bright ? 'bright' : 'plain');
    if (bright) img.classList.add('kr-bright');
  }

  function judge(img){
    // Settled from the size test alone, whether the image was already loaded
    // when we scanned or arrived later: an article that opens with a row of
    // icons must still have budget left for the screenshot below them.
    if (tooSmall(img)) { img.setAttribute('data-kr-tone', 'plain'); return; }
    if (judged >= MAX_IMAGES) return;
    judged++;
    img.setAttribute('data-kr-tone', 'pending');
    var direct = null;
    try {
      direct = measure(img);
    } catch (e) {
      // A tainted canvas is the one error worth a second fetch over, and
      // getImageData reports it as exactly this. Anything else is a bug here,
      // not a permissions problem — say so rather than spend a download on it.
      if (!e || e.name !== 'SecurityError') {
        if (window.console && console.warn) console.warn('kr: image tone sampling failed', e);
        finish(img, null);
        return;
      }
      direct = undefined; // fall through to the CORS probe below
    }
    if (direct !== undefined) {
      finish(img, direct);
      return;
    }
    // The displayed image stays exactly as it is; this second, CORS-mode load
    // exists only to be readable. Blink won't reuse the renderer's no-cors
    // entry for it, but AssetLoader answers both from the same OkHttp disk
    // cache with byte-identical requests, so it costs a disk read rather than a
    // download. If it fails we simply never invert.
    var probe = new Image();
    probe.crossOrigin = 'anonymous';
    probe.onload = function(){
      var s = null;
      try {
        s = measure(probe);
      } catch (e2) {
        // The direct and bridge paths both leave a trace; so should the one
        // that actually fails in the field.
        if (window.console && console.warn) console.warn('kr: image tone probe sampling failed', e2);
        s = null;
      }
      // Drop the second decode straight away: on an article full of
      // screenshots, holding 40 of them would be a real memory bill. Detach
      // first — replacing the src fires an error at the handler still on it,
      // which would come back through finish() and overwrite the verdict
      // written on the next line.
      probe.onload = probe.onerror = null;
      probe.src = 'data:,';
      finish(img, s);
    };
    probe.onerror = function(){
      // Usually an origin that sends no Access-Control-Allow-Origin. Name it:
      // "why wasn't this screenshot inverted" should be answerable from a log.
      if (window.console && console.warn) {
        console.warn('kr: image tone probe blocked', img.currentSrc || img.src);
      }
      finish(img, null);
    };
    probe.src = img.currentSrc || img.src;
  }

  function scan(){
    if (!on) return;
    // The same subtree the stylesheet's .kr-bright rule is scoped to, so an
    // image can't be sampled and tagged where no filter could reach it.
    var imgs = document.querySelectorAll('.kr-article img');
    for (var i = 0; i < imgs.length; i++){
      var img = imgs[i];
      if (img.getAttribute('data-kr-tone')) continue;
      if (img.complete && img.naturalWidth){ judge(img); continue; }
      if (img.getAttribute('data-kr-bound')) continue;
      img.setAttribute('data-kr-bound', '1');
      img.addEventListener('load', (function(el){
        return function(){ if (on && !el.getAttribute('data-kr-tone')) judge(el); };
      })(img));
    }
  }

  // Called on every preference/theme change, so a mid-article switch to Dark
  // flips the images that are already on screen. Verdicts are cached on the
  // element, so turning it off and on again costs nothing.
  window.krApplyImageInvert = function(enable){
    on = !!enable;
    document.documentElement.classList.toggle('kr-invert-images', on);
    if (on) scan();
  };

  if (document.readyState === 'loading'){
    document.addEventListener('DOMContentLoaded', scan);
  }
  window.addEventListener('load', scan);
  window.krApplyImageInvert(${initiallyOn});
})();
</script>
    """.trimIndent()

    /**
     * @font-face for the bundled reading typefaces. Served by [AssetLoader] via
     * the same-origin `/__krfont/<file>` path so there are no cross-origin/file
     * restrictions. Variable fonts declare a 100–900 weight range (one file);
     * Atkinson Hyperlegible ships static regular + bold.
     */
    private fun fontFaceCss(): String {
        fun variable(family: String, file: String) =
            "@font-face{font-family:'$family';src:url('/__krfont/$file') format('truetype');" +
                "font-weight:100 900;font-style:normal;font-display:swap;}"
        return buildString {
            append(variable("Literata", "Literata.ttf"))
            append(variable("Lora", "Lora.ttf"))
            append(variable("Source Serif 4", "SourceSerif4.ttf"))
            append(variable("Newsreader", "Newsreader.ttf"))
            append(variable("Crimson Pro", "CrimsonPro.ttf"))
            append(variable("Bitter", "Bitter.ttf"))
            append(variable("Inter", "Inter.ttf"))
            append(
                "@font-face{font-family:'Atkinson Hyperlegible';src:url('/__krfont/Atkinson-Regular.ttf') format('truetype');font-weight:400;font-style:normal;font-display:swap;}",
            )
            append(
                "@font-face{font-family:'Atkinson Hyperlegible';src:url('/__krfont/Atkinson-Bold.ttf') format('truetype');font-weight:700;font-style:normal;font-display:swap;}",
            )
        }
    }

    private fun baseCss(): String = """
* { box-sizing: border-box; }
html { -webkit-text-size-adjust: 100%; }
body {
  margin: 0;
  background: var(--kr-bg);
  color: var(--kr-text);
  font-family: var(--kr-font);
  font-size: var(--kr-font-size);
  line-height: var(--kr-line-height);
  text-rendering: optimizeLegibility;
  -webkit-font-smoothing: antialiased;
  overflow-wrap: break-word;
  word-wrap: break-word;
}
#kr-content {
  max-width: 720px;
  margin: 0 auto;
  padding: var(--kr-safe-top, 28px) var(--kr-margin) 96px var(--kr-margin);
}
.kr-header { margin-bottom: 1.6em; }
.kr-title {
  font-size: 1.7em;
  line-height: 1.25;
  font-weight: 700;
  margin: 0 0 .35em 0;
  letter-spacing: -0.01em;
}
.kr-byline {
  color: var(--kr-secondary);
  font-size: .82em;
  margin: 0;
  font-family: -apple-system, 'Roboto', sans-serif;
}
.kr-article {
  text-align: var(--kr-align);
  /* Hyphenation is off: soft-hyphen break opportunities confuse Android
     WebView's text-selection handles (handles land mid-paragraph). */
  -webkit-hyphens: none; hyphens: none;
}
/* Don't hyphenate code/headings. */
.kr-article pre, .kr-article code, .kr-article h1, .kr-article h2,
.kr-article h3, .kr-article h4 { -webkit-hyphens: none; hyphens: none; }
.kr-article p { margin: 0 0 1.1em 0; }
.kr-article a { color: var(--kr-link); text-decoration: none; border-bottom: 1px solid color-mix(in srgb, var(--kr-link) 35%, transparent); }
.kr-article h1, .kr-article h2, .kr-article h3, .kr-article h4 {
  line-height: 1.3; margin: 1.6em 0 .5em 0; font-weight: 700; text-align: start;
}
.kr-article h2 { font-size: 1.35em; }
.kr-article h3 { font-size: 1.15em; }
.kr-article img, .kr-article video { max-width: 100%; height: auto; border-radius: 6px; display: block; margin: 1.2em auto; }
/* Bright documents — screenshots of text, diagrams, line art on white — flipped
   to light-on-dark so they belong to the page instead of glaring out of it.
   ImageTone picks which images get .kr-bright; the root class means the light
   themes never composite a filter at all. The invert amount is per-theme, so an
   inverted white page lands exactly on the page background rather than punching
   a black hole in it. hue-rotate() is a no-op on a plain black-on-white shot; it
   earns its place on coloured ones (syntax highlighting, a chart), where bare
   inversion would turn red into teal. */
html.kr-invert-images .kr-article img.kr-bright {
  filter: invert(var(--kr-img-invert, 1)) hue-rotate(180deg);
}
.kr-article figure { margin: 1.4em 0; }
.kr-article figcaption { color: var(--kr-secondary); font-size: .8em; text-align: center; margin-top: .5em; }
.kr-article blockquote {
  margin: 1.4em 0; padding: .2em 0 .2em 1.1em;
  border-left: 3px solid var(--kr-border); color: var(--kr-secondary); font-style: italic;
}
.kr-article pre {
  background: var(--kr-code-bg); padding: 1em; border-radius: 8px;
  overflow-x: auto; font-size: .85em; line-height: 1.5; text-align: left;
}
.kr-article code {
  background: var(--kr-code-bg); padding: .12em .35em; border-radius: 4px;
  font-family: 'JetBrains Mono', 'Courier New', monospace; font-size: .85em;
}
.kr-article pre code { background: none; padding: 0; }
.kr-article ul, .kr-article ol { padding-left: 1.4em; margin: 0 0 1.1em 0; }
.kr-article li { margin: .35em 0; }
.kr-article hr { border: none; border-top: 1px solid var(--kr-border); margin: 2em 0; }
.kr-article table { border-collapse: collapse; width: 100%; margin: 1.2em 0; font-size: .9em; display: block; overflow-x: auto; }
.kr-article th, .kr-article td { border: 1px solid var(--kr-border); padding: .5em .7em; text-align: left; }
.kr-article mark { background: #ffe57f; color: #1b1b1b; padding: 0 .1em; }
.kr-article mark.kr-hl {
  /* Theme-aware (dimmer on dark themes, where a 55% amber wash under light
     text lands at ~2.6:1) and plain rgba(), so highlights stay visible on
     WebViews without color-mix() support (pre-Chromium 111). */
  background: var(--kr-hl, rgba(255, 213, 79, 0.4));
  color: inherit; padding: 0; border-radius: 2px; cursor: pointer;
  -webkit-box-decoration-break: clone; box-decoration-break: clone;
}
.kr-empty { color: var(--kr-secondary); font-style: italic; }
.kr-footer { color: var(--kr-secondary); text-align: center; margin-top: 3em; font-size: .8em; letter-spacing: .15em; }
::selection { background: color-mix(in srgb, var(--kr-link) 30%, transparent); }
    """.trimIndent()

    /** Reports scroll fraction to the Android host; exposes restore() & applyPrefs(). */
    private fun progressScript(): String = """
<script>
(function(){
  function scrollFraction(){
    var doc = document.documentElement;
    var scrollable = doc.scrollHeight - doc.clientHeight;
    if (scrollable <= 0) return 1.0;
    return Math.min(1, Math.max(0, doc.scrollTop / scrollable));
  }
  function krScrollTop(){ return (document.scrollingElement || document.documentElement).scrollTop; }
  // The set of block elements we anchor reading position to. Anchoring to a
  // specific block (rather than a raw scroll fraction) keeps the restored
  // position stable when late-loading images/fonts shift the layout.
  function krBlocks(){
    return document.querySelectorAll(
      '#kr-content h1,#kr-content h2,#kr-content h3,#kr-content h4,' +
      '#kr-content p,#kr-content li,#kr-content blockquote,#kr-content pre,' +
      '#kr-content figure,#kr-content img,#kr-content table');
  }
  // Cached block geometry for the per-frame anchor: walking every block with
  // getBoundingClientRect() on each scroll frame is O(article length) in
  // forced layout reads — the main source of scroll jank on long articles.
  // The cache rebuilds whenever the document height changes (images/fonts
  // loading, preference reflows), which is a single cheap read per frame.
  var krBlockTops = null, krBlockHeights = null, krCachedDocHeight = -1;
  function krRebuildBlockCache(){
    var blocks = krBlocks();
    var viewTop = krScrollTop();
    krBlockTops = new Array(blocks.length);
    krBlockHeights = new Array(blocks.length);
    for (var i = 0; i < blocks.length; i++){
      var r = blocks[i].getBoundingClientRect();
      krBlockTops[i] = r.top + viewTop;
      krBlockHeights[i] = r.height || 1;
    }
    krCachedDocHeight = document.documentElement.scrollHeight;
  }
  // "<blockIndex>:<fractionWithinBlock>" for the block at the top of the viewport.
  function krComputeAnchor(){
    if (!krBlockTops || krCachedDocHeight !== document.documentElement.scrollHeight){
      krRebuildBlockCache();
    }
    var n = krBlockTops.length; if (!n) return '';
    var viewTop = krScrollTop();
    // Binary search: last block whose cached top is above the viewport top.
    var lo = 0, hi = n - 1, idx = 0;
    while (lo <= hi){
      var mid = (lo + hi) >> 1;
      if (krBlockTops[mid] <= viewTop + 1){ idx = mid; lo = mid + 1; } else { hi = mid - 1; }
    }
    var frac = Math.min(1, Math.max(0, (viewTop - krBlockTops[idx]) / krBlockHeights[idx]));
    return idx + ':' + frac.toFixed(4);
  }
  function krScrollToAnchor(anchor){
    if (!anchor) return false;
    var parts = anchor.split(':');
    var idx = parseInt(parts[0], 10);
    var frac = parseFloat(parts[1]); if (isNaN(frac)) frac = 0;
    var blocks = krBlocks(); if (!blocks.length || isNaN(idx)) return false;
    if (idx < 0) idx = 0; if (idx >= blocks.length) idx = blocks.length - 1;
    var rect = blocks[idx].getBoundingClientRect();
    var elTop = rect.top + krScrollTop();
    window.scrollTo(0, Math.max(0, elTop + frac * (rect.height || 0)));
    return true;
  }
  var ticking = false;
  var lastY = 0;
  var krLastFraction = 0;
  // While a programmatic restore is moving the page, its scroll events must
  // not read as "reading down" — that hid the chrome the moment a half-read
  // article reopened, with no user gesture at all.
  var krCalmUntil = 0;
  window.addEventListener('scroll', function(){
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(function(){
      var y = document.documentElement.scrollTop;
      var up = y < lastY - 2 || Date.now() < krCalmUntil;
      lastY = y;
      var sf = scrollFraction();
      krLastFraction = sf;
      try { if (window.AndroidReader) AndroidReader.onProgress(sf, krComputeAnchor(), up); } catch(e){}
      ticking = false;
    });
  }, { passive: true });

  // Smoothly move to a 0..1 position — used to follow narration.
  window.krSmoothToFraction = function(f){
    krStopSticky();
    var doc = document.documentElement;
    window.scrollTo({ top: (doc.scrollHeight - doc.clientHeight) * f, left: 0, behavior: 'smooth' });
  };

  // Jump to the Nth article heading (table of contents). The selector/order
  // matches ReaderHtmlBuilder.tableOfContents so the host's index lines up.
  window.krScrollToHeading = function(index){
    krStopSticky();
    var hs = document.querySelectorAll('#kr-content .kr-article h1, #kr-content .kr-article h2, #kr-content .kr-article h3, #kr-content .kr-article h4');
    var el = hs[index];
    if (el && el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    return !!el;
  };

  // Restore position. Prefer the block anchor; fall back to a raw fraction for
  // rows saved before anchors existed.
  var krRestoreActive = false;
  var krStickyTimer = null;
  function krStopSticky(){
    krRestoreActive = false;
    if (krStickyTimer){ clearInterval(krStickyTimer); krStickyTimer = null; }
  }
  window.krStopSticky = krStopSticky;
  window.krRestore = function(fraction){
    krCalmUntil = Date.now() + 700;
    krLastFraction = fraction;
    var doc = document.documentElement;
    window.scrollTo(0, (doc.scrollHeight - doc.clientHeight) * fraction);
  };
  window.krRestoreAnchor = function(anchor){
    krCalmUntil = Date.now() + 700;
    if (!krScrollToAnchor(anchor)) return;
    // Re-pin to the anchor as images/fonts finish loading (they change layout
    // above us), and stop the moment the reader scrolls themselves.
    krRestoreActive = true;
    function reapply(){ if (krRestoreActive) krScrollToAnchor(anchor); }
    var imgs = document.images;
    for (var i = 0; i < imgs.length; i++){
      if (!imgs[i].complete){
        imgs[i].addEventListener('load', reapply);
        imgs[i].addEventListener('error', reapply);
      }
    }
    window.addEventListener('load', reapply);
    var tries = 0;
    krStickyTimer = setInterval(function(){
      if (!krRestoreActive){ krStopSticky(); return; }
      krCalmUntil = Date.now() + 400;
      reapply();
      if (++tries >= 12) krStopSticky();
    }, 200);
  };
  ['touchstart','wheel','keydown','mousedown'].forEach(function(ev){
    window.addEventListener(ev, krStopSticky, { passive: true });
  });

  // One line of body text, in px — the overlap we keep between pages.
  function krLineHeightPx(){
    var el = document.querySelector('#kr-content .kr-article') || document.body;
    var cs = window.getComputedStyle(el);
    var lh = parseFloat(cs.lineHeight);
    if (!lh || isNaN(lh)) lh = parseFloat(cs.fontSize) * 1.5;
    return lh || 24;
  }
  // How much of the bottom the host's "next article" badge is covering (0 when
  // it isn't shown); the host keeps --kr-page-bottom-cover up to date.
  function krBottomCoverPx(){
    var v = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--kr-page-bottom-cover'));
    return (v && !isNaN(v)) ? v : 0;
  }
  // Page up/down by one screenful, less a line of overlap — and less whatever the
  // "next article" badge is covering, so paging never lands text behind it.
  function krEaseOutCubic(t){ return 1 - Math.pow(1 - t, 3); }
  // Generation counter: a new page turn invalidates any still-running one, so
  // rapid taps (or a held volume key) don't run overlapping animations that
  // write scrollTo toward different targets on interleaved frames.
  var krPageAnim = 0;
  window.krPageBy = function(dir){
    krStopSticky();
    var doc = document.scrollingElement || document.documentElement;
    var page = Math.max(40, doc.clientHeight - krLineHeightPx() - krBottomCoverPx());
    var startY = window.pageYOffset || doc.scrollTop || 0;
    var maxY = Math.max(0, doc.scrollHeight - doc.clientHeight);
    var targetY = Math.min(maxY, Math.max(0, startY + page * dir));
    var dist = targetY - startY;
    if (dist === 0) return;
    // A custom rAF scroll instead of CSS 'smooth', whose duration isn't tunable
    // and runs slower than feels right for page turns.
    var duration = 160;
    var start = null;
    var myAnim = ++krPageAnim;
    function step(ts){
      if (myAnim !== krPageAnim) return; // superseded by a newer page turn
      if (start === null) start = ts;
      var t = Math.min(1, (ts - start) / duration);
      window.scrollTo(0, startY + dist * krEaseOutCubic(t));
      if (t < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  };
  // A tap on a highlight asks the host about it. (Chrome toggling is handled
  // natively via a gesture detector, which is more reliable than a JS click.)
  document.addEventListener('click', function(e){
    var n = e.target;
    while (n && n !== document.body) {
      if (n.classList && n.classList.contains('kr-hl')) {
        var id = n.getAttribute('data-id');
        try { if (window.AndroidReader && AndroidReader.onHighlightTap) AndroidReader.onHighlightTap(id); } catch(err){}
        return;
      }
      n = n.parentNode;
    }
  }, false);

  // In-page anchor / footnote links: scroll within the article instead of
  // navigating away. With the server origin as the document's base URL, a bare
  // "#fn1" otherwise resolves to the server root and gets handed to an external
  // browser. Only same-document fragments are intercepted; genuine external
  // links fall through to the host (which opens them in the browser).
  document.addEventListener('click', function(e){
    var n = e.target;
    while (n && n !== document.body) {
      if (n.tagName === 'A' && n.getAttribute && n.getAttribute('href')) {
        var raw = n.getAttribute('href');
        var hash = '';
        if (raw.charAt(0) === '#') hash = raw;
        else if (n.hash && n.pathname === location.pathname && n.search === location.search) hash = n.hash;
        if (hash.length > 1) {
          // Same-document fragment: always consume it. Falling through for a
          // missing target used to resolve "#fn1" against the server-origin
          // base URL and launch an external browser at the Karakeep root.
          e.preventDefault();
          var id; try { id = decodeURIComponent(hash.substring(1)); } catch(err){ id = hash.substring(1); }
          var target = document.getElementById(id) || document.getElementsByName(id)[0];
          if (target && target.scrollIntoView) {
            krStopSticky();
            target.scrollIntoView({ behavior: 'smooth', block: 'start' });
          }
          return;
        }
      }
      n = n.parentNode;
    }
  }, false);
  ${highlightJs()}
  // Signal that the document is ready for progress restore.
  window.requestAnimationFrame(function(){
    try { if (window.AndroidReader) AndroidReader.onReady(); } catch(e){}
    // An article shorter than one screen can never scroll, so no progress
    // event would ever fire: report completion once so the progress line and
    // the end-of-article affordances still work. (up=true so nothing hides.)
    var doc = document.documentElement;
    if (doc.scrollHeight - doc.clientHeight <= 0) {
      try { if (window.AndroidReader) AndroidReader.onProgress(1.0, '', true); } catch(e){}
    }
  });
})();
</script>
    """.trimIndent()

    /**
     * Selection-capture + highlight-rendering JS. Offsets are character indices
     * into the concatenated text of the article container, computed the same way
     * on capture and on render so our own highlights round-trip exactly.
     */
    private fun highlightJs(): String = """
  function krRoot(){ return document.querySelector('#kr-content .kr-article'); }
  function krTextOffset(root, node, offset){
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var total = 0, n;
    while (n = walker.nextNode()){
      if (n === node) return total + offset;
      total += n.nodeValue.length;
    }
    return total + offset;
  }
  // Turn a DOM range (and its visible text) into character offsets into the
  // article, or null if it falls outside the article or selects nothing.
  function krCaptureRange(range, text){
    var root = krRoot(); if (!root || !range) return null;
    if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) return null;
    var start = krTextOffset(root, range.startContainer, range.startOffset);
    var end = krTextOffset(root, range.endContainer, range.endOffset);
    if (end > start && text && text.trim().length > 0) return { start: start, end: end, text: text };
    return null;
  }
  // The live, non-collapsed selection (range + text) if it lies inside the
  // article, else null.
  function krLiveSelection(){
    var sel = window.getSelection();
    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
    var range = sel.getRangeAt(0), root = krRoot();
    if (!root || !root.contains(range.startContainer) || !root.contains(range.endContainer)) return null;
    return { range: range, text: sel.toString() };
  }
  // Remember the last real selection — the action-mode "Highlight" item finishes
  // the action (clearing the live selection) before our async capture runs, so we
  // keep a clone to fall back on. Offsets are computed lazily, at capture time,
  // so dragging the selection handles stays cheap on long articles.
  var krLastRange = null, krLastText = '';
  // Signature ("start..end") and time of the last range we reported. Some OEM
  // selection toolbars (notably Huawei's HwFloatingToolbar) re-invoke the
  // "Highlight" action in a tight loop while the selection stays live, so a plain
  // once-flag is unsafe: the live selection re-appears between invocations. We
  // instead report a given range at most once per short window. krAttemptAt drives
  // a cheap sliding throttle that swallows the burst *before* the per-call offset
  // walk, so the loop can't jank a long article.
  var krReportedSig = '', krReportedAt = 0, krAttemptAt = 0;
  document.addEventListener('selectionchange', function(){
    var live = krLiveSelection();
    if (live){
      krLastRange = live.range.cloneRange();
      krLastText = live.text;
    }
  });
  // Called from the native "Highlight" selection action.
  window.krCaptureSelection = function(){
    var now = Date.now();
    // Collapse a burst of rapid re-invocations (the OEM loop) cheaply.
    var burst = (now - krAttemptAt) < 400;
    krAttemptAt = now;
    if (burst) return 'throttled';
    var live = krLiveSelection();
    var cap = (live && krCaptureRange(live.range, live.text)) ||
              krCaptureRange(krLastRange, krLastText);
    if (cap){
      var sig = cap.start + '..' + cap.end;
      if (sig === krReportedSig && (now - krReportedAt) < 1500){
        // Same range already reported moments ago — ignore the repeat.
        krLastRange = null; krLastText = '';
        var s0 = window.getSelection(); if (s0){ try { s0.removeAllRanges(); } catch(e){} }
        return 'dup';
      }
      krReportedSig = sig; krReportedAt = now;
      try { if (window.AndroidReader && AndroidReader.onSelection) AndroidReader.onSelection(cap.text, cap.start, cap.end); }
      catch(e){}
    }
    krLastRange = null; krLastText = '';
    var sel = window.getSelection();
    if (sel){ try { sel.removeAllRanges(); } catch(e){} }
    return cap ? 'ok' : 'nocap';
  };
  function krUnwrap(root){
    var marks = root.querySelectorAll('mark.kr-hl');
    for (var i = 0; i < marks.length; i++){
      var m = marks[i], p = m.parentNode;
      while (m.firstChild) p.insertBefore(m.firstChild, m);
      p.removeChild(m);
    }
    root.normalize();
  }
  function krWrapRange(root, start, end, id){
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var nodes = [], n; while (n = walker.nextNode()) nodes.push(n);
    var pos = 0;
    for (var i = 0; i < nodes.length; i++){
      var node = nodes[i]; var len = node.nodeValue.length;
      var nodeStart = pos, nodeEnd = pos + len; pos = nodeEnd;
      if (nodeEnd <= start || nodeStart >= end) continue;
      var s = Math.max(start, nodeStart) - nodeStart;
      var e = Math.min(end, nodeEnd) - nodeStart;
      try {
        var range = document.createRange();
        range.setStart(node, s); range.setEnd(node, e);
        var mark = document.createElement('mark');
        mark.className = 'kr-hl'; mark.setAttribute('data-id', id);
        range.surroundContents(mark);
      } catch(err){}
    }
  }
  window.krApplyHighlights = function(jsonStr){
    var root = krRoot(); if (!root) return;
    krUnwrap(root);
    var list; try { list = JSON.parse(jsonStr); } catch(e){ return; }
    list.sort(function(a, b){ return b.start - a.start; });
    for (var i = 0; i < list.length; i++){
      var h = list[i];
      if (typeof h.start === 'number' && typeof h.end === 'number' && h.end > h.start){
        krWrapRange(root, h.start, h.end, String(h.id));
      }
    }
  };
  // Jump to a saved highlight's mark (from the Highlights screen). Stops any
  // in-progress restore so it doesn't fight the jump.
  window.krScrollToHighlight = function(id){
    if (window.krStopSticky) krStopSticky();
    var el = document.querySelector('mark.kr-hl[data-id="' + id + '"]');
    if (el && el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    return !!el;
  };
    """.trimIndent()

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
