package ch.lkmc.kararead.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import ch.lkmc.kararead.data.local.CachedArticleDao
import ch.lkmc.kararead.data.local.ReadingProgressDao
import ch.lkmc.kararead.data.local.ReadingProgressEntity
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    // --- Mutations ---

    suspend fun setArchived(id: String, archived: Boolean) {
        api().updateBookmark(id, UpdateBookmarkRequest(archived = archived))
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

    suspend fun clearCache() {
        cacheDao.clear()
    }

    suspend fun cachedCount(): Int = cacheDao.count()
}
