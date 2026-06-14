package ch.lkmc.kararead.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models mapped 1:1 from the Karakeep OpenAPI spec (`/api/v1`).
 * Most fields are nullable per the spec; status enums are kept as raw strings
 * to avoid deserialization failures on unexpected values.
 */

@Serializable
data class PaginatedBookmarksDto(
    val bookmarks: List<BookmarkDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class BookmarkDto(
    val id: String,
    val createdAt: String? = null,
    val modifiedAt: String? = null,
    val title: String? = null,
    val archived: Boolean = false,
    val favourited: Boolean = false,
    val taggingStatus: String? = null,
    val summarizationStatus: String? = null,
    val note: String? = null,
    val summary: String? = null,
    val tags: List<TagRefDto> = emptyList(),
    val content: ContentDto? = null,
    val assets: List<AssetDto> = emptyList(),
)

/**
 * The `content` discriminated union. kotlinx.serialization uses the default
 * `"type"` class discriminator, which matches Karakeep exactly.
 */
@Serializable
sealed class ContentDto {

    @Serializable
    @SerialName("link")
    data class Link(
        val url: String? = null,
        val title: String? = null,
        val description: String? = null,
        val author: String? = null,
        val publisher: String? = null,
        val datePublished: String? = null,
        val dateModified: String? = null,
        val favicon: String? = null,
        val imageUrl: String? = null,
        val imageAssetId: String? = null,
        val screenshotAssetId: String? = null,
        val fullPageArchiveAssetId: String? = null,
        val htmlContent: String? = null,
        val contentAssetId: String? = null,
        val crawledAt: String? = null,
        val crawlStatus: String? = null,
    ) : ContentDto()

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String? = null,
        val sourceUrl: String? = null,
    ) : ContentDto()

    @Serializable
    @SerialName("asset")
    data class Asset(
        val assetType: String? = null,
        val assetId: String? = null,
        val fileName: String? = null,
        val sourceUrl: String? = null,
        val size: Long? = null,
        val content: String? = null,
    ) : ContentDto()

    @Serializable
    @SerialName("unknown")
    data object Unknown : ContentDto()
}

@Serializable
data class TagRefDto(
    val id: String,
    val name: String,
    val attachedBy: String? = null,
)

@Serializable
data class AssetDto(
    val id: String,
    val assetType: String? = null,
    val fileName: String? = null,
)

// --- Lists ---

@Serializable
data class ListsResponseDto(val lists: List<ListDto> = emptyList())

@Serializable
data class ListDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val icon: String = "📚",
    val parentId: String? = null,
    val type: String = "manual",
    val query: String? = null,
    val public: Boolean = false,
)

// --- Tags ---

@Serializable
data class TagsResponseDto(val tags: List<TagDto> = emptyList())

@Serializable
data class TagDto(
    val id: String,
    val name: String,
    val numBookmarks: Int = 0,
)

// --- Highlights ---

@Serializable
data class PaginatedHighlightsDto(
    val highlights: List<HighlightDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class HighlightDto(
    val id: String,
    val bookmarkId: String,
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    val color: String = "yellow",
    val text: String? = null,
    val note: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class CreateHighlightRequest(
    val bookmarkId: String,
    val startOffset: Int,
    val endOffset: Int,
    val color: String = "yellow",
    val text: String? = null,
    val note: String? = null,
)

// --- User ---

@Serializable
data class UserDto(
    val id: String,
    val name: String? = null,
    val email: String? = null,
)

// --- Mutations ---

@Serializable
data class UpdateBookmarkRequest(
    val archived: Boolean? = null,
    val favourited: Boolean? = null,
    val note: String? = null,
    val title: String? = null,
)

@Serializable
data class CreateBookmarkRequest(
    val type: String = "link",
    val url: String,
    val title: String? = null,
)
