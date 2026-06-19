package ch.lkmc.kararead.data.repository

import ch.lkmc.kararead.data.local.CachedArticleDao
import ch.lkmc.kararead.data.local.PendingOpDao
import ch.lkmc.kararead.data.local.PendingOpEntity
import ch.lkmc.kararead.data.local.ReadingProgressDao
import ch.lkmc.kararead.data.local.ReadingStatsDao
import ch.lkmc.kararead.data.remote.ApiProvider
import ch.lkmc.kararead.data.remote.KarakeepApi
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
}
