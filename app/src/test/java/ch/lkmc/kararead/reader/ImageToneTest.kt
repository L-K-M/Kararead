package ch.lkmc.kararead.reader

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
        samples: Int = 16384,
    ) = ImageToneStats(
        lightFraction = lightFraction,
        darkFraction = darkFraction,
        meanSaturation = meanSaturation,
        vividFraction = vividFraction,
        borderLightFraction = borderLightFraction,
        peakFraction = peakFraction,
        peakLevel = peakLevel,
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
        assertTrue(ImageTone.shouldInvert(screenshot(lightFraction = 0.94, darkFraction = 0.05)))
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
        assertFalse(ImageTone.shouldInvert(screenshot(lightFraction = 0.999, darkFraction = 0.0)))
    }

    @Test
    fun `a colour graphic is left as its designer drew it`() {
        // A bright, flat, high-contrast infographic passes every structural
        // test; saturation is the only thing standing between it and a ruinous
        // inversion.
        assertFalse(ImageTone.shouldInvert(screenshot(meanSaturation = 0.30, vividFraction = 0.35)))
    }

    @Test
    fun `a bright pane inside a dark frame is left alone`() {
        // Inverting this would turn the frame into a glaring white border —
        // exactly the thing a dark theme is meant to avoid.
        assertFalse(ImageTone.shouldInvert(screenshot(borderLightFraction = 0.05)))
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
