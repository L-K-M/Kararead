package ch.lkmc.kararead.reader

import ch.lkmc.kararead.data.model.ReaderArticle
import ch.lkmc.kararead.data.model.ReaderPreferences
import ch.lkmc.kararead.data.model.ReaderTheme
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/** Palette for a reader theme (CSS color strings). */
data class ReaderPalette(
    val background: String,
    val surface: String,
    val text: String,
    val secondary: String,
    val link: String,
    val border: String,
    val codeBg: String,
    /** "dark" or "light" — drives the WebView's color-scheme & scrollbar. */
    val scheme: String,
)

object ReaderHtmlBuilder {

    fun paletteFor(theme: ReaderTheme): ReaderPalette = when (theme) {
        ReaderTheme.LIGHT -> ReaderPalette(
            background = "#fdfdfb", surface = "#f3f3ee", text = "#1b1b1b",
            secondary = "#6b6b6b", link = "#1565c0", border = "#e3e3dd",
            codeBg = "#f0f0ea", scheme = "light",
        )
        ReaderTheme.SEPIA -> ReaderPalette(
            background = "#f4ecd8", surface = "#ece1c7", text = "#4a3f35",
            secondary = "#7a6a55", link = "#9a5b2b", border = "#ddd0b3",
            codeBg = "#e9ddc2", scheme = "light",
        )
        ReaderTheme.DARK -> ReaderPalette(
            background = "#1a1a1a", surface = "#262626", text = "#d8d4cc",
            secondary = "#9a958c", link = "#82b1e6", border = "#333333",
            codeBg = "#222222", scheme = "dark",
        )
        ReaderTheme.BLACK -> ReaderPalette(
            background = "#000000", surface = "#101010", text = "#cbc7bf",
            secondary = "#8a857c", link = "#82b1e6", border = "#222222",
            codeBg = "#0c0c0c", scheme = "dark",
        )
    }

    /** Sanitize crawled HTML: keep rich structure, drop scripts/styles/iframes. */
    private fun sanitize(html: String): String {
        val safelist = Safelist.relaxed()
            .addTags("figure", "figcaption", "h1", "h2", "section", "article", "mark", "hr")
            .addAttributes("img", "src", "alt", "title", "width", "height")
            .addAttributes("a", "href", "title")
            .addProtocols("img", "src", "http", "https", "data")
        return Jsoup.clean(html, safelist)
    }

    fun build(article: ReaderArticle, prefs: ReaderPreferences): String {
        val palette = paletteFor(prefs.theme)
        val bm = article.bookmark
        val body = article.htmlContent?.let { sanitize(it) }
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
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">
<style>
${baseCss()}
${variableCss(palette, prefs)}
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
</body>
</html>
        """.trimIndent()
    }

    /** Runtime-updatable CSS variables, also emitted as JS for live preference changes. */
    fun variableCss(palette: ReaderPalette, prefs: ReaderPreferences): String {
        val baseFontPx = (19 * prefs.fontScale)
        return """
:root {
  --kr-bg: ${palette.background};
  --kr-surface: ${palette.surface};
  --kr-text: ${palette.text};
  --kr-secondary: ${palette.secondary};
  --kr-link: ${palette.link};
  --kr-border: ${palette.border};
  --kr-code-bg: ${palette.codeBg};
  --kr-font: ${prefs.font.cssStack};
  --kr-font-size: ${"%.1f".format(baseFontPx)}px;
  --kr-line-height: ${prefs.lineHeight};
  --kr-margin: ${prefs.horizontalMargin}px;
  --kr-align: ${if (prefs.justify) "justify" else "start"};
  color-scheme: ${palette.scheme};
}
        """.trimIndent()
    }

    /** JS snippet to apply preference changes without reloading the document. */
    fun applyPrefsScript(palette: ReaderPalette, prefs: ReaderPreferences): String {
        val baseFontPx = (19 * prefs.fontScale)
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
  r.setProperty('--kr-font', "${prefs.font.cssStack}");
  r.setProperty('--kr-font-size', '${"%.1f".format(baseFontPx)}px');
  r.setProperty('--kr-line-height', '${prefs.lineHeight}');
  r.setProperty('--kr-margin', '${prefs.horizontalMargin}px');
  r.setProperty('--kr-align', '${if (prefs.justify) "justify" else "start"}');
  document.documentElement.style.colorScheme = '${palette.scheme}';
})();
        """.trimIndent()
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
  padding: 28px var(--kr-margin) 96px var(--kr-margin);
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
.kr-article { text-align: var(--kr-align); }
.kr-article p { margin: 0 0 1.1em 0; -webkit-hyphens: auto; hyphens: auto; }
.kr-article a { color: var(--kr-link); text-decoration: none; border-bottom: 1px solid color-mix(in srgb, var(--kr-link) 35%, transparent); }
.kr-article h1, .kr-article h2, .kr-article h3, .kr-article h4 {
  line-height: 1.3; margin: 1.6em 0 .5em 0; font-weight: 700; text-align: start;
}
.kr-article h2 { font-size: 1.35em; }
.kr-article h3 { font-size: 1.15em; }
.kr-article img, .kr-article video { max-width: 100%; height: auto; border-radius: 6px; display: block; margin: 1.2em auto; }
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
  var ticking = false;
  var lastY = 0;
  window.addEventListener('scroll', function(){
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(function(){
      var y = document.documentElement.scrollTop;
      var up = y < lastY - 2;
      lastY = y;
      try { if (window.AndroidReader) AndroidReader.onProgress(scrollFraction(), up); } catch(e){}
      ticking = false;
    });
  }, { passive: true });
  window.krRestore = function(fraction){
    var doc = document.documentElement;
    var scrollable = doc.scrollHeight - doc.clientHeight;
    window.scrollTo(0, scrollable * fraction);
  };
  // Page up/down by (almost) a screenful — driven by the hardware volume keys.
  window.krPageBy = function(dir){
    var doc = document.scrollingElement || document.documentElement;
    var page = Math.max(40, doc.clientHeight - 64);
    window.scrollBy({ top: page * dir, left: 0, behavior: 'smooth' });
  };
  // A tap in the reading column (not on a link, and not a text selection)
  // toggles the app chrome.
  document.addEventListener('click', function(e){
    var n = e.target;
    while (n && n !== document.body) {
      var tag = n.tagName;
      if (tag === 'A' || tag === 'BUTTON' || tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
      n = n.parentNode;
    }
    var sel = window.getSelection && window.getSelection().toString();
    if (sel && sel.length > 0) return;
    try { if (window.AndroidReader && AndroidReader.onTap) AndroidReader.onTap(); } catch(e){}
  }, false);
  // Signal that the document is ready for progress restore.
  window.requestAnimationFrame(function(){
    try { if (window.AndroidReader) AndroidReader.onReady(); } catch(e){}
  });
})();
</script>
    """.trimIndent()

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
