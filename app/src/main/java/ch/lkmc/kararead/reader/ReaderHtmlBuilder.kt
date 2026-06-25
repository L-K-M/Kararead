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
    private fun sanitize(html: String, baseUri: String): String {
        val safelist = Safelist.relaxed()
            .addTags("figure", "figcaption", "h1", "h2", "section", "article", "mark", "hr")
            .addAttributes("img", "src", "alt", "title", "width", "height")
            .addAttributes("a", "href", "title")
            .addProtocols("img", "src", "http", "https", "data")
        // Pass the server origin as the base URI so Karakeep's *relative* asset
        // paths (e.g. /api/assets/<id>) resolve to absolute https URLs and
        // survive sanitization — otherwise protocol-restricted img[src] with no
        // base resolves to nothing and the image is silently dropped before the
        // WebView's auth-injecting interceptor ever sees it.
        return Jsoup.clean(html, baseUri, safelist)
    }

    fun build(article: ReaderArticle, prefs: ReaderPreferences, baseUri: String? = null, safeTopPx: Int = 0): String {
        val palette = paletteFor(prefs.theme)
        val bm = article.bookmark
        val body = article.htmlContent?.let { sanitize(it, baseUri.orEmpty()) }
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
${fontFaceCss()}
${baseCss()}
${variableCss(palette, prefs, safeTopPx)}
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
    fun variableCss(palette: ReaderPalette, prefs: ReaderPreferences, safeTopPx: Int = 0): String {
        val baseFontPx = (19 * prefs.fontScale)
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
  background: color-mix(in srgb, #ffd54f 55%, transparent);
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
  // "<blockIndex>:<fractionWithinBlock>" for the block at the top of the viewport.
  function krComputeAnchor(){
    var blocks = krBlocks(); if (!blocks.length) return '';
    var viewTop = krScrollTop();
    var idx = 0;
    for (var i = 0; i < blocks.length; i++){
      var top = blocks[i].getBoundingClientRect().top + viewTop;
      if (top <= viewTop + 1) idx = i; else break;
    }
    var rect = blocks[idx].getBoundingClientRect();
    var elTop = rect.top + viewTop;
    var h = rect.height || 1;
    var frac = Math.min(1, Math.max(0, (viewTop - elTop) / h));
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
  window.addEventListener('scroll', function(){
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(function(){
      var y = document.documentElement.scrollTop;
      var up = y < lastY - 2;
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
    krLastFraction = fraction;
    var doc = document.documentElement;
    window.scrollTo(0, (doc.scrollHeight - doc.clientHeight) * fraction);
  };
  window.krRestoreAnchor = function(anchor){
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
    function step(ts){
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
          var id; try { id = decodeURIComponent(hash.substring(1)); } catch(err){ id = hash.substring(1); }
          var target = document.getElementById(id) || document.getElementsByName(id)[0];
          if (target && target.scrollIntoView) {
            e.preventDefault();
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
    """.trimIndent()

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
