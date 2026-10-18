package ch.lkmc.kararead.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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

class ApiProviderConfigureTest {

    @Test
    fun `an unparsable server url leaves the provider unconfigured instead of throwing`() {
        val provider = ApiProvider()
        // A space in the host survives normalizeBaseUrl but is not a valid
        // http URL — this used to crash inside Retrofit's baseUrl().
        provider.configure(
            ch.lkmc.kararead.data.model.ConnectionSettings(
                serverUrl = "my server.com",
                apiKey = "ak1_test",
            ),
        )
        assertTrue(!provider.isConfigured())
    }

    @Test
    fun `a valid server url configures normally`() {
        val provider = ApiProvider()
        provider.configure(
            ch.lkmc.kararead.data.model.ConnectionSettings(
                serverUrl = "bookmarks.example.com",
                apiKey = "ak1_test",
            ),
        )
        assertTrue(provider.isConfigured())
    }

    @Test
    fun `an invalid fallback url is dropped but the primary still works`() {
        val provider = ApiProvider()
        provider.configure(
            ch.lkmc.kararead.data.model.ConnectionSettings(
                serverUrl = "bookmarks.example.com",
                apiKey = "ak1_test",
                fallbackUrl = "bad fallback url",
            ),
        )
        assertTrue(provider.isConfigured())
    }
}

class AuthHeaderScopeTest {

    private fun provider() = ApiProvider().apply {
        configure(
            ch.lkmc.kararead.data.model.ConnectionSettings(
                serverUrl = "https://main.example.com",
                apiKey = "ak1_secret",
            ),
        )
    }

    @Test
    fun `attaches the key to the configured origin`() {
        assertEquals(
            "Bearer ak1_secret",
            provider().authHeaderForUrl("https://main.example.com/api/v1/assets/1"),
        )
    }

    @Test
    fun `never attaches the key to cleartext http on the same host`() {
        // Article HTML is attacker-supplied; an http:// image on the server's
        // host used to receive the bearer token over cleartext.
        assertEquals(null, provider().authHeaderForUrl("http://main.example.com/x.png"))
    }

    @Test
    fun `never attaches the key to a different port on the same host`() {
        assertEquals(null, provider().authHeaderForUrl("https://main.example.com:8443/x.png"))
    }

    @Test
    fun `never attaches the key to third-party hosts`() {
        assertEquals(null, provider().authHeaderForUrl("https://evil.example.net/x.png"))
    }
}

class FailoverInterceptorTest {

    private val primary = "https://main.example.com/api/v1/".toHttpUrl()
    private val fallback = "http://intranet.local:3000/api/v1/".toHttpUrl()

    private fun request() = Request.Builder()
        .url("https://main.example.com/api/v1/bookmarks?archived=false")
        .build()

    @Test
    fun `falls over to the secondary host when the primary throws`() {
        var activeBase: String? = null
        val interceptor = FailoverInterceptor(primary, fallback) { activeBase = it }
        val seen = mutableListOf<String>()
        val chain = FakeChain(request()) { req ->
            seen += req.url.toString()
            if (req.url.host == "main.example.com") throw IOException("primary down")
            okhttp3.Response.Builder()
                .request(req).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").build()
        }

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        // Tried primary, then the fallback (host/port swapped, path preserved).
        assertEquals("https://main.example.com/api/v1/bookmarks?archived=false", seen[0])
        assertEquals("http://intranet.local:3000/api/v1/bookmarks?archived=false", seen[1])
        assertEquals("http://intranet.local:3000/api/v1/", activeBase)
    }

    @Test
    fun `sticks with the fallback on the next request`() {
        val interceptor = FailoverInterceptor(primary, fallback) {}
        val firstSeen = mutableListOf<String>()
        interceptor.intercept(
            FakeChain(request()) { req ->
                firstSeen += req.url.host
                if (req.url.host == "main.example.com") throw IOException("down")
                okResponse(req)
            },
        )
        // After one failover the interceptor should hit the fallback first.
        var firstHost: String? = null
        interceptor.intercept(
            FakeChain(request()) { req ->
                if (firstHost == null) firstHost = req.url.host
                okResponse(req)
            },
        )
        assertEquals("intranet.local", firstHost)
    }

    @Test
    fun `does not replay a POST against the fallback`() {
        val interceptor = FailoverInterceptor(primary, fallback) {}
        val attempts = mutableListOf<String>()
        val post = Request.Builder()
            .url("https://main.example.com/api/v1/bookmarks")
            .post(okhttp3.RequestBody.create(null, "{}"))
            .build()
        var threw = false
        try {
            interceptor.intercept(
                FakeChain(post) { req ->
                    attempts += req.url.host
                    throw IOException("response lost")
                },
            )
        } catch (e: IOException) {
            threw = true
        }
        // A create may have succeeded server-side even though the response was
        // lost; replaying it would duplicate the bookmark.
        assertTrue(threw)
        assertEquals(listOf("main.example.com"), attempts)
    }

    @Test
    fun `rethrows the original error when both servers are down`() {
        val interceptor = FailoverInterceptor(primary, fallback) {}
        var threw = false
        try {
            interceptor.intercept(FakeChain(request()) { throw IOException("everything down") })
        } catch (e: IOException) {
            threw = true
        }
        assertTrue(threw)
    }

    private fun okResponse(req: Request) = okhttp3.Response.Builder()
        .request(req).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").build()
}

/** Minimal [okhttp3.Interceptor.Chain] that runs [proceed] against a lambda. */
private class FakeChain(
    private val request: Request,
    private val handler: (Request) -> okhttp3.Response,
) : okhttp3.Interceptor.Chain {
    override fun request(): Request = request
    override fun proceed(request: Request): okhttp3.Response = handler(request)
    override fun connection(): okhttp3.Connection? = null
    override fun call(): okhttp3.Call = throw UnsupportedOperationException()
    override fun connectTimeoutMillis(): Int = 0
    override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    override fun readTimeoutMillis(): Int = 0
    override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    override fun writeTimeoutMillis(): Int = 0
    override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
}
