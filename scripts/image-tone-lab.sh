#!/usr/bin/env bash
#
# Run the reader's image-tone sampler in a headless Chromium against a set of
# synthetic article images and print what ImageTone would decide for each.
#
# The sampler is the <script> inside ReaderHtmlBuilder.imageToneScript, taken
# from the source at run time with its interpolated constants resolved from
# ImageTone.kt; the judge is a JavaScript mirror of ImageTone.shouldInvert fed
# the same constants. So the numbers here are what a device would measure —
# real text rendering, real JPEG decoding, real nearest-neighbour downscaling —
# and a threshold change in ImageTone.kt is measured, not argued.
#
# Each case says what it expects. A line marked XXX is a wrong verdict and
# fails the run; "kn" marks a known limitation of a histogram-only classifier
# (a flat, bright photograph with a few dark objects); "?" marks an open
# question where either verdict is defensible.
#
# Usage:
#   scripts/image-tone-lab.sh                  run every case, print the report
#   scripts/image-tone-lab.sh --set MIN_DARK=0.003 [--set ...]
#                                              try a threshold without editing
#                                              the source (applies to both the
#                                              sampler and the judge)
#   scripts/image-tone-lab.sh --html lab.html  also keep the page, to open in a
#                                              desktop browser and *see* the
#                                              inversion applied
#   scripts/image-tone-lab.sh --help           show this help
#
# Needs a Chromium: $CHROME, or chromium / chromium-browser / google-chrome on
# PATH, or the Playwright install under /opt/pw-browsers.
#
set -euo pipefail

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
CALLER_PWD="$PWD"
cd "$(dirname "$SELF")/.."

usage() { awk 'NR==1 && /^#!/ {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "$SELF"; }

KEEP_HTML=""
OVERRIDES=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --set) [[ "${2:-}" =~ ^[A-Za-z_][A-Za-z0-9_]*=[0-9]+(\.[0-9]+)?$ ]] || { echo "!! --set wants NAME=NUMBER" >&2; exit 2; }
           OVERRIDES+=("$2"); shift 2 ;;
    --html) [[ -n "${2:-}" ]] || { echo "!! --html wants a path" >&2; exit 2; }
            KEEP_HTML="$2"; [[ "$KEEP_HTML" == /* ]] || KEEP_HTML="$CALLER_PWD/$KEEP_HTML"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
done

BUILDER=app/src/main/java/ch/lkmc/kararead/reader/ReaderHtmlBuilder.kt
TONE=app/src/main/java/ch/lkmc/kararead/reader/ImageTone.kt

# Every `const val NAME = number` in ImageTone.kt, public and private alike,
# as NAME=VALUE lines: the sampler's interpolations and the judge's thresholds
# both come from here. (Plain lines rather than an associative array so the
# script runs on the bash 3 that macOS ships.)
CONSTS="$(awk 'match($0, /const val [A-Z_]+ = [^ ]+/) { split(substr($0, RSTART, RLENGTH), a, " "); print a[3] "=" a[5] }' "$TONE")"
# Only a plain decimal is usable here. A literal that would be misread (1e-3,
# 5_000, 0x10, 0.5f) or a declaration the pattern cannot read at all (a type
# annotation) would leave the sampler and the judge running against numbers
# that are not ImageTone's, with nothing to say so. Refuse instead.
n_decl="$(grep -Ec '^[[:space:]]*(private[[:space:]]+)?const val ' "$TONE" || true)"
n_read="$(grep -c . <<<"$CONSTS" || true)"
[[ "$n_decl" == "$n_read" ]] || { echo "!! read $n_read of $n_decl const vals in $TONE; only \`NAME = <number>\` is understood" >&2; exit 1; }
while IFS='=' read -r name value; do
  [[ "$value" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "!! $name = $value in $TONE: only a plain decimal is understood" >&2; exit 1; }
done <<<"$CONSTS"
for kv in ${OVERRIDES[@]+"${OVERRIDES[@]}"}; do
  name="${kv%%=*}"
  grep -q "^$name=" <<<"$CONSTS" || { echo "!! no such constant in ImageTone.kt: $name" >&2; exit 2; }
  CONSTS="$(sed "s/^$name=.*/$kv/" <<<"$CONSTS")"
  echo "-- $kv"
done

# The sampler: from `private fun imageToneScript` to the end of its <script>.
SAMPLER="$(awk '/private fun imageToneScript/ {f=1} f && /<script>/ {p=1} p {print} p && /<\/script>/ {exit}' "$BUILDER")"
[[ -n "$SAMPLER" ]] || { echo "!! could not find imageToneScript in $BUILDER" >&2; exit 1; }
KJS="var K = {"
while IFS='=' read -r name value; do
  SAMPLER="${SAMPLER//\$\{ImageTone.$name\}/$value}"
  KJS+="$name: $value, "
done <<<"$CONSTS"
KJS+="};"
SAMPLER="${SAMPLER//\$\{initiallyOn\}/true}"
[[ "$SAMPLER" != *'${'* ]] || { echo "!! unresolved interpolation left in the sampler" >&2; exit 1; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
if [[ -n "$KEEP_HTML" ]]; then HTML="$KEEP_HTML"; else HTML="$TMP/lab.html"; fi

{
cat <<'EOF'
<!doctype html><html><head><meta charset="utf-8"><title>Kararead image-tone lab</title>
<style>
  body { background: #1a1a1a; color: #d8d4cc; font: 14px/1.4 sans-serif; margin: 16px; }
  .kr-article { display: flex; flex-wrap: wrap; gap: 14px; }
  figure { margin: 0; width: 300px; }
  figure img { max-width: 300px; max-height: 300px; display: block; background: #fff0; }
  figcaption { font-size: 12px; color: #9a958c; }
  html.kr-invert-images .kr-article img.kr-bright { filter: invert(0.9) hue-rotate(180deg); }
  pre { font-size: 12px; white-space: pre; }
</style></head><body>
<p>Dark page. Images the sampler marks bright are shown with the reader's Dark-theme filter applied.</p>
<div class="kr-article" id="art"></div>
<script>
// Its own script element, so a script below that fails to even parse is still
// caught here rather than leaving the page with no report at all.
var errors = [];
window.addEventListener('error', function(e){ errors.push(e.message || 'script error'); });
</script>
<script>
EOF
printf '%s\n' "$KJS"
cat <<'EOF'
// Mirror of ImageTone.shouldInvert, keeping the list of conjuncts that failed.
function verdict(s){
  var mid = Math.max(0, 1 - s.light - s.dark), fails = [];
  if (!(s.samples >= K.MIN_SAMPLES)) fails.push('samples');
  if (s.clear >= K.LIGHT_ART_MIN_CLEAR && s.opaqueLight >= K.LIGHT_ART_MIN_OPAQUE_LIGHT) fails.push('lightArtOnAlpha');
  if (!(s.light >= K.MIN_LIGHT)) fails.push('light<' + K.MIN_LIGHT);
  if (!(s.dark >= K.MIN_DARK)) fails.push('dark<' + K.MIN_DARK);
  if (!(s.dark <= K.MAX_DARK)) fails.push('dark>' + K.MAX_DARK);
  if (!(mid <= K.MAX_MID)) fails.push('mid>' + K.MAX_MID);
  if (!(s.peak >= K.MIN_PEAK)) fails.push('peak<' + K.MIN_PEAK);
  if (!(s.peakLevel >= K.LIGHT_LEVEL)) fails.push('peakLevel<' + K.LIGHT_LEVEL);
  if (!(s.sat <= K.MAX_MEAN_SATURATION)) fails.push('sat>' + K.MAX_MEAN_SATURATION);
  if (!(s.vivid <= K.MAX_VIVID)) fails.push('vivid>' + K.MAX_VIVID);
  if (!(s.colors <= K.MAX_COLOR_BUCKETS)) fails.push('colors>' + K.MAX_COLOR_BUCKETS);
  if (!(s.border >= K.MIN_BORDER_LIGHT)) fails.push('border<' + K.MIN_BORDER_LIGHT);
  s.mid = mid; s.fails = fails;
  return fails.length === 0;
}
// Stands in for the ReaderBridge. The sampler writes data-kr-tone right after
// asking, so hooking setAttribute pins each answer to its image.
var last = null;
window.AndroidReader = { shouldInvertImage: function(light, dark, sat, vivid, border, peak, peakLevel, colors, samples, clear, opaqueLight){
  last = {light: light, dark: dark, sat: sat, vivid: vivid, border: border, peak: peak, peakLevel: peakLevel, colors: colors, samples: samples, clear: clear, opaqueLight: opaqueLight};
  return verdict(last);
}};
var setAttr = Element.prototype.setAttribute;
Element.prototype.setAttribute = function(n, v){
  if (n === 'data-kr-tone' && (v === 'bright' || v === 'plain') && last){ this.krStats = last; last = null; }
  return setAttr.apply(this, arguments);
};

var LOREM = 'The quick brown fox jumps over the lazy dog while the committee reviewed the quarterly figures and found that the numbers did not add up in the way anyone had expected so a second audit was commissioned for the following spring';
var TAU = Math.PI * 2;
function mk(w, h){ var c = document.createElement('canvas'); c.width = w; c.height = h; return c; }
function fill(ctx, w, h, c){ ctx.fillStyle = c; ctx.fillRect(0, 0, w, h); }
function paintText(ctx, w, h, o){
  o = o || {};
  ctx.font = o.font || '16px sans-serif'; ctx.fillStyle = o.color || '#000'; ctx.textBaseline = 'top';
  var lh = o.lh || 24, x0 = o.x0 == null ? 40 : o.x0, y = o.y0 == null ? 40 : o.y0;
  var wrapW = o.wrapW || (w - x0 - 40), yMax = o.yMax || (h - lh), words = LOREM.split(' '), line = '';
  for (var i = 0; y < yMax; i++){
    var word = words[i % words.length], test = line ? line + ' ' + word : word;
    if (ctx.measureText(test).width > wrapW){ ctx.fillText(line, x0, y); y += lh; line = word; } else line = test;
  }
}
// A seeded generator instead of Math.random(), so the page is the same every
// run and a change in the numbers is a change in the sampler or the thresholds,
// not in the dice. add() reseeds per case so inserting one leaves the rest alone.
var seed = 1;
function rand(){ seed = (seed + 0x6D2B79F5) | 0; var t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }
function gauss(){ var u = 1 - rand(), v = rand(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); }
function noise(ctx, w, h, sigma){
  var id = ctx.getImageData(0, 0, w, h), d = id.data;
  for (var i = 0; i < d.length; i += 4){ var n = gauss() * sigma; for (var c = 0; c < 3; c++) d[i + c] = Math.max(0, Math.min(255, d[i + c] + n)); }
  ctx.putImageData(id, 0, 0);
}
// Subpixel antialiasing: render at 3x horizontal resolution and take R, G, B
// from the three subpixels — raw, or through the 5-tap filter FreeType and
// ClearType actually apply.
function subpixel(ctx, w, h, filtered){
  var c3 = mk(w * 3, h), c = c3.getContext('2d'); fill(c, w * 3, h, '#fff'); c.scale(3, 1); paintText(c, w, h);
  var s = c.getImageData(0, 0, w * 3, h).data, out = ctx.createImageData(w, h), d = out.data, W3 = w * 3;
  function g(y, i){ i = Math.max(0, Math.min(W3 - 1, i)); return s[(y * W3 + i) * 4]; }
  for (var y = 0; y < h; y++) for (var x = 0; x < w; x++){
    var o = (y * w + x) * 4;
    for (var ch = 0; ch < 3; ch++){
      var i = x * 3 + ch;
      d[o + ch] = filtered ? (g(y, i - 2) + 2 * g(y, i - 1) + 3 * g(y, i) + 2 * g(y, i + 1) + g(y, i + 2)) / 9 : g(y, i);
    }
    d[o + 3] = 255;
  }
  ctx.putImageData(out, 0, 0);
}

// add(name, expect, known, w, h, paint, type, jpegQuality)
//   expect: true / false / null (open question); known: a wrong verdict here
//   is a documented limitation, reported but not a failure.
var cases = [];
function add(name, expect, known, w, h, paint, type, q){
  var c = mk(w, h), ctx = c.getContext('2d', {willReadFrequently: true});
  seed = 1; paint(ctx, w, h);
  cases.push({name: name, expect: expect, known: !!known, url: type === 'jpeg' ? c.toDataURL('image/jpeg', q) : c.toDataURL('image/png')});
}
function textOnWhite(ctx, w, h){ fill(ctx, w, h, '#fff'); paintText(ctx, w, h); }

// —— Documents: these are what the feature exists for. ——
add('text_png', true, false, 1200, 800, textOnWhite, 'png');
add('text_jpeg_q60', true, false, 1200, 800, textOnWhite, 'jpeg', 0.6);
add('text_jpeg_q85', true, false, 1200, 800, textOnWhite, 'jpeg', 0.85);
add('text_12px', true, false, 1200, 800, function(ctx, w, h){ fill(ctx, w, h, '#fff'); paintText(ctx, w, h, {font: '12px sans-serif', lh: 16}); }, 'png');
add('subpixel_raw', true, false, 1200, 800, function(ctx, w, h){ subpixel(ctx, w, h, false); }, 'png');
add('subpixel_5tap', true, false, 1200, 800, function(ctx, w, h){ subpixel(ctx, w, h, true); }, 'png');
add('dense_terminal', true, false, 1200, 800, function(ctx, w, h){ fill(ctx, w, h, '#fff'); paintText(ctx, w, h, {font: 'bold 15px monospace', lh: 17, x0: 8, y0: 8, wrapW: w - 16, yMax: h - 8}); }, 'png');
add('phone_material', true, false, 1080, 2340, function(ctx, w, h){
  fill(ctx, w, h, '#fff'); fill(ctx, w, 90, '#0d47a1'); ctx.fillStyle = '#1976d2'; ctx.fillRect(0, 90, w, 170);
  ctx.fillStyle = '#fff'; ctx.font = '48px sans-serif'; ctx.textBaseline = 'top'; ctx.fillText('Inbox', 60, 140);
  paintText(ctx, w, h, {font: '38px sans-serif', lh: 58, x0: 60, y0: 320, yMax: 2150});
  ctx.fillStyle = '#9e9e9e'; for (var i = 0; i < 4; i++) ctx.fillRect(160 + i * 240, 2230, 60, 60);
}, 'png');
add('light_grey_ui', true, false, 1200, 800, function(ctx, w, h){
  fill(ctx, w, h, '#f5f5f5');
  for (var i = 0; i < 3; i++){ ctx.fillStyle = '#fff'; ctx.fillRect(40, 40 + i * 250, w - 80, 220); ctx.strokeStyle = '#e0e0e0'; ctx.strokeRect(40, 40 + i * 250, w - 80, 220); paintText(ctx, w, h, {color: '#212121', x0: 60, y0: 60 + i * 250, yMax: 230 + i * 250, wrapW: w - 160}); }
}, 'png');
add('emoji_chat', true, false, 1080, 1600, function(ctx, w, h){
  fill(ctx, w, h, '#fff'); paintText(ctx, w, h, {font: '30px sans-serif', lh: 60, x0: 60, y0: 60, yMax: h - 60, wrapW: w - 300});
  var cols = ['#f44336', '#ffeb3b', '#4caf50', '#2196f3', '#ff9800', '#9c27b0'];
  for (var i = 0; i < 45; i++){ var x = 880 + (i % 3) * 60, y = 80 + Math.floor(i / 3) * 100; ctx.fillStyle = cols[i % 6]; ctx.beginPath(); ctx.arc(x, y, 18, 0, TAU); ctx.fill(); ctx.fillStyle = cols[(i + 2) % 6]; ctx.beginPath(); ctx.arc(x - 5, y - 4, 7, 0, TAU); ctx.fill(); }
}, 'png');
add('scanned_doc', true, false, 1000, 1400, function(ctx, w, h){
  fill(ctx, w, h, 'rgb(238,234,226)'); var g = ctx.createRadialGradient(500, 700, 300, 500, 700, 900); g.addColorStop(0, 'rgba(0,0,0,0)'); g.addColorStop(1, 'rgba(0,0,0,0.08)'); ctx.fillStyle = g; ctx.fillRect(0, 0, w, h);
  paintText(ctx, w, h, {font: '20px serif', lh: 30, x0: 90, y0: 110, yMax: h - 110, wrapW: w - 180, color: '#151515'}); noise(ctx, w, h, 6);
}, 'jpeg', 0.8);
add('wide_banner', true, false, 2000, 120, function(ctx, w, h){ fill(ctx, w, h, '#fff'); paintText(ctx, w, h, {font: '22px sans-serif', lh: 30, x0: 20, y0: 15, yMax: h - 15, wrapW: w - 40}); }, 'png');
add('bg_200_boundary', true, false, 1200, 800, function(ctx, w, h){ fill(ctx, w, h, 'rgb(200,200,200)'); paintText(ctx, w, h); }, 'png');

// —— Diagrams: dark line art, mostly paper, very little ink. ——
add('line_art_alpha', true, false, 1000, 700, function(ctx, w, h){
  ctx.strokeStyle = '#000'; ctx.lineWidth = 2; ctx.fillStyle = '#000'; ctx.font = '18px sans-serif'; ctx.textBaseline = 'top';
  for (var i = 0; i < 4; i++){ ctx.strokeRect(60 + i * 230, 100, 180, 90); ctx.fillText('Stage ' + (i + 1), 90 + i * 230, 130); if (i < 3){ ctx.beginPath(); ctx.moveTo(240 + i * 230, 145); ctx.lineTo(290 + i * 230, 145); ctx.stroke(); } }
  ctx.beginPath(); ctx.moveTo(100, 400); ctx.bezierCurveTo(300, 250, 600, 650, 900, 400); ctx.stroke();
}, 'png');
add('pastel_flowchart', true, false, 1000, 700, function(ctx, w, h){
  fill(ctx, w, h, '#fff'); var fills = ['#cce5ff', '#d5f5e3', '#fdebd0', '#e8daef', '#fadbd8'];
  for (var i = 0; i < 5; i++){ var x = 60 + (i % 3) * 320, y = 80 + Math.floor(i / 3) * 300; ctx.fillStyle = fills[i]; ctx.fillRect(x, y, 260, 200); ctx.strokeStyle = '#333'; ctx.strokeRect(x, y, 260, 200); ctx.fillStyle = '#000'; ctx.font = '18px sans-serif'; ctx.textBaseline = 'top'; ctx.fillText('Process step ' + (i + 1), x + 30, y + 80); }
}, 'png');
// Hairline strokes: the sparsest document there is, and the one MIN_DARK has
// the least room for. A 1px line is hit by the nearest-neighbour sample in
// only every eighth column, so most of the ink here is the labels.
add('hairline_diagram', true, false, 1000, 700, function(ctx, w, h){
  fill(ctx, w, h, '#fff'); ctx.strokeStyle = '#000'; ctx.lineWidth = 1; ctx.fillStyle = '#000'; ctx.font = '14px sans-serif'; ctx.textBaseline = 'top';
  for (var i = 0; i < 4; i++){ ctx.strokeRect(60.5 + i * 230, 100.5, 180, 90); ctx.fillText('Step ' + (i + 1), 90 + i * 230, 135); if (i < 3){ ctx.beginPath(); ctx.moveTo(240.5 + i * 230, 145.5); ctx.lineTo(290.5 + i * 230, 145.5); ctx.stroke(); } }
  for (var j = 0; j < 6; j++){ ctx.beginPath(); ctx.moveTo(100.5, 300.5 + j * 60); ctx.lineTo(900.5, 300.5 + j * 60); ctx.stroke(); }
}, 'png');
// Hairline axes, coloured (mid-luminance) series, a few tiny labels: 0.1% ink,
// which is not distinguishable from a light-on-alpha logo with a stray shadow.
add('line_chart', null, false, 1000, 600, function(ctx, w, h){
  fill(ctx, w, h, '#fff'); ctx.strokeStyle = '#ddd'; ctx.lineWidth = 1; for (var i = 0; i < 10; i++){ ctx.beginPath(); ctx.moveTo(80, 40 + i * 50); ctx.lineTo(w - 40, 40 + i * 50); ctx.stroke(); }
  ctx.strokeStyle = '#000'; ctx.beginPath(); ctx.moveTo(80, 40); ctx.lineTo(80, h - 80); ctx.lineTo(w - 40, h - 80); ctx.stroke();
  ctx.lineWidth = 2; ['#1e88e5', '#e53935'].forEach(function(c, k){ ctx.strokeStyle = c; ctx.beginPath(); for (var x = 80; x < w - 40; x += 20){ var y = 300 + Math.sin(x / 90 + k) * 120 + k * 60; if (x === 80) ctx.moveTo(x, y); else ctx.lineTo(x, y); } ctx.stroke(); });
  ctx.fillStyle = '#000'; ctx.font = '14px sans-serif'; for (var j = 0; j < 12; j++) ctx.fillText('' + (j * 10), 60 + j * 75, h - 60);
}, 'png');

// —— Not documents: must be left alone. ——
add('dark_code', false, false, 1200, 800, function(ctx, w, h){ fill(ctx, w, h, '#1e1e1e'); paintText(ctx, w, h, {color: '#d4d4d4', font: '15px monospace', lh: 20}); ctx.fillStyle = '#569cd6'; ctx.fillRect(40, 40, 300, 200); }, 'png');
add('bar_chart', false, false, 1000, 600, function(ctx, w, h){
  fill(ctx, w, h, '#fff'); var cols = ['#e53935', '#1e88e5', '#43a047', '#fb8c00'];
  for (var i = 0; i < 4; i++){ ctx.fillStyle = cols[i]; var bh = 200 + i * 80; ctx.fillRect(120 + i * 210, h - 80 - bh, 150, bh); }
  ctx.strokeStyle = '#000'; ctx.beginPath(); ctx.moveTo(80, 40); ctx.lineTo(80, h - 80); ctx.lineTo(w - 40, h - 80); ctx.stroke();
  ctx.fillStyle = '#000'; ctx.font = '16px sans-serif'; for (var j = 0; j < 4; j++) ctx.fillText('Q' + (j + 1), 180 + j * 210, h - 60);
}, 'png');
add('letterboxed', false, false, 1200, 800, function(ctx, w, h){ fill(ctx, w, h, '#000'); ctx.fillStyle = '#fff'; ctx.fillRect(144, 96, w - 288, h - 192); paintText(ctx, w, h, {x0: 170, y0: 120, yMax: h - 120, wrapW: w - 340}); }, 'png');
add('frame_two_sides', false, false, 1200, 800, function(ctx, w, h){ fill(ctx, w, h, '#fff'); fill(ctx, 260, h, '#222'); ctx.fillStyle = '#222'; ctx.fillRect(0, 0, w, 100); paintText(ctx, w, h, {x0: 300, y0: 140}); }, 'png');
add('web_hero_photo', false, false, 1200, 800, function(ctx, w, h){
  fill(ctx, w, h, '#fff'); var g = ctx.createLinearGradient(0, 0, w, 320); g.addColorStop(0, 'rgb(60,90,140)'); g.addColorStop(0.5, 'rgb(220,180,120)'); g.addColorStop(1, 'rgb(90,140,90)'); ctx.fillStyle = g; ctx.fillRect(0, 0, w, 320); noise(ctx, w, 320, 12);
  paintText(ctx, w, h, {y0: 360});
}, 'jpeg', 0.85);
add('bg_199_below', false, false, 1200, 800, function(ctx, w, h){ fill(ctx, w, h, 'rgb(199,199,199)'); paintText(ctx, w, h); }, 'png');
add('tiny_icon', false, false, 24, 24, function(ctx, w, h){ fill(ctx, w, h, '#fff'); ctx.fillStyle = '#000'; ctx.fillRect(6, 6, 12, 12); }, 'png');
add('blank_white', false, false, 800, 600, function(ctx, w, h){ fill(ctx, w, h, '#fff'); }, 'png');
// Colour graphics the design leaves as drawn: their verdict is a product call.
add('slack_like', null, false, 1200, 800, function(ctx, w, h){ fill(ctx, w, h, '#fff'); fill(ctx, 264, h, '#3f0e40'); paintText(ctx, w, h, {x0: 300}); ctx.fillStyle = '#ddd'; ctx.font = '15px sans-serif'; for (var i = 0; i < 12; i++) ctx.fillText('# channel-' + i, 20, 60 + i * 30); }, 'png');
add('imessage', null, false, 1080, 1800, function(ctx, w, h){
  fill(ctx, w, h, '#fff');
  for (var i = 0; i < 9; i++){ var mine = i % 2 === 0, bw = 500 + (i % 3) * 120, x = mine ? w - 60 - bw : 60; ctx.fillStyle = mine ? '#0a84ff' : '#e9e9eb'; ctx.beginPath(); ctx.roundRect(x, 80 + i * 190, bw, 130, 30); ctx.fill();
    ctx.fillStyle = mine ? '#fff' : '#000'; ctx.font = '32px sans-serif'; ctx.textBaseline = 'top'; ctx.fillText('Message number ' + (i + 1) + ' in the thread', x + 30, 120 + i * 190); }
}, 'png');

// —— Transparency. Light artwork on alpha was drawn for a dark page already;
// over white it is a sparse document with a dark accent, and only its alpha
// says otherwise. The same marks in black are the case the composite rescues,
// and a window capture with a shadow margin is a screenshot like any other. ——
add('light_logo_alpha', false, false, 1000, 600, function(ctx, w, h){
  ctx.fillStyle = '#f4f4f4'; ctx.font = 'bold 120px sans-serif'; ctx.textBaseline = 'top'; ctx.fillText('KARAREAD', 60, 200);
  ctx.fillStyle = '#e0e0e0'; ctx.fillRect(60, 360, 880, 12);
  ctx.fillStyle = '#111'; ctx.beginPath(); ctx.arc(880, 120, 34, 0, TAU); ctx.fill();
}, 'png');
add('dark_logo_alpha', true, false, 1000, 600, function(ctx, w, h){
  ctx.fillStyle = '#111'; ctx.font = 'bold 120px sans-serif'; ctx.textBaseline = 'top'; ctx.fillText('KARAREAD', 60, 200);
  ctx.fillStyle = '#333'; ctx.fillRect(60, 360, 880, 12);
}, 'png');
add('mac_window_alpha', true, false, 1320, 920, function(ctx, w, h){
  ctx.shadowColor = 'rgba(0,0,0,0.55)'; ctx.shadowBlur = 50; ctx.shadowOffsetY = 25;
  ctx.fillStyle = '#fff'; ctx.fillRect(60, 40, 1200, 800);
  ctx.shadowColor = 'transparent'; ctx.shadowBlur = 0; ctx.shadowOffsetY = 0;
  paintText(ctx, w, h, {x0: 100, y0: 90, yMax: 800, wrapW: 1120});
}, 'png');

// —— Known limitations: flat, bright, near-grey photographs with a few dark
// objects are, histogram for histogram, black marks on paper. ——
add('snow_scene', false, true, 1200, 800, function(ctx, w, h){
  var g = ctx.createLinearGradient(0, 0, 0, h * 0.4); g.addColorStop(0, 'rgb(180,198,222)'); g.addColorStop(1, 'rgb(228,234,242)'); ctx.fillStyle = g; ctx.fillRect(0, 0, w, h * 0.4);
  ctx.fillStyle = 'rgb(240,242,246)'; ctx.fillRect(0, h * 0.4, w, h * 0.6);
  var g2 = ctx.createLinearGradient(0, h * 0.4, 0, h); g2.addColorStop(0, 'rgba(120,140,170,0.25)'); g2.addColorStop(1, 'rgba(120,140,170,0)'); ctx.fillStyle = g2; ctx.fillRect(0, h * 0.4, w, h * 0.6);
  ctx.fillStyle = 'rgb(40,50,35)'; for (var i = 0; i < 40; i++){ ctx.beginPath(); ctx.arc(700 + rand() * 300, 250 + rand() * 250, 12 + rand() * 30, 0, TAU); ctx.fill(); }
  noise(ctx, w, h, 9);
}, 'jpeg', 0.85);
add('grey_product_on_white', false, true, 1000, 1000, function(ctx, w, h){
  fill(ctx, w, h, '#fff'); var g = ctx.createRadialGradient(420, 420, 20, 500, 500, 300); g.addColorStop(0, 'rgb(230,235,240)'); g.addColorStop(0.6, 'rgb(120,130,150)'); g.addColorStop(1, 'rgb(40,45,60)');
  ctx.fillStyle = g; ctx.beginPath(); ctx.arc(500, 500, 300, 0, TAU); ctx.fill(); noise(ctx, w, h, 3);
}, 'jpeg', 0.85);
add('bright_muted_photo', false, true, 1200, 800, function(ctx, w, h){
  fill(ctx, w, h, 'rgb(228,222,210)');
  for (var i = 0; i < 25; i++){ var x = rand() * w, y = rand() * h, r = 150 + rand() * 250, g = ctx.createRadialGradient(x, y, 0, x, y, r);
    var dr = Math.round(rand() * 60 - 30), dg = Math.round(rand() * 60 - 30), db = Math.round(rand() * 60 - 30);
    g.addColorStop(0, 'rgba(' + (228 + dr) + ',' + (222 + dg) + ',' + (210 + db) + ',0.6)'); g.addColorStop(1, 'rgba(228,222,210,0)'); ctx.fillStyle = g; ctx.fillRect(0, 0, w, h); }
  ctx.fillStyle = 'rgb(50,40,35)'; for (var j = 0; j < 12; j++){ ctx.beginPath(); ctx.arc(rand() * w, rand() * h, 20 + rand() * 35, 0, TAU); ctx.fill(); }
  noise(ctx, w, h, 6);
}, 'jpeg', 0.85);

var art = document.getElementById('art');
cases.forEach(function(c){
  var fig = document.createElement('figure'), img = new Image(), cap = document.createElement('figcaption');
  img.setAttribute('data-name', c.name); img.setAttribute('data-expect', String(c.expect)); if (c.known) img.setAttribute('data-known', '1');
  img.src = c.url; cap.textContent = c.name; fig.appendChild(img); fig.appendChild(cap); art.appendChild(fig);
});
</script>
EOF
printf '%s\n' "$SAMPLER"
cat <<'EOF'
<script>
function report(){
  var lines = [], imgs = document.querySelectorAll('.kr-article img'), bad = 0;
  for (var i = 0; i < imgs.length; i++){
    var im = imgs[i], s = im.krStats, got = im.classList.contains('kr-bright'), exp = im.getAttribute('data-expect');
    var mark = exp === 'null' ? '  ?' : String(got) === exp ? ' ok' : im.getAttribute('data-known') ? ' kn' : 'XXX';
    if (mark === 'XXX') bad++;
    var stat = s ? 'light=' + s.light.toFixed(3) + ' dark=' + s.dark.toFixed(3) + ' mid=' + s.mid.toFixed(3) + ' peak=' + s.peak.toFixed(3) + '@' + s.peakLevel +
      ' sat=' + s.sat.toFixed(3) + ' vivid=' + s.vivid.toFixed(3) + ' colors=' + s.colors.toFixed(3) + ' border=' + s.border.toFixed(3) + ' n=' + s.samples +
      (s.clear ? ' clear=' + s.clear.toFixed(2) + '/' + s.opaqueLight.toFixed(2) : '') +
      (s.fails.length ? '  FAIL:' + s.fails.join(',') : '') : '(too small to sample)';
    lines.push(mark + ' ' + im.getAttribute('data-name').padEnd(22) + ' expect=' + exp.padEnd(5) + ' got=' + String(got).padEnd(5) + ' ' + stat);
    var cap = im.parentNode.querySelector('figcaption');
    if (cap) cap.textContent = im.getAttribute('data-name') + ' — ' + (got ? 'inverted' : 'left alone') + (mark === 'XXX' ? '  (WRONG)' : '');
  }
  if (errors.length) lines.push('FAILED: uncaught error: ' + errors.join('; '));
  else if (!imgs.length) lines.push('FAILED: no images under .kr-article — the case list never ran');
  else lines.push(bad ? 'FAILED: ' + bad + ' wrong verdict(s)' : 'OK: no wrong verdicts');
  var pre = document.createElement('pre'); pre.id = 'report'; pre.textContent = lines.join('\n'); document.body.appendChild(pre);
}
window.addEventListener('load', function(){ setTimeout(report, 3000); });
</script>
</body></html>
EOF
} > "$HTML"

# The judge is a hand-written mirror of shouldInvert, so a constant the Kotlin
# reads that the mirror never tests means the mirror has drifted.
while read -r c; do
  grep -qE "K\.$c([^A-Z_]|$)" "$HTML" || { echo "!! shouldInvert reads $c but the lab's judge never tests it" >&2; exit 1; }
done < <(awk '/fun shouldInvert/,/^    }/' "$TONE" | grep -oE '[A-Z][A-Z_]{2,}' | sort -u)

[[ -n "$KEEP_HTML" ]] && echo "==> Page written to $HTML"

CHROME="${CHROME:-}"
if [[ -z "$CHROME" ]]; then
  for c in chromium chromium-browser google-chrome google-chrome-stable chrome \
           /opt/pw-browsers/chromium-*/chrome-linux/chrome \
           "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"; do
    if command -v "$c" >/dev/null 2>&1; then CHROME="$c"; break; fi
  done
fi
if [[ -z "$CHROME" ]]; then
  echo "!! No Chromium found; set CHROME=/path/to/chrome" >&2
  [[ -n "$KEEP_HTML" ]] && echo "-- The page is still usable: open $HTML in a browser." >&2
  exit 1
fi

echo "==> Sampling in $CHROME"
# --no-sandbox is fine only because the page is local — file:// with data: URLs
# it generated itself. Revisit before pointing the sampler at remote images.
# --virtual-time-budget lets the page's timers and image decodes settle before
# the DOM is dumped; the report is the <pre> the page appends at the end.
# A profile of its own: the new headless mode otherwise opens the default one,
# and hands the page to a desktop Chrome already running on it, which then
# dumps nothing.
DOM="$("$CHROME" --headless=new --no-sandbox --disable-gpu --disable-dev-shm-usage --hide-scrollbars --user-data-dir="$TMP/chrome-profile" \
  --virtual-time-budget=30000 --dump-dom "file://$HTML" 2>"$TMP/chrome.err" || true)"
REPORT="$(printf '%s\n' "$DOM" | sed -n '/<pre id="report">/,/<\/pre>/p' | sed -e 's/.*<pre id="report">//' -e 's/<\/pre>.*//' \
  -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&quot;/"/g' -e 's/&amp;/\&/g')"
if [[ -z "$REPORT" ]]; then
  echo "!! No report in the page output; is $CHROME a Chromium?" >&2
  [[ -s "$TMP/chrome.err" ]] && { echo "-- what it said:" >&2; head -n 15 "$TMP/chrome.err" >&2; }
  exit 1
fi
printf '%s\n' "$REPORT"
[[ "$REPORT" == *"OK: no wrong verdicts"* ]]
