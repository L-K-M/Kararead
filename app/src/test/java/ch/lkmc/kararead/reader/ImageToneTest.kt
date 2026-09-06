package ch.lkmc.kararead.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageToneTest {

    /**
     * A screenshot of black text on white paper: almost everything is the one
     * flat paper level, a few percent is ink, and there is very little in
     * between (only glyph antialiasing).
     */
    private fun screenshot(
        lightFraction: Double = 0.88,
        darkFraction: Double = 0.07,
        meanSaturation: Double = 0.01,
        vividFraction: Double = 0.0,
        borderLightFraction: Double = 0.99,
        peakFraction: Double = 0.85,
        peakLevel: Int = 248,
        colorBucketFraction: Double = 0.01,
        samples: Int = 16384,
    ) = ImageToneStats(
        lightFraction = lightFraction,
        darkFraction = darkFraction,
        meanSaturation = meanSaturation,
        vividFraction = vividFraction,
        borderLightFraction = borderLightFraction,
        peakFraction = peakFraction,
        peakLevel = peakLevel,
        colorBucketFraction = colorBucketFraction,
        samples = samples,
    )

    @Test
    fun `a screenshot of text on white is inverted`() {
        assertTrue(ImageTone.shouldInvert(screenshot()))
    }

    @Test
    fun `syntax highlighting and a coloured toolbar do not disqualify a screenshot`() {
        // A code screenshot: a tenth of it is vividly coloured, but the page
        // underneath is still flat paper.
        assertTrue(
            ImageTone.shouldInvert(
                screenshot(meanSaturation = 0.07, vividFraction = 0.10, darkFraction = 0.12),
            ),
        )
    }

    @Test
    fun `dark line art on a transparent background is inverted`() {
        // The sampler composites over white, so an alpha-backed diagram — the
        // one that disappears entirely on a dark page — arrives as ink on paper.
        // And very little of it: 2px boxes, arrows and labels on a 1000x700
        // canvas measure 0.5% ink in Chromium, a pastel flowchart 0.3%, against
        // the 7-10% of a page of text. The floor has to admit them.
        assertTrue(ImageTone.shouldInvert(screenshot(lightFraction = 0.993, darkFraction = 0.005)))
        assertTrue(ImageTone.shouldInvert(screenshot(lightFraction = 0.996, darkFraction = 0.003)))
    }

    @Test
    fun `a photograph is left alone even when it is bright`() {
        // Continuous tone: most of a photograph sits between ink and paper, and
        // its luminance is spread rather than piled on one level.
        assertFalse(
            ImageTone.shouldInvert(
                screenshot(
                    lightFraction = 0.58, darkFraction = 0.06,
                    peakFraction = 0.12, meanSaturation = 0.09,
                ),
            ),
        )
    }

    @Test
    fun `a bright but smoothly graded image is left alone`() {
        // Snow, fog, an overexposed sky: bright and near-greyscale, but with no
        // single flat level behind it. Only the peak test catches this one.
        assertFalse(ImageTone.shouldInvert(screenshot(peakFraction = 0.20)))
    }

    @Test
    fun `an already dark image is left alone`() {
        assertFalse(
            ImageTone.shouldInvert(
                screenshot(
                    lightFraction = 0.10, darkFraction = 0.80,
                    borderLightFraction = 0.02, peakFraction = 0.60, peakLevel = 24,
                ),
            ),
        )
    }

    @Test
    fun `a blank image has nothing to gain and is left alone`() {
        // Also the shape of light line art on alpha — made for a dark page and
        // composited over white by the sampler — which inverting would erase.
        assertFalse(ImageTone.shouldInvert(screenshot(lightFraction = 0.999, darkFraction = 0.0)))
        // The floor is a few dozen sampled pixels; below it there is no ink to
        // speak of, only a stray shadow or a hairline.
        assertFalse(ImageTone.shouldInvert(screenshot(lightFraction = 0.999, darkFraction = 0.001)))
    }

    @Test
    fun `a muted photograph is caught by the size of its palette`() {
        // Low saturation, flat-looking, bright — it clears every other guard.
        // What gives it away is that it spends hundreds of colours where a page
        // of text spends a few dozen.
        assertFalse(ImageTone.shouldInvert(screenshot(colorBucketFraction = 0.28)))
    }

    @Test
    fun `an ink-dense screenshot is not mistaken for a colour graphic`() {
        // A dense terminal or a bold-text shot is 20-40% ink. Saturation is
        // (max - min) / max, which explodes towards black, so before the
        // sampler's chroma floor ordinary noise in that ink read as ~0.7 mean
        // saturation and pushed both colour guards past their limits — the
        // feature refusing exactly the screenshots it exists for.
        assertTrue(
            ImageTone.shouldInvert(
                screenshot(lightFraction = 0.60, darkFraction = 0.38, meanSaturation = 0.02),
            ),
        )
    }

    @Test
    fun `subpixel-antialiased text is not mistaken for a colour graphic`() {
        // ClearType and its kin tint every glyph edge, so a plain Windows
        // screenshot measures around 0.11 mean saturation and 0.13 vivid with
        // not a coloured pixel by design in it. That has to clear both guards
        // with room for the face and the size of the type.
        assertTrue(ImageTone.shouldInvert(screenshot(meanSaturation = 0.111, vividFraction = 0.130)))
    }

    @Test
    fun `a colour graphic is left as its designer drew it`() {
        // A bright, flat, high-contrast infographic passes every structural
        // test; saturation is the only thing standing between it and a ruinous
        // inversion.
        assertFalse(ImageTone.shouldInvert(screenshot(meanSaturation = 0.30, vividFraction = 0.35)))
    }

    @Test
    fun `either colour guard refuses a graphic on its own`() {
        // The two are alternatives, not a pair: the test above moves both at
        // once, which would still pass if one of them stopped working.
        assertFalse(ImageTone.shouldInvert(screenshot(meanSaturation = 0.30)))
        assertFalse(ImageTone.shouldInvert(screenshot(vividFraction = 0.35)))
    }

    @Test
    fun `a bright pane inside a dark frame is left alone`() {
        // Inverting this would turn the frame into a glaring white border —
        // exactly the thing a dark theme is meant to avoid. A letterbox reads
        // ~0 on the ring; a dark sidebar plus a dark top bar reads 0.41.
        assertFalse(ImageTone.shouldInvert(screenshot(borderLightFraction = 0.05)))
        assertFalse(
            ImageTone.shouldInvert(
                screenshot(lightFraction = 0.59, darkFraction = 0.37, borderLightFraction = 0.41),
            ),
        )
    }

    @Test
    fun `a tightly cropped dense screenshot is not mistaken for a framed one`() {
        // With no margin to speak of, the ring runs through the text, and a
        // dense terminal reads 0.59 light there — the same as its body. That is
        // text, not a frame.
        assertTrue(
            ImageTone.shouldInvert(
                screenshot(lightFraction = 0.72, darkFraction = 0.215, borderLightFraction = 0.59),
            ),
        )
    }

    @Test
    fun `light grey paper still reads as paper at the band boundary`() {
        // The sampler reports the peak as floor(level / 8) * 8, or one bucket
        // above. A #cccccc page (luminance 204) therefore lands on 200 — the
        // threshold itself — and must not be rejected for it. This holds only
        // because LIGHT_LEVEL is a multiple of the 8-level bucket width; move it
        // off one and light-grey screenshots start being skipped.
        assertTrue(ImageTone.shouldInvert(screenshot(peakLevel = ImageTone.LIGHT_LEVEL)))
        assertEquals(0, ImageTone.LIGHT_LEVEL % 8)
    }

    @Test
    fun `a peak at a mid grey level is not paper`() {
        assertFalse(ImageTone.shouldInvert(screenshot(peakLevel = 160)))
    }

    @Test
    fun `too few samples to judge means no inversion`() {
        assertFalse(ImageTone.shouldInvert(screenshot(samples = ImageTone.MIN_SAMPLES - 1)))
        assertTrue(ImageTone.shouldInvert(screenshot(samples = ImageTone.MIN_SAMPLES)))
    }
}
