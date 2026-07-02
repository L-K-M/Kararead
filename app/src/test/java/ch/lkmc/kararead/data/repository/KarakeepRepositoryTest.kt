package ch.lkmc.kararead.data.repository

import ch.lkmc.kararead.data.local.CachedArticleDao
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
import okhttp3.ResponseBody.Companion.toResponseBody
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
    fun `flush keeps an op through transport errors without burning attempts`() = runTest {
        // The op must survive an unreachable server indefinitely — a long train
        // ride used to discard queued archives after five failed replays.
        val op = PendingOpEntity(
            id = 1,
            bookmarkId = "abc",
            type = PendingOpEntity.TYPE_ARCHIVED,
            value = true,
            createdAt = 0,
            attempts = 0,
        )
        coEvery { pendingOpDao.all() } returns listOf(op)
        coEvery { api.updateBookmark(any(), any()) } throws java.io.IOException("unreachable")

        val allCleared = repo.flushPendingOps()

        assert(!allCleared)
        coVerify(exactly = 0) { pendingOpDao.setAttempts(any(), any()) }
        coVerify(exactly = 0) { pendingOpDao.delete(1) }
    }

    @Test
    fun `flush drops an op the server definitively rejected`() = runTest {
        val op = PendingOpEntity(
            id = 1,
            bookmarkId = "gone",
            type = PendingOpEntity.TYPE_ARCHIVED,
            value = true,
            createdAt = 0,
        )
        coEvery { pendingOpDao.all() } returns listOf(op)
        coEvery { api.updateBookmark(any(), any()) } throws
            retrofit2.HttpException(retrofit2.Response.error<Any>(404, "".toResponseBody()))

        val allCleared = repo.flushPendingOps()

        // A deleted bookmark can never sync; retrying is pointless.
        assert(allCleared)
        coVerify { pendingOpDao.delete(1) }
    }

    @Test
    fun `flush counts attempts only for server-side errors`() = runTest {
        val op = PendingOpEntity(
            id = 1,
            bookmarkId = "abc",
            type = PendingOpEntity.TYPE_ARCHIVED,
            value = true,
            createdAt = 0,
            attempts = 0,
        )
        coEvery { pendingOpDao.all() } returns listOf(op)
        coEvery { api.updateBookmark(any(), any()) } throws
            retrofit2.HttpException(retrofit2.Response.error<Any>(500, "".toResponseBody()))

        val allCleared = repo.flushPendingOps()

        assert(!allCleared)
        coVerify { pendingOpDao.setAttempts(1, 1) }
    }

    @Test
    fun `a successful online toggle clears its queued outbox op`() = runTest {
        // Queued offline op + later direct online change: the op is stale and
        // must not replay (it would silently revert the newer change).
        repo.setArchived("abc", archived = false)
        coVerify { pendingOpDao.deleteFor("abc", PendingOpEntity.TYPE_ARCHIVED) }

        repo.setFavourited("abc", favourited = true)
        coVerify { pendingOpDao.deleteFor("abc", PendingOpEntity.TYPE_FAVOURITED) }
    }

    @Test
    fun `refreshReadState keeps the value of a still-pending offline op`() = runTest {
        // Server still says unarchived, but the user archived offline and the
        // outbox hasn't flushed yet — the local change must win.
        coEvery { api.getBookmark("abc", includeContent = false) } returns BookmarkDto(
            id = "abc",
            archived = false,
            favourited = false,
            content = ContentDto.Link(url = "https://x.test/a"),
        )
        coEvery { pendingOpDao.getFor("abc", PendingOpEntity.TYPE_ARCHIVED) } returns
            PendingOpEntity(
                id = 7, bookmarkId = "abc",
                type = PendingOpEntity.TYPE_ARCHIVED, value = true, createdAt = 0,
            )
        coEvery { pendingOpDao.getFor("abc", PendingOpEntity.TYPE_FAVOURITED) } returns null

        val result = repo.refreshReadState("abc")

        assert(result != null && result.first)   // archived: pending op wins
        assert(result != null && !result.second) // favourited: server value
    }
}
