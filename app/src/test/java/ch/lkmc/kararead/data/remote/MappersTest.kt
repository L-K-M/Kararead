package ch.lkmc.kararead.data.remote

import ch.lkmc.kararead.data.model.ContentType
import ch.lkmc.kararead.data.remote.dto.BookmarkDto
import ch.lkmc.kararead.data.remote.dto.ContentDto
import ch.lkmc.kararead.data.remote.dto.HighlightDto
import ch.lkmc.kararead.data.remote.dto.TagRefDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappersTest {

    private val resolver: (String) -> String? = { "https://srv/api/v1/assets/$it" }

    @Test
    fun `maps a link bookmark to domain and resolves image asset`() {
        val dto = BookmarkDto(
            id = "1",
            createdAt = "2024-01-01T00:00:00Z",
            archived = false,
            favourited = true,
            tags = listOf(TagRefDto("t", "news", "human")),
            content = ContentDto.Link(
                url = "https://www.example.com/article",
                title = "Hello",
                author = "Ann",
                publisher = "Example",
                htmlContent = "<p>Some words here for reading time.</p>",
                imageAssetId = "img1",
            ),
        )

        val bm = dto.toDomain(resolver)
        assertEquals("Hello", bm.displayTitle)
        assertEquals("Ann", bm.author)
        assertEquals("Example", bm.siteName)
        assertEquals("https://srv/api/v1/assets/img1", bm.imageUrl)
        assertEquals(ContentType.LINK, bm.contentType)
        assertTrue(bm.favourited)
        assertEquals(listOf("news"), bm.tags)
        assertEquals(1704067200000L, bm.createdAt)
    }

    @Test
    fun `falls back to remote image url when no asset id`() {
        val dto = BookmarkDto(
            id = "2",
            archived = false,
            favourited = false,
            content = ContentDto.Link(url = "https://x.test", imageUrl = "https://cdn/x.png"),
        )
        assertEquals("https://cdn/x.png", dto.toDomain(resolver).imageUrl)
    }

    @Test
    fun `displayTitle falls back to host when title blank`() {
        val dto = BookmarkDto(
            id = "3",
            archived = false,
            favourited = false,
            content = ContentDto.Link(url = "https://www.bbc.com/news/article"),
        )
        assertEquals("bbc.com", dto.toDomain(resolver).displayTitle)
    }

    @Test
    fun `cache round-trip preserves read and favourite state`() {
        val dto = BookmarkDto(
            id = "5",
            archived = true,
            favourited = true,
            content = ContentDto.Link(url = "https://x.test", htmlContent = "<p>Body</p>"),
        )
        val article = dto.toReaderArticle(resolver)
        // Reading back from the cache must not lose archived/favourited.
        val restored = article.toCacheEntity(now = 0L).toReaderArticle()
        assertTrue(restored.bookmark.archived)
        assertTrue(restored.bookmark.favourited)
    }

    @Test
    fun `maps a highlight dto to domain`() {
        val hl = HighlightDto(
            id = "h1", bookmarkId = "b1", startOffset = 10, endOffset = 25,
            color = "yellow", text = "a quote", note = null,
        ).toDomain()
        assertEquals("h1", hl.id)
        assertEquals(10, hl.startOffset)
        assertEquals(25, hl.endOffset)
        assertEquals("a quote", hl.text)
    }

    @Test
    fun `toReaderArticle exposes html and plain text`() {
        val dto = BookmarkDto(
            id = "4",
            archived = false,
            favourited = false,
            content = ContentDto.Link(
                url = "https://x.test",
                htmlContent = "<p>Hello <b>reader</b></p>",
            ),
        )
        val article = dto.toReaderArticle(resolver)
        assertEquals("<p>Hello <b>reader</b></p>", article.htmlContent)
        assertEquals("Hello reader", article.textContent)
    }
}
