package ch.lkmc.kararead.data.repository

import ch.lkmc.kararead.data.local.CachedArticleDao
import ch.lkmc.kararead.data.local.CachedArticleEntity
import ch.lkmc.kararead.data.local.CachedHighlightDao
import ch.lkmc.kararead.data.local.CachedHighlightEntity
import ch.lkmc.kararead.data.local.HighlightOpDao
import ch.lkmc.kararead.data.local.HighlightOpEntity
import ch.lkmc.kararead.data.local.PendingOpDao
import ch.lkmc.kararead.data.local.PendingOpEntity
import ch.lkmc.kararead.data.local.ReadingDayEntity
import ch.lkmc.kararead.data.local.ReadingProgressDao
import ch.lkmc.kararead.data.local.ReadingProgressEntity
import ch.lkmc.kararead.data.local.ReadingStatsDao
import ch.lkmc.kararead.data.remote.ApiProvider
import ch.lkmc.kararead.data.remote.KarakeepApi
import ch.lkmc.kararead.data.remote.dto.BookmarkDto
import ch.lkmc.kararead.data.remote.dto.ContentDto
import ch.lkmc.kararead.data.remote.dto.HighlightDto
import ch.lkmc.kararead.data.remote.dto.ListDto
import ch.lkmc.kararead.data.remote.dto.ListsResponseDto
import ch.lkmc.kararead.data.remote.dto.PaginatedBookmarksDto
import ch.lkmc.kararead.data.remote.dto.PaginatedHighlightsDto
import ch.lkmc.kararead.data.remote.dto.UpdateBookmarkRequest
import ch.lkmc.kararead.reader.AssetLoader
import ch.lkmc.kararead.util.LocalBackup
import ch.lkmc.kararead.util.ProgressEntry
import ch.lkmc.kararead.util.ReadingDayEntry
import ch.lkmc.kararead.work.PendingOpSync
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
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
    private val highlightDao = mockk<CachedHighlightDao>(relaxed = true)
    private val highlightOpDao = mockk<HighlightOpDao>(relaxed = true)

    private lateinit var repo: KarakeepRepository

    @Before
    fun setUp() {
        every { apiProvider.api() } returns api
        every { apiProvider.assetUrl(any()) } returns null
        repo = KarakeepRepository(
            apiProvider, progressDao, cacheDao, statsDao, pendingOpDao, pendingOpSync, assetLoader,
            highlightDao, highlightOpDao,
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

    // --- Lists (H7) ---

    @Test
    fun `getManualLists drops smart lists`() = runTest {
        coEvery { api.getLists() } returns ListsResponseDto(
            listOf(
                ListDto(id = "m1", name = "Recipes", type = "manual"),
                ListDto(id = "s1", name = "Unread longreads", type = "smart"),
                ListDto(id = "m2", name = "To buy", type = "manual"),
            ),
        )

        val manual = repo.getManualLists()

        assert(manual.map { it.id } == listOf("m1", "m2"))
    }

    @Test
    fun `listsContaining reports only lists that hold the bookmark`() = runTest {
        coEvery { api.getListBookmarks("l1", any(), any(), any(), any()) } returns
            PaginatedBookmarksDto(listOf(BookmarkDto(id = "abc"), BookmarkDto(id = "other")))
        coEvery { api.getListBookmarks("l2", any(), any(), any(), any()) } returns
            PaginatedBookmarksDto(listOf(BookmarkDto(id = "other")))

        val containing = repo.listsContaining("abc", listOf("l1", "l2"))

        assert(containing == setOf("l1"))
    }

    @Test
    fun `a failed membership scan is treated as not-a-member`() = runTest {
        coEvery { api.getListBookmarks("l1", any(), any(), any(), any()) } throws
            RuntimeException("offline")

        // A scan error must not surface as "in the list" — that would drive a
        // wrong removal on the next toggle.
        assert(repo.listsContaining("abc", listOf("l1")).isEmpty())
    }

    @Test
    fun `add and remove call the API with list and bookmark ids in order`() = runTest {
        repo.addBookmarkToList("abc", "l1")
        repo.removeBookmarkFromList("abc", "l1")

        coVerify { api.addBookmarkToList("l1", "abc") }
        coVerify { api.removeBookmarkFromList("l1", "abc") }
    }

    // --- Offline highlights (D13) ---

    @Test
    fun `creating online swaps the optimistic local row for the server's`() = runTest {
        coEvery { api.createHighlight(any()) } returns
            HighlightDto(id = "srv1", bookmarkId = "b", startOffset = 0, endOffset = 5, text = "hi")

        val result = repo.createHighlight("b", 0, 5, "hi")

        assert(result.id == "srv1")
        // Optimistic unsynced insert first, then the server row replaces it.
        coVerify { highlightDao.upsert(match { !it.synced }) }
        coVerify { highlightDao.upsert(match { it.id == "srv1" && it.synced }) }
        coVerify(exactly = 0) { highlightOpDao.insert(any()) }
    }

    @Test
    fun `creating offline keeps the local row and queues a create op`() = runTest {
        coEvery { api.createHighlight(any()) } throws java.io.IOException("offline")

        val result = repo.createHighlight("b", 0, 5, "hi")

        assert(result.id.startsWith(CachedHighlightEntity.LOCAL_ID_PREFIX))
        coVerify { highlightOpDao.insert(match { it.type == HighlightOpEntity.TYPE_CREATE }) }
        coVerify { pendingOpSync.schedule() }
        // The optimistic row must survive so the reader still shows it offline.
        coVerify(exactly = 0) { highlightDao.delete(any()) }
    }

    @Test
    fun `deleting a still-local highlight never touches the server`() = runTest {
        val localId = CachedHighlightEntity.LOCAL_ID_PREFIX + "xyz"

        repo.deleteHighlight(localId)

        coVerify { highlightDao.delete(localId) }
        coVerify { highlightOpDao.deleteForHighlight(localId) }
        coVerify(exactly = 0) { api.deleteHighlight(any()) }
    }

    @Test
    fun `deleting offline queues a delete op`() = runTest {
        coEvery { highlightDao.get("srv1") } returns
            CachedHighlightEntity("srv1", "b", 0, 5, "yellow", "hi", null, 0L, synced = true)
        coEvery { api.deleteHighlight("srv1") } throws java.io.IOException("offline")

        repo.deleteHighlight("srv1")

        coVerify { highlightOpDao.insert(match { it.type == HighlightOpEntity.TYPE_DELETE && it.highlightId == "srv1" }) }
        coVerify { pendingOpSync.schedule() }
    }

    @Test
    fun `flushing a queued create remaps the local id to the server id`() = runTest {
        val op = HighlightOpEntity(
            id = 1, highlightId = "local-1", bookmarkId = "b",
            type = HighlightOpEntity.TYPE_CREATE, startOffset = 0, endOffset = 5,
            color = "yellow", text = "hi", createdAt = 0L,
        )
        coEvery { highlightOpDao.all() } returns listOf(op)
        coEvery { api.createHighlight(any()) } returns
            HighlightDto(id = "srv1", bookmarkId = "b", startOffset = 0, endOffset = 5, text = "hi")
        coEvery { highlightDao.get("local-1") } returns
            CachedHighlightEntity("local-1", "b", 0, 5, "yellow", "hi", note = "my note", updatedAt = 0L, synced = false)

        val cleared = repo.flushHighlightOps()

        assert(cleared)
        coVerify { highlightDao.delete("local-1") }
        // The synced server row keeps the note added while the highlight was local.
        coVerify { highlightDao.upsert(match { it.id == "srv1" && it.synced && it.note == "my note" }) }
        coVerify { highlightOpDao.remapHighlightId("local-1", "srv1") }
        coVerify { highlightOpDao.delete(1) }
    }

    @Test
    fun `flushing keeps a delete op through a transport error`() = runTest {
        val op = HighlightOpEntity(
            id = 2, highlightId = "srv1", bookmarkId = "b",
            type = HighlightOpEntity.TYPE_DELETE, createdAt = 0L,
        )
        coEvery { highlightOpDao.all() } returns listOf(op)
        coEvery { api.deleteHighlight("srv1") } throws java.io.IOException("offline")

        val cleared = repo.flushHighlightOps()

        assert(!cleared)
        coVerify(exactly = 0) { highlightOpDao.delete(2) }
    }

    @Test
    fun `refresh does not resurrect a highlight deleted offline`() = runTest {
        coEvery { api.getBookmarkHighlights("b") } returns PaginatedHighlightsDto(
            listOf(
                HighlightDto(id = "srv1", bookmarkId = "b"),
                HighlightDto(id = "srv2", bookmarkId = "b"),
            ),
        )
        // A queued DELETE for srv1: the server still has it, but we must not
        // bring it back into the cache.
        coEvery { highlightOpDao.all() } returns listOf(
            HighlightOpEntity(id = 1, highlightId = "srv1", bookmarkId = "b", type = HighlightOpEntity.TYPE_DELETE, createdAt = 0L),
        )

        repo.refreshHighlights("b")

        coVerify { highlightDao.deleteSyncedForBookmark("b") }
        coVerify { highlightDao.upsertAll(match { list -> list.size == 1 && list.first().id == "srv2" }) }
    }

    // --- Local backup (H9) ---

    @Test
    fun `export snapshots progress and reading days`() = runTest {
        every { progressDao.observeAll() } returns flowOf(
            listOf(ReadingProgressEntity("a", 0.4f, 5L, anchor = "1:0.1")),
        )
        every { statsDao.observeAll() } returns flowOf(
            listOf(ReadingDayEntity("2026-06-15", 600L, 7L)),
        )

        val backup = repo.exportLocalData()

        assertEquals(1, backup.progress.size)
        assertEquals("a", backup.progress.first().bookmarkId)
        assertEquals("1:0.1", backup.progress.first().anchor)
        assertEquals(1, backup.readingDays.size)
        assertEquals(600L, backup.readingDays.first().seconds)
    }

    @Test
    fun `import keeps the newer reading position and adds unseen ones`() = runTest {
        // Local "a" is newer than the backup's copy → keep local; "b" is new.
        coEvery { progressDao.get("a") } returns ReadingProgressEntity("a", 0.9f, 100L, null)
        coEvery { progressDao.get("b") } returns null

        val summary = repo.importLocalData(
            LocalBackup(
                progress = listOf(
                    ProgressEntry("a", 0.1f, 50L, null),   // older → ignored
                    ProgressEntry("b", 0.7f, 80L, null),   // unseen → applied
                ),
            ),
        )

        assertEquals(1, summary.progress)
        coVerify(exactly = 0) { progressDao.upsert(match { it.bookmarkId == "a" }) }
        coVerify { progressDao.upsert(match { it.bookmarkId == "b" && it.fraction == 0.7f }) }
    }

    @Test
    fun `import grows a day tally but never shrinks it`() = runTest {
        coEvery { statsDao.get("d1") } returns ReadingDayEntity("d1", 600L, 1L) // already higher
        coEvery { statsDao.get("d2") } returns ReadingDayEntity("d2", 100L, 1L) // backup has more

        val summary = repo.importLocalData(
            LocalBackup(
                readingDays = listOf(
                    ReadingDayEntry("d1", 300L, 9L),
                    ReadingDayEntry("d2", 500L, 9L),
                ),
            ),
        )

        assertEquals(1, summary.days)
        coVerify(exactly = 0) { statsDao.upsert(match { it.date == "d1" }) }
        coVerify { statsDao.upsert(match { it.date == "d2" && it.seconds == 500L }) }
    }
}
