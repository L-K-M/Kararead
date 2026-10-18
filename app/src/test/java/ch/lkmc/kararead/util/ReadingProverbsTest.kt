package ch.lkmc.kararead.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReadingProverbsTest {

    @Test
    fun `every proverb is short and non-blank`() {
        assertTrue(ReadingProverbs.all.isNotEmpty())
        ReadingProverbs.all.forEach { line ->
            assertTrue("blank proverb", line.isNotBlank())
            // A crowded strip under the arrow: keep them to at most three lines.
            assertTrue("too many lines: $line", line.count { it == '\n' } <= 2)
        }
    }

    @Test
    fun `pick always returns a member of the well`() {
        repeat(50) { i ->
            val picked = ReadingProverbs.pick(Random(i.toLong()))
            assertTrue(picked in ReadingProverbs.all)
        }
    }

    @Test
    fun `pickOccasional yields a member or null, never junk`() {
        repeat(200) { i ->
            val picked = ReadingProverbs.pickOccasional(Random(i.toLong()))
            assertTrue(picked == null || picked in ReadingProverbs.all)
        }
    }

    @Test
    fun `pickOccasional stays rare over many pulls`() {
        // With ODDS = 4, well under half of pulls should surface a proverb.
        val hits = (0 until 1000).count {
            ReadingProverbs.pickOccasional(Random(it.toLong())) != null
        }
        assertTrue("proverbs too frequent: $hits/1000", hits < 400)
    }

    @Test
    fun `a zero roll shows a proverb, a non-zero roll stays quiet`() {
        // nextInt(ODDS) == 0 is the trigger; pin both branches with fixed seeds.
        val alwaysZero = object : Random() {
            override fun nextBits(bitCount: Int) = 0
        }
        assertEquals(ReadingProverbs.all.first(), ReadingProverbs.pickOccasional(alwaysZero))

        val neverZero = object : Random() {
            var calls = 0
            override fun nextBits(bitCount: Int) = if (calls++ == 0) 1 else 0
        }
        assertNull(ReadingProverbs.pickOccasional(neverZero))
    }
}
