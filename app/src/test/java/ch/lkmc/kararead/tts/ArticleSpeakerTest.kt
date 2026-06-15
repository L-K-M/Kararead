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
}
