package ch.lkmc.kararead.util

import kotlin.random.Random

/**
 * A small well of reading proverbs and pocket haiku, surfaced once in a while
 * beneath the pull-to-refresh spinner — a quiet, unhurried reward for the pull
 * rather than yet another spinner (I10 "Pull-to-refresh haiku").
 */
object ReadingProverbs {

    /** Roughly one pull in [ODDS] shows a proverb — a treat, not a fixture. */
    const val ODDS = 4

    /**
     * Short lines only — they share a crowded strip under the arrow. Some are
     * three-line haiku (kept under ~17 syllables), some plain one-line proverbs.
     */
    val all: List<String> = listOf(
        "A quiet page\nis a window left open\nfor the mind to lean out.",
        "Slow down —\nthe finest sentences\nask to be reread.",
        "The unread pile\nis not a debt.\nIt's a garden.",
        "One page a day\nkeeps the restless\ngently ashore.",
        "Refill the well\nbefore you\ndraw from it.",
        "No hurry —\nthe story kept\nwhile you were away.",
        "A book waits\nwith the patience\nof a stone.",
        "Between two lines,\na small door opens.\nStep through.",
        "Morning light,\na cup gone cold,\nstill one more page.",
        "Wisdom travels\nat the speed\nof a turning page.",
        "Not all who wander\nthrough footnotes\nare lost.",
        "The best shelf\nis the one you\nactually read.",
    )

    /**
     * A proverb to show, or `null` most of the time (see [ODDS]). Inject a
     * seeded [random] in tests for determinism.
     */
    fun pickOccasional(random: Random = Random.Default): String? =
        if (random.nextInt(ODDS) == 0) pick(random) else null

    /** Always returns a proverb — for previews and callers that want one. */
    fun pick(random: Random = Random.Default): String = all[random.nextInt(all.size)]
}
