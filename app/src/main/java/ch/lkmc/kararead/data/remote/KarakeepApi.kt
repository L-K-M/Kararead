package ch.lkmc.kararead.data.remote

import ch.lkmc.kararead.data.remote.dto.BookmarkDto
import ch.lkmc.kararead.data.remote.dto.CreateBookmarkRequest
import ch.lkmc.kararead.data.remote.dto.CreateHighlightRequest
import ch.lkmc.kararead.data.remote.dto.HighlightDto
import ch.lkmc.kararead.data.remote.dto.ListDto
import ch.lkmc.kararead.data.remote.dto.ListsResponseDto
import ch.lkmc.kararead.data.remote.dto.PaginatedBookmarksDto
import ch.lkmc.kararead.data.remote.dto.PaginatedHighlightsDto
import ch.lkmc.kararead.data.remote.dto.TagsResponseDto
import ch.lkmc.kararead.data.remote.dto.UpdateBookmarkRequest
import ch.lkmc.kararead.data.remote.dto.UpdateHighlightRequest
import ch.lkmc.kararead.data.remote.dto.UserDto
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** Retrofit interface for the Karakeep REST API (base URL `{server}/api/v1/`). */
interface KarakeepApi {

    @GET("users/me")
    suspend fun getCurrentUser(): UserDto

    // --- Bookmarks ---

    @GET("bookmarks")
    suspend fun getBookmarks(
        @Query("archived") archived: Boolean? = null,
        @Query("favourited") favourited: Boolean? = null,
        @Query("sortOrder") sortOrder: String = "desc",
        @Query("limit") limit: Int = DEFAULT_PAGE_SIZE,
        @Query("cursor") cursor: String? = null,
        @Query("includeContent") includeContent: Boolean = false,
    ): PaginatedBookmarksDto

    @GET("bookmarks/{id}")
    suspend fun getBookmark(
        @Path("id") id: String,
        @Query("includeContent") includeContent: Boolean = true,
    ): BookmarkDto

    @GET("bookmarks/search")
    suspend fun searchBookmarks(
        @Query("q") query: String,
        @Query("sortOrder") sortOrder: String = "relevance",
        @Query("limit") limit: Int = DEFAULT_PAGE_SIZE,
        @Query("cursor") cursor: String? = null,
        @Query("includeContent") includeContent: Boolean = false,
    ): PaginatedBookmarksDto

    @PATCH("bookmarks/{id}")
    suspend fun updateBookmark(
        @Path("id") id: String,
        @Body body: UpdateBookmarkRequest,
    ): BookmarkDto

    @POST("bookmarks")
    suspend fun createBookmark(@Body body: CreateBookmarkRequest): BookmarkDto

    @DELETE("bookmarks/{id}")
    suspend fun deleteBookmark(@Path("id") id: String)

    // --- Lists ---

    @GET("lists")
    suspend fun getLists(): ListsResponseDto

    @GET("lists/{id}")
    suspend fun getList(@Path("id") id: String): ListDto

    @GET("lists/{id}/bookmarks")
    suspend fun getListBookmarks(
        @Path("id") id: String,
        @Query("sortOrder") sortOrder: String = "desc",
        @Query("limit") limit: Int = DEFAULT_PAGE_SIZE,
        @Query("cursor") cursor: String? = null,
        @Query("includeContent") includeContent: Boolean = false,
    ): PaginatedBookmarksDto

    @PUT("lists/{listId}/bookmarks/{bookmarkId}")
    suspend fun addBookmarkToList(
        @Path("listId") listId: String,
        @Path("bookmarkId") bookmarkId: String,
    )

    @DELETE("lists/{listId}/bookmarks/{bookmarkId}")
    suspend fun removeBookmarkFromList(
        @Path("listId") listId: String,
        @Path("bookmarkId") bookmarkId: String,
    )

    // --- Tags ---

    @GET("tags")
    suspend fun getTags(): TagsResponseDto

    @GET("tags/{id}/bookmarks")
    suspend fun getTagBookmarks(
        @Path("id") id: String,
        @Query("limit") limit: Int = DEFAULT_PAGE_SIZE,
        @Query("cursor") cursor: String? = null,
        @Query("includeContent") includeContent: Boolean = false,
    ): PaginatedBookmarksDto

    // --- Highlights ---

    @GET("highlights")
    suspend fun getAllHighlights(
        @Query("limit") limit: Int = DEFAULT_PAGE_SIZE,
        @Query("cursor") cursor: String? = null,
    ): PaginatedHighlightsDto

    @GET("bookmarks/{id}/highlights")
    suspend fun getBookmarkHighlights(@Path("id") id: String): PaginatedHighlightsDto

    @POST("highlights")
    suspend fun createHighlight(@Body body: CreateHighlightRequest): HighlightDto

    @PATCH("highlights/{id}")
    suspend fun updateHighlight(
        @Path("id") id: String,
        @Body body: UpdateHighlightRequest,
    ): HighlightDto

    @DELETE("highlights/{id}")
    suspend fun deleteHighlight(@Path("id") id: String)

    // --- Assets (binary) ---

    @Streaming
    @GET("assets/{id}")
    suspend fun getAsset(@Path("id") id: String): ResponseBody

    companion object {
        const val DEFAULT_PAGE_SIZE = 30
    }
}
