package ch.lkmc.kararead.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import ch.lkmc.kararead.data.local.CachedArticleDao
import ch.lkmc.kararead.data.local.ReadingDayEntity
import ch.lkmc.kararead.data.local.ReadingProgressDao
import ch.lkmc.kararead.data.local.ReadingProgressEntity
import ch.lkmc.kararead.data.local.ReadingStatsDao
import ch.lkmc.kararead.data.model.Bookmark
import ch.lkmc.kararead.data.model.BookmarkSource
import ch.lkmc.kararead.data.model.ConnectionSettings
import ch.lkmc.kararead.data.model.Highlight
import ch.lkmc.kararead.data.model.KarakeepList
import ch.lkmc.kararead.data.model.QueueSort
import ch.lkmc.kararead.data.model.ReaderArticle
import ch.lkmc.kararead.data.model.ReadingProgress
import ch.lkmc.kararead.data.model.Tag
import ch.lkmc.kararead.data.remote.ApiProvider
import ch.lkmc.kararead.data.remote.KarakeepApi
import ch.lkmc.kararead.data.remote.dto.UpdateBookmarkRequest
import ch.lkmc.kararead.data.remote.toCacheEntity
import ch.lkmc.kararead.data.remote.toDomain
import ch.lkmc.kararead.data.remote.toReaderArticle
import ch.lkmc.kararead.data.paging.BookmarksPagingSource
import ch.lkmc.kararead.reader.AssetLoader
import ch.lkmc.kararead.util.ReadingStats
import ch.lkmc.kararead.util.computeReadingStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ConnectionResult {
    data class Success(val userLabel: String) : ConnectionResult
    data class Unauthorized(val message: String) : ConnectionResult
    data class Failure(val message: String) : ConnectionResult
}

@Singleton
class KarakeepRepository @Inject constructor(
    private val apiProvider: ApiProvider,
    private val progressDao: ReadingProgressDao,
    private val cacheDao: CachedArticleDao,
    private val statsDao: ReadingStatsDao,
    private val assetLoader: AssetLoader,
) {
    private fun api(): KarakeepApi = apiProvider.api()
    private val assetResolver: (String) -> String? = { apiProvider.assetUrl(it) }

    // --- Paging ---

    fun bookmarkPager(source: BookmarkSource, sort: QueueSort): Flow<PagingData<Bookmark>> {
        val order = if (sort == QueueSort.OLDEST) "asc" else "desc"
        return Pager(
            config = PagingConfig(
                pageSize = KarakeepApi.DEFAULT_PAGE_SIZE,
                prefetchDistance = 8,
                initialLoadSize = KarakeepApi.DEFAULT_PAGE_SIZE,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = {
                BookmarksPagingSource(
                    loader = { cursor, limit -> loadPage(source, order, cursor, limit) },
                    mapper = { list -> list.map { it.toDomain(assetResolver) } },
                )
            },
        ).flow
    }

    private suspend fun loadPage(source: BookmarkSource, order: String, cursor: String?, limit: Int) =
        when (source) {
            is BookmarkSource.Inbox ->
                api().getBookmarks(archived = false, sortOrder = order, cursor = cursor, limit = limit)
            is BookmarkSource.Archive ->
                api().getBookmarks(archived = true, sortOrder = order, cursor = cursor, limit = limit)
            is BookmarkSource.Favourites ->
                api().getBookmarks(favourited = true, sortOrder = order, cursor = cursor, limit = limit)
            is BookmarkSource.ListSource ->
                api().getListBookmarks(source.listId, sortOrder = order, cursor = cursor, limit = limit)
            is BookmarkSource.TagSource ->
                api().getTagBookmarks(source.tagId, cursor = cursor, limit = limit)
            is BookmarkSource.SearchSource ->
                api().searchBookmarks(source.query, cursor = cursor, limit = limit)
        }

    // --- Article (cache-first) ---

    suspend fun getArticle(id: String, forceRefresh: Boolean = false): ReaderArticle {
        if (!forceRefresh) {
            cacheDao.get(id)?.let { return it.toReaderArticle() }
        }
        return try {
            val dto = api().getBookmark(id, includeContent = true)
            val article = dto.toReaderArticle(assetResolver)
            runCatching { cacheDao.upsert(article.toCacheEntity(System.currentTimeMillis())) }
            article
        } catch (e: Exception) {
            // Offline fallback to any cached copy.
            cacheDao.get(id)?.toReaderArticle() ?: throw e
        }
    }

    suspend fun getBookmark(id: String): Bookmark =
        api().getBookmark(id, includeContent = false).toDomain(assetResolver)

    /**
     * Best-effort live refresh of just the read/favourite flags (lightweight, no
     * content), keeping any cached copy in sync. Returns null when unreachable so
     * callers can keep showing the last-known state offline.
     */
    suspend fun refreshReadState(id: String): Pair<Boolean, Boolean>? = runCatching {
        val bm = api().getBookmark(id, includeContent = false).toDomain(assetResolver)
        cacheDao.get(id)?.let {
            cacheDao.upsert(it.copy(archived = bm.archived, favourited = bm.favourited))
        }
        bm.archived to bm.favourited
    }.getOrNull()

    // --- Mutations ---

    suspend fun setArchived(id: String, archived: Boolean) {
        api().updateBookmark(id, UpdateBookmarkRequest(archived = archived))
        // Uncache on read: once an article is archived (done reading) it leaves
        // the offline queue, so drop its cached copy to free space.
        if (archived) runCatching { cacheDao.delete(id) }
    }

    suspend fun setFavourited(id: String, favourited: Boolean) {
        api().updateBookmark(id, UpdateBookmarkRequest(favourited = favourited))
    }

    /** Save a new link to Karakeep, optionally adding it to a list. Returns the new id. */
    suspend fun saveLink(url: String, listId: String? = null): String {
        val created = api().createBookmark(
            ch.lkmc.kararead.data.remote.dto.CreateBookmarkRequest(url = url),
        )
        if (listId != null) {
            runCatching { api().addBookmarkToList(listId, created.id) }
        }
        return created.id
    }

    suspend fun deleteBookmark(id: String) {
        api().deleteBookmark(id)
        runCatching { cacheDao.delete(id); progressDao.delete(id) }
    }

    // --- Lists / Tags / Highlights ---

    suspend fun getLists(): List<KarakeepList> = api().getLists().lists.map { it.toDomain() }

    suspend fun getTags(): List<Tag> =
        api().getTags().tags.map { it.toDomain() }.sortedByDescending { it.count }

    suspend fun getHighlights(bookmarkId: String): List<Highlight> =
        api().getBookmarkHighlights(bookmarkId).highlights.map { it.toDomain() }

    suspend fun createHighlight(
        bookmarkId: String,
        startOffset: Int,
        endOffset: Int,
        text: String?,
        color: String = "yellow",
    ): Highlight = api().createHighlight(
        ch.lkmc.kararead.data.remote.dto.CreateHighlightRequest(
            bookmarkId = bookmarkId,
            startOffset = startOffset,
            endOffset = endOffset,
            color = color,
            text = text,
        ),
    ).toDomain()

    suspend fun deleteHighlight(id: String) = api().deleteHighlight(id)

    // --- Connection test ---

    suspend fun testConnection(settings: ConnectionSettings): ConnectionResult {
        apiProvider.configure(settings)
        return try {
            val user = apiProvider.api().getCurrentUser()
            ConnectionResult.Success(user.name ?: user.email ?: "Connected")
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 401 || e.code() == 403) {
                ConnectionResult.Unauthorized("Invalid API key (HTTP ${e.code()}).")
            } else {
                ConnectionResult.Failure("Server returned HTTP ${e.code()}.")
            }
        } catch (e: Exception) {
            ConnectionResult.Failure(e.message ?: "Could not reach the server.")
        }
    }

    // --- Reading progress ---

    fun progress(id: String): Flow<ReadingProgress?> =
        progressDao.observe(id).map { it?.let { e -> ReadingProgress(e.bookmarkId, e.fraction, e.updatedAt) } }

    fun allProgress(): Flow<Map<String, Float>> =
        progressDao.observeAll().map { list -> list.associate { it.bookmarkId to it.fraction } }

    suspend fun getProgressOnce(id: String): Float =
        progressDao.get(id)?.fraction ?: 0f

    suspend fun saveProgress(id: String, fraction: Float) {
        progressDao.upsert(
            ReadingProgressEntity(id, fraction.coerceIn(0f, 1f), System.currentTimeMillis()),
        )
    }

    // --- Reading stats (streaks / minutes) ---

    /** Add active reading time to today's tally. */
    suspend fun addReadingSeconds(seconds: Long) {
        if (seconds <= 0L) return
        val date = LocalDate.now().toString()
        val existing = statsDao.get(date)?.seconds ?: 0L
        statsDao.upsert(ReadingDayEntity(date, existing + seconds, System.currentTimeMillis()))
    }

    fun readingStats(): Flow<ReadingStats> =
        statsDao.observeAll().map { rows ->
            computeReadingStats(rows.associate { it.date to it.seconds })
        }

    suspend fun clearCache() {
        cacheDao.clear()
    }

    suspend fun cachedCount(): Int = cacheDao.count()

    /** Ids of articles currently available offline, for "downloaded" indicators. */
    fun cachedIds(): Flow<Set<String>> = cacheDao.observeIds().map { it.toSet() }

    /** First [limit] bookmarks of a source, without paging (for offline prefetch). */
    private suspend fun firstPage(source: BookmarkSource, limit: Int): List<Bookmark> =
        loadPage(source, order = "desc", cursor = null, limit = limit)
            .bookmarks.map { it.toDomain(assetResolver) }

    /**
     * Keep the top [limit] unread articles of [source] downloaded for offline
     * reading. Returns how many of that set are now cached. Eviction of read
     * articles is handled eagerly by [setArchived]; this only fills the queue.
     */
    suspend fun syncOffline(source: BookmarkSource, limit: Int): Int {
        val wanted = firstPage(source, limit)
        val origin = apiProvider.serverOrigin
        var ready = 0
        for (bm in wanted) {
            val article = runCatching { getArticle(bm.id) }.getOrNull()
            if (article != null) {
                ready++
                // Download the article's images too, so it reads fully offline.
                runCatching { assetLoader.prefetchImages(article.htmlContent, origin) }
            }
        }
        return ready
    }
}
