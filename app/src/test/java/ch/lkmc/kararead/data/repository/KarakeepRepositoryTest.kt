package ch.lkmc.kararead.data.repository

import ch.lkmc.kararead.data.local.CachedArticleDao
import ch.lkmc.kararead.data.local.CachedArticleEntity
import ch.lkmc.kararead.data.local.PendingOpDao
import ch.lkmc.kararead.data.local.PendingOpEntity
import ch.lkmc.kararead.data.local.ReadingProgressDao
import ch.lkmc.kararead.data.local.ReadingStatsDao
import ch.lkmc.kararead.data.remote.ApiProvider
import ch.lkmc.kararead.data.remote.KarakeepApi
import ch.lkmc.kararead.data.remote.dto.BookmarkDto
import ch.lkmc.kararead.data.remote.dto.ContentDto
import ch.lkmc.kararead.data.remote.dto.UpdateBookmarkRequest
import ch.lkmc.kararead.reader.AssetLoader
import ch.lkmc.kararead.work.PendingOpSync
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class KarakeepRepositoryTest {

    private val api = mockk<KarakeepApi>(relaxed = true)
    private val apiProvider = mockk<ApiProvider>(relaxed = true)
    private val progressDao = mockk<ReadingProgressDao>(relaxed = true)
    private val cacheDao = mockk<CachedArticleDao>(relaxed = true)
    private val statsDao = mockk<ReadingStatsDao>(relaxed = true)
    private val pendingOpDao = mockk<PendingOpDao>(relaxed = true)
    private val pendingOpSync = mockk<PendingOpSync>(relaxed = true)
    private val assetLoader = mockk<AssetLoader>(relaxed = true)

    private lateinit var repo: KarakeepRepository

    @Before
    fun setUp() {
        every { apiProvider.api() } returns api
        every { apiProvider.assetUrl(any()) } returns null
        repo = KarakeepRepository(
            apiProvider, progressDao, cacheDao, statsDao, pendingOpDao, pendingOpSync, assetLoader,
        )
    }

    @Test
    fun `archiving uncaches the article`() = runTest {
        repo.setArchived("abc", archived = true)
        coVerify { cacheDao.delete("abc") }
    }

    @Test
    fun `un-archiving keeps the cached copy`() = runTest {
        repo.setArchived("abc", archived = false)
        coVerify(exactly = 0) { cacheDao.delete(any()) }
    }

    @Test
    fun `archiving offline queues an op instead of uncaching`() = runTest {
        coEvery { api.updateBookmark(any(), any()) } throws RuntimeException("offline")

        repo.setArchived("abc", archived = true)

        // The server never confirmed, so the cached copy must stay (to read offline)
        // and the change is parked in the outbox for replay.
        coVerify(exactly = 0) { cacheDao.delete("abc") }
        coVerify { pendingOpDao.upsert(match { it.bookmarkId == "abc" && it.value }) }
        coVerify { pendingOpSync.schedule() }
    }

    @Test
    fun `flush replays a queued op and clears it on success`() = runTest {
        val op = PendingOpEntity(
            id = 1,
            bookmarkId = "abc",
            type = PendingOpEntity.TYPE_FAVOURITED,
            value = true,
            createdAt = 0,
        )
        coEvery { pendingOpDao.all() } returns listOf(op)

        val allCleared = repo.flushPendingOps()

        assert(allCleared)
        coVerify { api.updateBookmark("abc", UpdateBookmarkRequest(favourited = true)) }
        coVerify { pendingOpDao.delete(1) }
    }

    @Test
    fun `flush keeps a failing op and bumps its attempts`() = runTest {
        val op = PendingOpEntity(
            id = 1,
            bookmarkId = "abc",
            type = PendingOpEntity.TYPE_ARCHIVED,
            value = true,
            createdAt = 0,
            attempts = 0,
        )
        coEvery { pendingOpDao.all() } returns listOf(op)
        coEvery { api.updateBookmark(any(), any()) } throws RuntimeException("still offline")

        val allCleared = repo.flushPendingOps()

        assert(!allCleared)
        coVerify { pendingOpDao.setAttempts(1, 1) }
        coVerify(exactly = 0) { pendingOpDao.delete(1) }
    }

    // --- getArticle caching ---

    private fun cachedEntity(id: String, html: String?) = CachedArticleEntity(
        bookmarkId = id, title = "T", url = null, siteName = null, author = null,
        excerpt = null, imageUrl = null, faviconUrl = null, html = html, text = null,
        createdAt = 0L, datePublished = null, readingTimeMinutes = null, cachedAt = 0L,
    )

    private fun linkDto(id: String, html: String?) = BookmarkDto(
        id = id,
        content = ContentDto.Link(url = "https://x.test/a", htmlContent = html),
    )

    @Test
    fun `a cache hit serves the copy and refreshes its age`() = runTest {
        coEvery { cacheDao.get("abc") } returns cachedEntity("abc", "<p>body</p>")

        val article = repo.getArticle("abc")

        assert(article.htmlContent == "<p>body</p>")
        // Reading counts as use, so the 30-day cleanup evicts by last-opened.
        coVerify { cacheDao.touch("abc", any()) }
        coVerify(exactly = 0) { api.getBookmark(any(), any()) }
    }

    @Test
    fun `a body-less cached row is treated as a miss so it can heal`() = runTest {
        // Cached while the server was still crawling: html is null.
        coEvery { cacheDao.get("abc") } returns cachedEntity("abc", html = null)
        coEvery { api.getBookmark("abc", includeContent = true) } returns
            linkDto("abc", "<p>ready now</p>")

        val article = repo.getArticle("abc")

        assert(article.htmlContent == "<p>ready now</p>")
        coVerify { cacheDao.upsert(match { it.html == "<p>ready now</p>" }) }
    }

    @Test
    fun `a content-less fetch is not written to the cache`() = runTest {
        coEvery { cacheDao.get("abc") } returns null
        coEvery { api.getBookmark("abc", includeContent = true) } returns
            linkDto("abc", html = null)

        repo.getArticle("abc")

        // Caching the empty body would poison the cache-first path forever.
        coVerify(exactly = 0) { cacheDao.upsert(any()) }
    }
}
