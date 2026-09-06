package ch.lkmc.kararead.reader

/**
 * What a sample of one article image looks like, measured in the reader's
 * WebView (canvas) and handed to [ImageTone] over the JS bridge.
 *
 * Every fraction lies in 0..1, but they do not share a denominator: light,
 * dark, vivid, peak and clear are over all the sampled pixels,
 * [borderLightFraction] over its edge ring alone, [opaqueLightFraction] over
 * the opaque pixels alone, and [colorBucketFraction] over the colour buckets.
 * The thresholds in [ImageTone] are calibrated per denominator, so re-deriving
 * the sampler as if they were all per-pixel would quietly move two of them.
 *
 * The sampler composites the image over **white** first, so a transparent PNG
 * of dark line art — the case that goes completely invisible on a dark page —
 * reads as ink on paper.
 */
data class ImageToneStats(
    /** Pixels at or above [ImageTone.LIGHT_LEVEL] luminance: the "paper". */
    val lightFraction: Double,
    /** Pixels at or below [ImageTone.DARK_LEVEL] luminance: the "ink". */
    val darkFraction: Double,
    /** Mean HSV saturation (0 = greyscale). */
    val meanSaturation: Double,
    /** Pixels whose saturation exceeds [ImageTone.VIVID_SATURATION]. */
    val vividFraction: Double,
    /** Light pixels along a ring just inside the edge — the margin around the content. */
    val borderLightFraction: Double,
    /**
     * Largest share of pixels falling in any one 16-level luminance band (two
     * adjacent 8-level buckets, so noise around a flat colour reads as one peak).
     */
    val peakFraction: Double,
    /**
     * Where that band sits, as the lower edge of its upper bucket: always a
     * multiple of 8, and for a dominant level L either `floor(L / 8) * 8` or one
     * bucket above it. Not the band's arithmetic centre — the distinction is
     * what [ImageTone.LIGHT_LEVEL]'s boundary argument rests on.
     */
    val peakLevel: Int,
    /**
     * Share of the 4096 quantised colour buckets (4 bits per channel) holding
     * at least 1/2048 of the sample, and never fewer than two pixels. A page of
     * text spends a few dozen; a colour photograph spends hundreds. The floor is
     * for subpixel-antialiased text, which leaves a pixel or two in hundreds
     * more along its glyph edges — enough to read a Windows screenshot as a
     * photograph if every touched bucket counted.
     */
    val colorBucketFraction: Double,
    /** How many pixels were sampled. */
    val samples: Int,
    /**
     * Pixels that are clear (alpha below [ImageTone.CLEAR_ALPHA]), over the
     * sampled pixels. Zero for anything but a PNG with an alpha channel.
     */
    val clearFraction: Double,
    /**
     * Light pixels among the opaque ones (alpha at or above
     * [ImageTone.OPAQUE_ALPHA]), over those opaque pixels — the same as
     * [lightFraction] for an opaque image. Read before the sampler composites
     * the transparency away, it is what tells light artwork on alpha from
     * dark line art on alpha.
     */
    val opaqueLightFraction: Double,
)

/**
 * Decides whether an article image is a *bright document* — a screenshot of
 * text, a diagram, a chart, line art on white — that should be flipped to
 * light-on-dark so it sits in a dark reader page instead of glaring out of it.
 * Photographs, artwork and images that are already dark must be left alone.
 *
 * The judgement is deliberately a pure function of [ImageToneStats] so a JVM
 * unit test can reach it; the WebView side only samples pixels and reports
 * these numbers (see `ReaderHtmlBuilder.imageToneScript`).
 *
 * Three signals do the work, and an image has to satisfy all of them:
 *
 * 1. **Bright** — mostly paper, with enough ink on it to be worth reading
 *    ([lightFraction][ImageToneStats.lightFraction], [darkFraction][ImageToneStats.darkFraction]).
 * 2. **Bimodal** — ink and paper with little in between. A photograph is
 *    continuous tone and fails here even when it is bright overall.
 * 3. **Flat** — one dominant, near-uniform light level
 *    ([peakFraction][ImageToneStats.peakFraction]). A snowy landscape is bright
 *    and lowish-contrast but its luminance is spread across the whole range;
 *    a screenshot's background is a single value.
 *
 * Saturation and the palette size guard the remaining case — a bright, flat,
 * high-contrast *colour* graphic (an infographic, a poster, a muted photograph)
 * that inverting would only disfigure.
 *
 * Alpha guards one more: light artwork on a transparent background, which
 * composites over white into a sparse document but was drawn for a dark page
 * already ([clearFraction][ImageToneStats.clearFraction],
 * [opaqueLightFraction][ImageToneStats.opaqueLightFraction]).
 *
 * These are histogram tests, blind to layout, so a photograph that happens to
 * be *shaped* like a document slips through: a flat, bright, near-grey scene
 * with a few dark objects (fog, snow, a grey gadget on a white backdrop) is,
 * pixel for pixel, black marks on paper. Telling those apart needs spatial
 * structure — text is edges everywhere, a gradient is edges nowhere — and that
 * waits for a corpus to tune it on. The reader's toggle is the remedy meanwhile.
 *
 * Worth noting for anyone who reaches for it: WebView's own algorithmic
 * darkening cannot do this. Blink's force-dark classifier only ever runs on
 * images up to 64x64 CSS px and calls everything larger a photo, and it is
 * switched off entirely for a page that declares its own dark styles — which
 * the reader does, via `color-scheme: dark`.
 */
object ImageTone {

    /**
     * Luminance (0..255) at or above which a pixel counts as paper.
     *
     * Keep it a multiple of 8, the sampler's bucket width. The peak band is
     * reported as `floor(level / 8) * 8` or one bucket above it, so a paper
     * level at or above a multiple-of-8 threshold can never round down past it
     * — which is what lets [shouldInvert] test `peakLevel` and `lightFraction`
     * against the same number without a boundary between them.
     */
    const val LIGHT_LEVEL = 200

    /** Luminance at or below which a pixel counts as ink. */
    const val DARK_LEVEL = 96

    /** HSV saturation above which a pixel counts as vividly coloured. */
    const val VIVID_SATURATION = 0.45

    /**
     * Brightest channel below which a pixel is taken as achromatic.
     *
     * Saturation is `(max - min) / max`, which blows up as `max` approaches
     * zero: an ink pixel of rgb(3, 1, 0) — visually black — reads as *fully*
     * saturated. Ordinary noise in black text therefore piles spurious colour
     * onto exactly the guards that reject colour graphics, and an ink-dense
     * screenshot gets thrown out as one. Subpixel antialiasing is the other
     * source: the dark side of every glyph edge is a strongly tinted near-black,
     * and those alone read as 0.10-0.13 vivid on a plain Windows screenshot (a
     * tenth less at 64 than at 32) — room the vivid limit would rather keep for
     * a coloured toolbar. Below this level there is no hue worth measuring, so
     * there is none.
     */
    const val CHROMA_FLOOR = 64

    /** Bits per channel the colour buckets are quantised to (16 levels each). */
    const val COLOR_BUCKET_BITS = 4

    /** Alpha below which a pixel is clear: not drawn, whatever colour it carries. */
    const val CLEAR_ALPHA = 16

    /** Alpha from which a pixel is opaque, and its colour is the artwork's own. */
    const val OPAQUE_ALPHA = 128

    /** Below this many sampled pixels the fractions are too noisy to trust. */
    const val MIN_SAMPLES = 1024

    // The paper has to dominate, but not to the point of being blank: an image
    // with no ink gains nothing from being flipped, and a light-ink-on-alpha
    // graphic (already made for dark backgrounds) — which the white composite
    // turns into a blank — would be ruined by it. The ink floor is set by how
    // little a *diagram* has: lines and labels on a 1000x700 canvas sample at
    // 0.3-0.5% ink with 2px strokes and 0.9% with hairlines and more labels
    // (measured in Chromium), a tenth of what a page of text carries, and
    // those are the images that vanish on a dark page.
    private const val MIN_LIGHT = 0.55
    private const val MIN_DARK = 0.002
    // Implied by MIN_LIGHT today — the sampler's bins are disjoint, so light and
    // dark can't sum past 1 — and kept as the independent statement of intent,
    // which is what would still hold if MIN_LIGHT ever moved.
    private const val MAX_DARK = 0.45

    // Mid-tones are the tell-tale of a photograph. In a screenshot they only
    // come from antialiasing along glyph edges, which stays well under this.
    private const val MAX_MID = 0.25

    // One flat light level has to account for a good chunk of the image.
    private const val MIN_PEAK = 0.30

    // Colour guards: a screenshot may carry a coloured toolbar or syntax
    // highlighting, but a colour *graphic* should be left as its designer drew it.
    // The limits sit in the gap between the two populations as measured
    // (scripts/image-tone-lab.sh): subpixel-antialiased black-on-white text
    // alone reads 0.11 / 0.13 — every glyph edge is a tinted fringe — while the
    // least colourful graphics that must stay out (a chat with blue bubbles, a
    // dark purple sidebar, a bar chart) read 0.17 / 0.20 and up.
    private const val MAX_MEAN_SATURATION = 0.15
    private const val MAX_VIVID = 0.18

    // Palette size — the feature both shipped classifiers of this kind rank
    // first (Chromium's force-dark decision tree, mod_pagespeed's illustration
    // detector). A screenshot of text spends a few dozen of the 4096 buckets
    // even with syntax highlighting; a colour photograph spends hundreds. 0.10
    // is ~410 buckets: several times what a busy screenshot needs, well under
    // what a photograph reaches. This is what catches the muted photograph that
    // slips past the saturation guards — a *greyscale* one spends as few
    // buckets as a screenshot, which is what MAX_MID and MIN_PEAK are for.
    private const val MAX_COLOR_BUCKETS = 0.10

    // A bright panel inside a dark frame (a letterboxed still, a dark-themed
    // screenshot with a white content pane) would invert into a glaring white
    // border, so the margin has to be bright too. Half, not more: when a shot
    // is cropped tight the ring runs through the text itself, and a dense
    // terminal measures 0.59 there while a frame on two sides measures 0.41.
    private const val MIN_BORDER_LIGHT = 0.50

    // Light artwork on transparency — a wordmark, a diagram drawn for a dark
    // page — composites over white into something the histogram cannot tell
    // from a sparse document with a few dark accents, and inverting it paints
    // its marks in near-black on the dark page: the one outcome worse than
    // glare. Alpha is what tells it from the dark-on-alpha line art the
    // composite exists to rescue. Half the image has to be clear before this
    // applies at all; a screenshot saved with a transparent shadow margin (a
    // macOS window) stays well under that.
    private const val LIGHT_ART_MIN_CLEAR = 0.50
    private const val LIGHT_ART_MIN_OPAQUE_LIGHT = 0.80

    /** True when [stats] describe a bright document worth flipping for a dark page. */
    fun shouldInvert(stats: ImageToneStats): Boolean {
        if (stats.samples < MIN_SAMPLES) return false
        if (stats.clearFraction >= LIGHT_ART_MIN_CLEAR &&
            stats.opaqueLightFraction >= LIGHT_ART_MIN_OPAQUE_LIGHT
        ) return false
        val mid = (1.0 - stats.lightFraction - stats.darkFraction).coerceAtLeast(0.0)
        return stats.lightFraction >= MIN_LIGHT &&
            stats.darkFraction >= MIN_DARK &&
            stats.darkFraction <= MAX_DARK &&
            mid <= MAX_MID &&
            stats.peakFraction >= MIN_PEAK &&
            stats.peakLevel >= LIGHT_LEVEL &&
            stats.meanSaturation <= MAX_MEAN_SATURATION &&
            stats.vividFraction <= MAX_VIVID &&
            stats.colorBucketFraction <= MAX_COLOR_BUCKETS &&
            stats.borderLightFraction >= MIN_BORDER_LIGHT
    }
}
