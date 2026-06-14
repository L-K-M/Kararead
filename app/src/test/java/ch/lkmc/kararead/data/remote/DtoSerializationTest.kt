package ch.lkmc.kararead.data.remote

import ch.lkmc.kararead.data.remote.dto.BookmarkDto
import ch.lkmc.kararead.data.remote.dto.ContentDto
import ch.lkmc.kararead.data.remote.dto.PaginatedBookmarksDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtoSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    @Test
    fun `deserializes a link bookmark with content union`() {
        val payload = """
        {
          "bookmarks": [
            {
              "id": "bm1",
              "createdAt": "2024-01-01T00:00:00Z",
              "archived": false,
              "favourited": true,
              "tags": [{"id": "t1", "name": "tech", "attachedBy": "ai"}],
              "content": {
                "type": "link",
                "url": "https://example.com/post",
                "title": "A Post",
                "author": "Jane",
                "publisher": "Example",
                "htmlContent": "<p>Body</p>",
                "imageAssetId": "asset-9",
                "unexpectedField": 42
              },
              "assets": []
            }
          ],
          "nextCursor": "next-1"
        }
        """.trimIndent()

        val page = json.decodeFromString<PaginatedBookmarksDto>(payload)
        assertEquals("next-1", page.nextCursor)
        assertEquals(1, page.bookmarks.size)

        val bm = page.bookmarks.first()
        assertEquals("bm1", bm.id)
        assertTrue(bm.favourited)
        assertEquals("tech", bm.tags.first().name)

        val content = bm.content
        assertTrue(content is ContentDto.Link)
        content as ContentDto.Link
        assertEquals("https://example.com/post", content.url)
        assertEquals("<p>Body</p>", content.htmlContent)
        assertEquals("asset-9", content.imageAssetId)
    }

    @Test
    fun `deserializes a text bookmark`() {
        val payload = """
        { "id": "bm2", "archived": true, "favourited": false,
          "content": { "type": "text", "text": "a note" } }
        """.trimIndent()
        val bm = json.decodeFromString<BookmarkDto>(payload)
        assertTrue(bm.content is ContentDto.Text)
        assertEquals("a note", (bm.content as ContentDto.Text).text)
    }

    @Test
    fun `tolerates unknown content type via union fallback`() {
        val payload = """
        { "id": "bm3", "archived": false, "favourited": false,
          "content": { "type": "unknown" } }
        """.trimIndent()
        val bm = json.decodeFromString<BookmarkDto>(payload)
        assertTrue(bm.content is ContentDto.Unknown)
    }

    @Test
    fun `defaults missing optional fields`() {
        val payload = """{ "id": "bm4", "archived": false, "favourited": false }"""
        val bm = json.decodeFromString<BookmarkDto>(payload)
        assertNull(bm.content)
        assertTrue(bm.tags.isEmpty())
        assertTrue(bm.assets.isEmpty())
    }
}
