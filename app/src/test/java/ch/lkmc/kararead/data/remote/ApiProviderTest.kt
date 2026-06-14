package ch.lkmc.kararead.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiProviderNormalizeTest {

    @Test
    fun `adds https scheme and api suffix`() {
        assertEquals(
            "https://bookmarks.example.com/api/v1/",
            ApiProvider.normalizeBaseUrl("bookmarks.example.com"),
        )
    }

    @Test
    fun `keeps explicit http scheme`() {
        assertEquals(
            "http://10.0.0.5:3000/api/v1/",
            ApiProvider.normalizeBaseUrl("http://10.0.0.5:3000"),
        )
    }

    @Test
    fun `strips trailing slashes`() {
        assertEquals(
            "https://x.test/api/v1/",
            ApiProvider.normalizeBaseUrl("https://x.test///"),
        )
    }

    @Test
    fun `tolerates a pasted api suffix`() {
        assertEquals(
            "https://x.test/api/v1/",
            ApiProvider.normalizeBaseUrl("https://x.test/api/v1"),
        )
        assertEquals(
            "https://x.test/api/v1/",
            ApiProvider.normalizeBaseUrl("https://x.test/api"),
        )
    }

    @Test
    fun `trims whitespace`() {
        assertEquals(
            "https://x.test/api/v1/",
            ApiProvider.normalizeBaseUrl("  https://x.test  "),
        )
    }
}
