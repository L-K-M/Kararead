package ch.lkmc.kararead.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import ch.lkmc.kararead.data.model.Bookmark
import ch.lkmc.kararead.data.remote.dto.PaginatedBookmarksDto

/**
 * Generic cursor-based [PagingSource] for any Karakeep bookmark listing.
 * The actual endpoint call is injected as [loader] so the same source serves
 * the inbox, favourites, archive, lists, tags and search.
 */
class BookmarksPagingSource(
    private val loader: suspend (cursor: String?, limit: Int) -> PaginatedBookmarksDto,
    private val mapper: (List<ch.lkmc.kararead.data.remote.dto.BookmarkDto>) -> List<Bookmark>,
) : PagingSource<String, Bookmark>() {

    override suspend fun load(params: LoadParams<String>): LoadResult<String, Bookmark> {
        return try {
            val cursor = params.key
            val response = loader(cursor, params.loadSize)
            LoadResult.Page(
                data = mapper(response.bookmarks),
                prevKey = null, // forward-only cursor pagination
                nextKey = response.nextCursor,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<String, Bookmark>): String? = null
}
