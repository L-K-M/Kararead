package ch.lkmc.kararead.data.repository

import ch.lkmc.kararead.data.local.CachedArticleDao
import ch.lkmc.kararead.data.local.ReadingProgressDao
import ch.lkmc.kararead.data.local.ReadingStatsDao
import ch.lkmc.kararead.data.remote.ApiProvider
import ch.lkmc.kararead.data.remote.KarakeepApi
import ch.lkmc.kararead.reader.AssetLoader
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
    private val assetLoader = mockk<AssetLoader>(relaxed = true)

    private lateinit var repo: KarakeepRepository

    @Before
    fun setUp() {
        every { apiProvider.api() } returns api
        every { apiProvider.assetUrl(any()) } returns null
        repo = KarakeepRepository(apiProvider, progressDao, cacheDao, statsDao, assetLoader)
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
}
