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
import ch.lkmc.kararead.data.model.RecentArticle
import ch.lkmc.kararead.data.model.Tag
import ch.lkmc.kararead.data.remote.ApiProvider
import ch.lkmc.kararead.data.remote.KarakeepApi
import ch.lkmc.kararead.data.remote.dto.UpdateBookmarkRequest
import ch.lkmc.kararead.data.remote.dto.UpdateHighlightRequest
import ch.lkmc.kararead.data.remote.toCacheEntity
import ch.lkmc.kararead.data.remote.toDomain
import ch.lkmc.kararead.data.remote.toReaderArticle
import ch.lkmc.kararead.data.paging.BookmarksPagingSource
import ch.lkmc.kararead.reader.AssetLoader
import ch.lkmc.kararead.util.ReadingStats
import ch.lkmc.kararead.util.computeReadingStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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

    // Ids archived (marked read) anywhere this session. The library listens so an
    // article finished from the reader disappears from the inbox on return,
    // instead of lingering in its cached paging list.
    private val _archivedIds = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val archivedIds: SharedFlow<String> = _archivedIds

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
            // Only serve a cached copy that actually has content. An article that
            // was still processing when first opened is fetched empty; caching and
            // then serving that empty copy would pin the "no readable content"
            // placeholder forever — so treat a content-less cache row as a miss
            // and re-fetch, letting it recover once the server finishes.
            cacheDao.get(id)?.takeIf { !it.html.isNullOrBlank() }?.let { return it.toReaderArticle() }
        }
        return try {
            val dto = api().getBookmark(id, includeContent = true)
            val article = dto.toReaderArticle(assetResolver)
            // Don't cache an unprocessed (content-less) article, or reopening would
            // keep hitting the empty cache instead of re-fetching.
            if (!article.htmlContent.isNullOrBlank()) {
                runCatching { cacheDao.upsert(article.toCacheEntity(System.currentTimeMillis())) }
            }
            article
        } catch (e: Exception) {
            // Offline fallback to any cached copy that has content.
            cacheDao.get(id)?.takeIf { !it.html.isNullOrBlank() }?.toReaderArticle() ?: throw e
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
        if (archived) {
            runCatching { cacheDao.delete(id) }
            _archivedIds.tryEmit(id)
        }
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

    /** Every highlight across all bookmarks, newest first, paged in up to [max]. */
    suspend fun getAllHighlights(max: Int = 1000): List<Highlight> {
        val out = mutableListOf<Highlight>()
        var cursor: String? = null
        do {
            val page = api().getAllHighlights(
                limit = KarakeepApi.DEFAULT_PAGE_SIZE,
                cursor = cursor,
            )
            out += page.highlights.map { it.toDomain() }
            cursor = page.nextCursor
        } while (cursor != null && out.size < max)
        return out
    }

    /** Lightweight bookmark metadata (no body) — used to label highlights. */
    suspend fun getBookmarkMeta(bookmarkId: String): Bookmark =
        api().getBookmark(bookmarkId, includeContent = false).toDomain(assetResolver)

    suspend fun updateHighlightNote(id: String, note: String): Highlight =
        api().updateHighlight(id, UpdateHighlightRequest(note = note)).toDomain()

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
        progressDao.observe(id).map { it?.toDomain() }

    fun allProgress(): Flow<Map<String, Float>> =
        progressDao.observeAll().map { list -> list.associate { it.bookmarkId to it.fraction } }

    /** The saved progress (fraction + block anchor) for restore, or null. */
    suspend fun getProgressOnce(id: String): ReadingProgress? =
        progressDao.get(id)?.toDomain()

    suspend fun saveProgress(id: String, fraction: Float, anchor: String? = null) {
        progressDao.upsert(
            ReadingProgressEntity(id, fraction.coerceIn(0f, 1f), System.currentTimeMillis(), anchor),
        )
    }

    private fun ReadingProgressEntity.toDomain() =
        ReadingProgress(bookmarkId, fraction, updatedAt, anchor)

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

    /** Raw reading seconds keyed by ISO date, for the stats chart. */
    fun readingSecondsByDate(): Flow<Map<String, Long>> =
        statsDao.observeAll().map { rows -> rows.associate { it.date to it.seconds } }

    suspend fun clearCache() {
        cacheDao.clear()
    }

    suspend fun cachedCount(): Int = cacheDao.count()

    /** Ids of articles currently available offline, for "downloaded" indicators. */
    fun cachedIds(): Flow<Set<String>> = cacheDao.observeIds().map { it.toSet() }

    /** Cached reading-time hints, so list cards can show "N min" once an article
     *  has been opened (list/search responses omit content, hence reading time). */
    fun cachedReadingTimes(): Flow<Map<String, Int>> =
        cacheDao.observeReadingTimes().map { rows -> rows.associate { it.bookmarkId to it.readingTimeMinutes } }

    /** Recently opened articles (newest first) for the library's quick-resume strip. */
    fun recentlyOpened(limit: Int = 12): Flow<List<RecentArticle>> =
        cacheDao.observeRecent(limit).map { rows ->
            rows.map { RecentArticle(it.bookmarkId, it.title, it.imageUrl, it.fraction) }
        }

    /** First [limit] bookmarks of a source, without paging (for offline prefetch). */
    private suspend fun firstPage(source: BookmarkSource, limit: Int): List<Bookmark> =
        loadPage(source, order = "desc", cursor = null, limit = limit)
            .bookmarks.map { it.toDomain(assetResolver) }

    /** A random bookmark id from the first page of [source], for "surprise me". */
    suspend fun randomBookmarkId(source: BookmarkSource, limit: Int = 50): String? =
        runCatching { firstPage(source, limit) }.getOrNull()?.randomOrNull()?.id

    /** The next unread inbox article to read after [excludingId], if any. */
    suspend fun nextInboxBookmark(excludingId: String): Bookmark? =
        runCatching { firstPage(BookmarkSource.Inbox, 10) }.getOrNull()
            ?.firstOrNull { it.id != excludingId }

    /** The next unread inbox article id to read after [excludingId], if any. */
    suspend fun nextInboxId(excludingId: String): String? =
        nextInboxBookmark(excludingId)?.id

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
