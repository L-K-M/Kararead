package ch.lkmc.kararead.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleSpeakerTest {

    @Test
    fun `chunkText returns empty for blank input`() {
        assertTrue(ArticleSpeaker.chunkText(null).isEmpty())
        assertTrue(ArticleSpeaker.chunkText("   ").isEmpty())
    }

    @Test
    fun `chunkText splits on sentence boundaries`() {
        val chunks = ArticleSpeaker.chunkText("Hello world. How are you? I am fine!")
        assertEquals(listOf("Hello world.", "How are you?", "I am fine!"), chunks)
    }

    @Test
    fun `chunkText splits on blank lines and trims`() {
        val chunks = ArticleSpeaker.chunkText("First para\n\n  Second para  ")
        assertEquals(listOf("First para", "Second para"), chunks)
    }

    @Test
    fun `chunkText keeps a single sentence whole`() {
        assertEquals(listOf("Just one sentence"), ArticleSpeaker.chunkText("Just one sentence"))
    }

    @Test
    fun `chunkText splits CJK sentences without trailing spaces`() {
        // 。！？ are not followed by whitespace, which the old splitter required —
        // a whole Chinese/Japanese article became one giant utterance that
        // exceeded the engine's input limit and hung narration silently.
        val chunks = ArticleSpeaker.chunkText("这是第一句。这是第二句！这是第三句？")
        assertEquals(listOf("这是第一句。", "这是第二句！", "这是第三句？"), chunks)
    }

    @Test
    fun `chunkText hard-wraps unpunctuated runs below the engine limit`() {
        val words = (1..600).joinToString(" ") { "word$it" } // no sentence marks
        val chunks = ArticleSpeaker.chunkText(words)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= ArticleSpeaker.MAX_CHUNK_CHARS })
        // Nothing lost: the pieces reassemble to the original text.
        assertEquals(words, chunks.joinToString(" "))
    }

    @Test
    fun `chunkText hard-wraps even without any break characters`() {
        val solid = "口".repeat(2500) // no whitespace, no punctuation at all
        val chunks = ArticleSpeaker.chunkText(solid)
        assertTrue(chunks.all { it.length <= ArticleSpeaker.MAX_CHUNK_CHARS })
        assertEquals(2500, chunks.sumOf { it.length })
    }
}
