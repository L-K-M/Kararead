package ch.lkmc.kararead.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import ch.lkmc.kararead.data.local.CachedArticleDao
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
import ch.lkmc.kararead.data.remote.toBookmark
import ch.lkmc.kararead.data.remote.toCacheEntity
import ch.lkmc.kararead.data.remote.toDomain
import ch.lkmc.kararead.data.remote.toReaderArticle
import ch.lkmc.kararead.data.paging.BookmarksPagingSource
import ch.lkmc.kararead.reader.AssetLoader
import ch.lkmc.kararead.work.PendingOpSync
import ch.lkmc.kararead.util.LocalBackup
import ch.lkmc.kararead.util.ProgressEntry
import ch.lkmc.kararead.util.ReadingDayEntry
import ch.lkmc.kararead.util.ReadingStats
import ch.lkmc.kararead.util.computeReadingStats
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ConnectionResult {
    data class Success(val userLabel: String) : ConnectionResult
    data class Unauthorized(val message: String) : ConnectionResult
    data class Failure(val message: String) : ConnectionResult
}

/**
 * Drop a queued offline op after this many replays the server answered with a
 * 5xx, so a change it keeps erroring on can't loop forever. Definitive 4xx
 * rejections are dropped immediately; transport errors (offline, unreachable
 * self-hosted box) never count — the whole point of the outbox is to survive
 * them, however long they last.
 */
private const val MAX_SYNC_ATTEMPTS = 20

@Singleton
class KarakeepRepository @Inject constructor(
    private val apiProvider: ApiProvider,
    private val progressDao: ReadingProgressDao,
    private val cacheDao: CachedArticleDao,
    private val statsDao: ReadingStatsDao,
    private val pendingOpDao: PendingOpDao,
    private val pendingOpSync: PendingOpSync,
    private val assetLoader: AssetLoader,
    private val highlightDao: CachedHighlightDao,
    private val highlightOpDao: HighlightOpDao,
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
            cacheDao.get(id)?.let { cached ->
                // A body-less row was cached while the server was still crawling
                // the article — treat it as a miss so the copy can heal itself
                // once the crawl finishes, instead of showing "no readable
                // content yet" forever.
                if (!cached.html.isNullOrBlank()) {
                    // Reading from cache counts as use: keep cachedAt fresh so the
                    // 30-day cleanup evicts by last-opened, not first-downloaded.
                    runCatching { cacheDao.touch(id, System.currentTimeMillis()) }
                    return cached.toReaderArticle()
                }
            }
        }
        return try {
            val dto = api().getBookmark(id, includeContent = true)
            val article = dto.toReaderArticle(assetResolver)
            // Don't cache content-less articles: the cache-first path above
            // would keep serving the empty copy long after the server has the
            // real content.
            if (!article.htmlContent.isNullOrBlank()) {
                runCatching { cacheDao.upsert(article.toCacheEntity(System.currentTimeMillis())) }
            }
            article
        } catch (e: Exception) {
            // Offline fallback to any cached copy (even a body-less one beats
            // an error screen when there's no connection).
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
        // A queued offline change is newer than whatever the server still says;
        // keep the user's value until the outbox has flushed it.
        val archived = pendingOpDao.getFor(id, PendingOpEntity.TYPE_ARCHIVED)?.value ?: bm.archived
        val favourited =
            pendingOpDao.getFor(id, PendingOpEntity.TYPE_FAVOURITED)?.value ?: bm.favourited
        cacheDao.get(id)?.let {
            cacheDao.upsert(it.copy(archived = archived, favourited = favourited))
        }
        archived to favourited
    }.getOrNull()

    // --- Mutations ---

    // Mutations run under NonCancellable: they're launched from ViewModel scopes
    // that die the moment the user navigates away, and a cancelled PATCH would
    // otherwise be mis-read as "offline" — after which even the queueOp fallback
    // can't run on the dead coroutine, silently losing the tap.
    suspend fun setArchived(id: String, archived: Boolean): Unit =
        withContext(NonCancellable) {
            // Optimistically reflect the change in the cache so offline/cached lists
            // update at once; the live listing will agree once it (re)loads.
            cacheDao.get(id)?.let { runCatching { cacheDao.upsert(it.copy(archived = archived)) } }
            val synced = runCatching {
                api().updateBookmark(id, UpdateBookmarkRequest(archived = archived))
            }.isSuccess
            if (synced) {
                // The server now has the user's latest word on this field; a
                // still-queued offline op is stale and must not replay later
                // (it would silently revert this change).
                runCatching { pendingOpDao.deleteFor(id, PendingOpEntity.TYPE_ARCHIVED) }
                // Uncache on read: once an article is archived (done reading) it leaves
                // the offline queue, so drop its cached copy to free space.
                if (archived) runCatching { cacheDao.delete(id) }
            } else {
                // Offline (or transient): queue the change to replay when back online.
                queueOp(id, PendingOpEntity.TYPE_ARCHIVED, archived)
            }
            if (archived) _archivedIds.tryEmit(id)
        }

    suspend fun setFavourited(id: String, favourited: Boolean): Unit =
        withContext(NonCancellable) {
            cacheDao.get(id)?.let { runCatching { cacheDao.upsert(it.copy(favourited = favourited)) } }
            val synced = runCatching {
                api().updateBookmark(id, UpdateBookmarkRequest(favourited = favourited))
            }.isSuccess
            if (synced) {
                runCatching { pendingOpDao.deleteFor(id, PendingOpEntity.TYPE_FAVOURITED) }
            } else {
                queueOp(id, PendingOpEntity.TYPE_FAVOURITED, favourited)
            }
        }

    /** Add (or overwrite) an outbox entry and ask for a flush when online. */
    private suspend fun queueOp(id: String, type: String, value: Boolean) {
        pendingOpDao.upsert(
            PendingOpEntity(
                bookmarkId = id,
                type = type,
                value = value,
                createdAt = System.currentTimeMillis(),
            ),
        )
        pendingOpSync.schedule()
    }

    /**
     * Replay queued offline archive/favourite changes against the server, oldest
     * first. Confirmed ops are dropped (archives also uncache, as if done live);
     * ones that still fail keep their place and bump their attempt count, and are
     * abandoned after [MAX_SYNC_ATTEMPTS]. Returns true once the queue is empty.
     */
    suspend fun flushPendingOps(): Boolean {
        var allCleared = true
        for (op in pendingOpDao.all()) {
            val request = when (op.type) {
                PendingOpEntity.TYPE_ARCHIVED -> UpdateBookmarkRequest(archived = op.value)
                PendingOpEntity.TYPE_FAVOURITED -> UpdateBookmarkRequest(favourited = op.value)
                else -> { pendingOpDao.delete(op.id); continue }
            }
            val result = runCatching { api().updateBookmark(op.bookmarkId, request) }
            val error = result.exceptionOrNull()
            when {
                result.isSuccess -> {
                    pendingOpDao.delete(op.id)
                    if (op.type == PendingOpEntity.TYPE_ARCHIVED && op.value) {
                        runCatching { cacheDao.delete(op.bookmarkId) }
                        _archivedIds.tryEmit(op.bookmarkId)
                    }
                }
                error is kotlinx.coroutines.CancellationException -> throw error
                error is retrofit2.HttpException && error.code() in 400..499 -> {
                    // The server definitively rejected the change (bookmark
                    // deleted, bad request) — retrying can never succeed.
                    pendingOpDao.delete(op.id)
                }
                error is retrofit2.HttpException -> {
                    // Server-side error: retry, but cap so a permanently
                    // erroring op can't loop forever.
                    val attempts = op.attempts + 1
                    if (attempts >= MAX_SYNC_ATTEMPTS) {
                        pendingOpDao.delete(op.id)
                    } else {
                        pendingOpDao.setAttempts(op.id, attempts)
                        allCleared = false
                    }
                }
                else -> {
                    // Transport error — offline, or the (self-hosted) server is
                    // unreachable even though the phone has internet. This can
                    // last hours; the op must survive it without burning
                    // attempts, or a train ride silently discards the user's
                    // archives.
                    allCleared = false
                }
            }
        }
        return allCleared
    }

    /** Outcome of [saveLink]: the created id, and whether the list-add worked. */
    data class SaveLinkResult(val id: String, val addedToList: Boolean)

    /** Save a new link to Karakeep, optionally adding it to a list. */
    suspend fun saveLink(url: String, listId: String? = null): SaveLinkResult {
        val created = api().createBookmark(
            ch.lkmc.kararead.data.remote.dto.CreateBookmarkRequest(url = url),
        )
        // Don't fail the whole save if only the list-add breaks — but do
        // report it: the user's home queue IS the read-later list, and a
        // cheerful full-success toast used to hide that the article would
        // never appear there.
        val addedToList = listId == null ||
            runCatching { api().addBookmarkToList(listId, created.id) }.isSuccess
        return SaveLinkResult(created.id, addedToList)
    }

    suspend fun deleteBookmark(id: String) {
        api().deleteBookmark(id)
        runCatching { cacheDao.delete(id); progressDao.delete(id) }
    }

    // --- Lists / Tags / Highlights ---

    suspend fun getLists(): List<KarakeepList> = api().getLists().lists.map { it.toDomain() }

    /**
     * Manual lists only — smart lists are query-defined, so their membership
     * can't be edited by hand and they don't belong in an "Add to list" picker.
     */
    suspend fun getManualLists(): List<KarakeepList> =
        getLists().filter { it.type == "manual" }

    /**
     * Best-effort: which of [listIds] currently contain [bookmarkId]. Karakeep
     * offers no reverse lookup, so we scan each list's first page. A false
     * negative (the article sits deeper than one page) only leads to an
     * idempotent re-add — never a wrong removal — so this stays safe.
     */
    suspend fun listsContaining(bookmarkId: String, listIds: List<String>): Set<String> =
        coroutineScope {
            listIds.map { listId ->
                async {
                    val present = runCatching {
                        api().getListBookmarks(listId, limit = 100)
                            .bookmarks.any { it.id == bookmarkId }
                    }.getOrDefault(false)
                    listId to present
                }
            }.awaitAll().filter { it.second }.map { it.first }.toSet()
        }

    suspend fun addBookmarkToList(bookmarkId: String, listId: String) =
        withContext(NonCancellable) { api().addBookmarkToList(listId, bookmarkId) }

    suspend fun removeBookmarkFromList(bookmarkId: String, listId: String) =
        withContext(NonCancellable) { api().removeBookmarkFromList(listId, bookmarkId) }

    suspend fun getTags(): List<Tag> =
        api().getTags().tags.map { it.toDomain() }.sortedByDescending { it.count }

    // Highlights are cache-first and offline-capable: the reader observes the
    // local cache, and create/note/delete mutations fall back to an outbox
    // (highlight_op) that the same WorkManager sync as the archive/favourite
    // outbox replays once the server is reachable again.

    private fun CachedHighlightEntity.toDomain() =
        Highlight(id, bookmarkId, startOffset, endOffset, color, text, note)

    private fun Highlight.toCacheEntity(synced: Boolean, updatedAt: Long = System.currentTimeMillis()) =
        CachedHighlightEntity(id, bookmarkId, startOffset, endOffset, color, text, note, updatedAt, synced)

    /** The cached highlights for an article, reactive so offline edits show at once. */
    fun observeHighlights(bookmarkId: String): Flow<List<Highlight>> =
        highlightDao.observeForBookmark(bookmarkId).map { rows -> rows.map { it.toDomain() } }

    /**
     * Pull the server's highlights for an article into the cache (best-effort;
     * a no-op offline). Server rows are authoritative, but unsynced local
     * highlights are kept, and any the user deleted offline (a queued DELETE)
     * aren't resurrected.
     */
    suspend fun refreshHighlights(bookmarkId: String) {
        val server = runCatching {
            api().getBookmarkHighlights(bookmarkId).highlights.map { it.toDomain() }
        }.getOrNull() ?: return
        val pendingDeletes = highlightOpDao.all()
            .filter { it.type == HighlightOpEntity.TYPE_DELETE }
            .map { it.highlightId }
            .toSet()
        val fresh = server
            .filterNot { it.id in pendingDeletes }
            .map { it.toCacheEntity(synced = true) }
        highlightDao.deleteSyncedForBookmark(bookmarkId)
        highlightDao.upsertAll(fresh)
    }

    /**
     * Every highlight across all bookmarks (for the Highlights screen). Server
     * order when reachable — and the results refill the cache — falling back to
     * whatever is cached when offline.
     */
    suspend fun getAllHighlights(max: Int = 1000): List<Highlight> = runCatching {
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
        runCatching { highlightDao.upsertAll(out.map { it.toCacheEntity(synced = true) }) }
        out
    }.getOrElse {
        highlightDao.all().map { it.toDomain() }
    }

    /** Lightweight bookmark metadata (no body) — used to label highlights. */
    suspend fun getBookmarkMeta(bookmarkId: String): Bookmark =
        api().getBookmark(bookmarkId, includeContent = false).toDomain(assetResolver)

    suspend fun updateHighlightNote(id: String, note: String): Highlight = withContext(NonCancellable) {
        val existing = highlightDao.get(id)
        existing?.let { highlightDao.upsert(it.copy(note = note, updatedAt = System.currentTimeMillis())) }
        val isLocal = id.startsWith(CachedHighlightEntity.LOCAL_ID_PREFIX)
        if (!isLocal) {
            val server = runCatching {
                api().updateHighlight(id, UpdateHighlightRequest(note = note)).toDomain()
            }.getOrNull()
            if (server != null) {
                highlightDao.upsert(server.toCacheEntity(synced = true))
                return@withContext server
            }
        }
        // Offline, or the highlight itself is still a queued create: park the
        // note in the outbox (collapsing repeated edits into one op).
        highlightOpDao.deleteForHighlightAndType(id, HighlightOpEntity.TYPE_UPDATE_NOTE)
        highlightOpDao.insert(
            HighlightOpEntity(
                highlightId = id,
                bookmarkId = existing?.bookmarkId.orEmpty(),
                type = HighlightOpEntity.TYPE_UPDATE_NOTE,
                note = note,
                createdAt = System.currentTimeMillis(),
            ),
        )
        pendingOpSync.schedule()
        (existing?.copy(note = note))?.toDomain()
            ?: Highlight(id, existing?.bookmarkId.orEmpty(), 0, 0, "yellow", null, note)
    }

    suspend fun createHighlight(
        bookmarkId: String,
        startOffset: Int,
        endOffset: Int,
        text: String?,
        color: String = "yellow",
    ): Highlight = withContext(NonCancellable) {
        val localId = CachedHighlightEntity.LOCAL_ID_PREFIX + UUID.randomUUID()
        val local = Highlight(localId, bookmarkId, startOffset, endOffset, color, text, null)
        // Show it in the reader immediately, before the network round trip.
        highlightDao.upsert(local.toCacheEntity(synced = false))
        val created = runCatching {
            api().createHighlight(
                ch.lkmc.kararead.data.remote.dto.CreateHighlightRequest(
                    bookmarkId = bookmarkId,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    color = color,
                    text = text,
                ),
            ).toDomain()
        }.getOrNull()
        if (created != null) {
            highlightDao.delete(localId)
            highlightDao.upsert(created.toCacheEntity(synced = true))
            created
        } else {
            highlightOpDao.insert(
                HighlightOpEntity(
                    highlightId = localId,
                    bookmarkId = bookmarkId,
                    type = HighlightOpEntity.TYPE_CREATE,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    color = color,
                    text = text,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            pendingOpSync.schedule()
            local
        }
    }

    suspend fun deleteHighlight(id: String) = withContext(NonCancellable) {
        val existing = highlightDao.get(id)
        highlightDao.delete(id)
        if (id.startsWith(CachedHighlightEntity.LOCAL_ID_PREFIX)) {
            // Never reached the server: drop its queued create/note; nothing to delete remotely.
            highlightOpDao.deleteForHighlight(id)
            return@withContext
        }
        val deleted = runCatching { api().deleteHighlight(id); true }.getOrDefault(false)
        if (deleted) {
            highlightOpDao.deleteForHighlight(id)
        } else {
            // A pending note is moot once the highlight is going away.
            highlightOpDao.deleteForHighlightAndType(id, HighlightOpEntity.TYPE_UPDATE_NOTE)
            highlightOpDao.insert(
                HighlightOpEntity(
                    highlightId = id,
                    bookmarkId = existing?.bookmarkId.orEmpty(),
                    type = HighlightOpEntity.TYPE_DELETE,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            pendingOpSync.schedule()
        }
    }

    /**
     * Replay the highlights outbox in order. A queued create's local id is
     * rewritten to the server id (in the cache and later ops) once it lands.
     * Error handling mirrors [flushPendingOps]: 4xx dropped, 5xx capped, and
     * transport errors kept indefinitely.
     */
    suspend fun flushHighlightOps(): Boolean {
        var allCleared = true
        val remap = mutableMapOf<String, String>()
        for (op in highlightOpDao.all()) {
            val effectiveId = remap[op.highlightId] ?: op.highlightId
            val result = runCatching {
                when (op.type) {
                    HighlightOpEntity.TYPE_CREATE -> {
                        val created = api().createHighlight(
                            ch.lkmc.kararead.data.remote.dto.CreateHighlightRequest(
                                bookmarkId = op.bookmarkId,
                                startOffset = op.startOffset,
                                endOffset = op.endOffset,
                                color = op.color,
                                text = op.text,
                            ),
                        ).toDomain()
                        // Swap the optimistic local row for the server's, keeping
                        // any note the user added while it was still local.
                        val local = highlightDao.get(op.highlightId)
                        highlightDao.delete(op.highlightId)
                        highlightDao.upsert(created.toCacheEntity(synced = true).copy(note = local?.note))
                        highlightOpDao.remapHighlightId(op.highlightId, created.id)
                        remap[op.highlightId] = created.id
                    }
                    HighlightOpEntity.TYPE_UPDATE_NOTE ->
                        api().updateHighlight(effectiveId, UpdateHighlightRequest(note = op.note.orEmpty()))
                    HighlightOpEntity.TYPE_DELETE ->
                        api().deleteHighlight(effectiveId)
                    else -> Unit
                }
            }
            val error = result.exceptionOrNull()
            when {
                result.isSuccess -> highlightOpDao.delete(op.id)
                error is kotlinx.coroutines.CancellationException -> throw error
                error is retrofit2.HttpException && error.code() in 400..499 -> highlightOpDao.delete(op.id)
                error is retrofit2.HttpException -> {
                    val attempts = op.attempts + 1
                    if (attempts >= MAX_SYNC_ATTEMPTS) {
                        highlightOpDao.delete(op.id)
                    } else {
                        highlightOpDao.setAttempts(op.id, attempts)
                        allCleared = false
                    }
                }
                else -> allCleared = false
            }
        }
        return allCleared
    }

    // --- Connection test ---

    suspend fun testConnection(settings: ConnectionSettings): ConnectionResult {
        return try {
            // Inside the try: a URL that survives normalization but is still
            // unparsable must surface as a Failure, not crash the Connect tap.
            apiProvider.configure(settings)
            if (!apiProvider.isConfigured()) {
                return ConnectionResult.Failure(
                    "That server URL doesn't look valid — check it and try again.",
                )
            }
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

    // --- Local backup (H9) ---

    /** Snapshot the local-only data (reading progress + per-day tally) for backup. */
    suspend fun exportLocalData(): LocalBackup {
        val progress = progressDao.observeAll().first().map {
            ProgressEntry(it.bookmarkId, it.fraction, it.updatedAt, it.anchor)
        }
        val days = statsDao.observeAll().first().map {
            ReadingDayEntry(it.date, it.seconds, it.updatedAt)
        }
        return LocalBackup(
            exportedAt = System.currentTimeMillis(),
            progress = progress,
            readingDays = days,
        )
    }

    /** How many rows a [importLocalData] restore actually applied. */
    data class ImportSummary(val progress: Int, val days: Int)

    /**
     * Merge a [LocalBackup] into the local data, non-destructively: a reading
     * position is overwritten only by a strictly newer one, and a day's tally
     * only ever grows (max of the two). So restoring an old backup can't erase
     * today's fresh reading, and re-importing the same file is a no-op.
     */
    suspend fun importLocalData(backup: LocalBackup): ImportSummary {
        var progressApplied = 0
        for (entry in backup.progress) {
            val existing = progressDao.get(entry.bookmarkId)
            if (existing == null || entry.updatedAt > existing.updatedAt) {
                progressDao.upsert(
                    ReadingProgressEntity(entry.bookmarkId, entry.fraction, entry.updatedAt, entry.anchor),
                )
                progressApplied++
            }
        }
        var daysApplied = 0
        for (entry in backup.readingDays) {
            val existing = statsDao.get(entry.date)
            val merged = maxOf(existing?.seconds ?: 0L, entry.seconds)
            if (existing == null || merged != existing.seconds) {
                statsDao.upsert(
                    ReadingDayEntity(entry.date, merged, maxOf(existing?.updatedAt ?: 0L, entry.updatedAt)),
                )
                daysApplied++
            }
        }
        return ImportSummary(progressApplied, daysApplied)
    }

    suspend fun clearCache() {
        cacheDao.clear()
    }

    /** How many offline changes (archive/favourite + highlight edits) await sync. */
    fun pendingOpCount(): Flow<Int> =
        combine(pendingOpDao.observeCount(), highlightOpDao.observeCount()) { a, b -> a + b }

    /** Ask WorkManager to replay the outbox now (manual "Retry"). */
    fun retryPendingOps() {
        pendingOpSync.schedule()
    }

    /**
     * Wipe everything tied to the signed-in account: cached articles, the
     * offline outbox, reading progress and stats. Leaving any of it behind
     * replays stale ops against the next server and bleeds one account's
     * reading history into another's.
     */
    suspend fun clearLocalState() {
        runCatching { cacheDao.clear() }
        runCatching { pendingOpDao.clear() }
        runCatching { progressDao.clear() }
        runCatching { statsDao.clear() }
        runCatching { highlightDao.clear() }
        runCatching { highlightOpDao.clear() }
    }

    suspend fun cachedCount(): Int = cacheDao.count()

    /** Whether an article is available from the local cache right now. */
    suspend fun isCached(id: String): Boolean = cacheDao.get(id) != null

    /** Ids of articles currently available offline, for "downloaded" indicators. */
    fun cachedIds(): Flow<Set<String>> = cacheDao.observeIds().map { it.toSet() }

    /**
     * Every downloaded article as a [Bookmark] (metadata only, newest first).
     * Backs the library's offline fallback so cached articles stay readable when
     * the server is unreachable, instead of a "couldn't load" error.
     */
    fun cachedBookmarks(): Flow<List<Bookmark>> =
        cacheDao.observeCachedBookmarks().map { rows -> rows.map { it.toBookmark() } }

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
