package ch.lkmc.kararead.data.remote

import ch.lkmc.kararead.data.local.CachedArticleEntity
import ch.lkmc.kararead.data.model.Bookmark
import ch.lkmc.kararead.data.model.ContentType
import ch.lkmc.kararead.data.model.Highlight
import ch.lkmc.kararead.data.model.KarakeepList
import ch.lkmc.kararead.data.model.ReaderArticle
import ch.lkmc.kararead.data.model.Tag
import ch.lkmc.kararead.data.remote.dto.BookmarkDto
import ch.lkmc.kararead.data.remote.dto.ContentDto
import ch.lkmc.kararead.data.remote.dto.HighlightDto
import ch.lkmc.kararead.data.remote.dto.ListDto
import ch.lkmc.kararead.data.remote.dto.TagDto
import ch.lkmc.kararead.util.estimateReadingMinutes
import ch.lkmc.kararead.util.excerptFrom
import ch.lkmc.kararead.util.htmlToPlainText
import ch.lkmc.kararead.util.parseIsoToMillis

/**
 * DTO → domain mappers. Asset references are resolved to absolute URLs via the
 * supplied [assetUrl] resolver (backed by [ApiProvider]).
 */

fun BookmarkDto.toDomain(assetUrl: (String) -> String?): Bookmark {
    val link = content as? ContentDto.Link
    val text = content as? ContentDto.Text
    val asset = content as? ContentDto.Asset

    val resolvedImage = link?.imageAssetId?.let { assetUrl(it) }
        ?: link?.imageUrl
    val resolvedFavicon = link?.favicon

    val url = link?.url ?: text?.sourceUrl ?: asset?.sourceUrl
    val derivedExcerpt = link?.description
        ?: summary
        ?: excerptFrom(htmlToPlainText(link?.htmlContent) ?: text?.text)

    return Bookmark(
        id = id,
        title = (title?.takeIf { it.isNotBlank() } ?: link?.title ?: text?.text?.lineSequence()?.firstOrNull())
            ?.trim().orEmpty(),
        url = url,
        siteName = link?.publisher,
        author = link?.author,
        excerpt = derivedExcerpt,
        faviconUrl = resolvedFavicon,
        imageUrl = resolvedImage,
        createdAt = parseIsoToMillis(createdAt) ?: 0L,
        datePublished = parseIsoToMillis(link?.datePublished),
        archived = archived,
        favourited = favourited,
        tags = tags.map { it.name },
        note = note,
        summary = summary,
        readingTimeMinutes = estimateReadingMinutes(htmlToPlainText(link?.htmlContent) ?: text?.text),
        contentType = when (content) {
            is ContentDto.Link -> ContentType.LINK
            is ContentDto.Text -> ContentType.TEXT
            is ContentDto.Asset -> ContentType.ASSET
            else -> ContentType.UNKNOWN
        },
    )
}

fun BookmarkDto.toReaderArticle(assetUrl: (String) -> String?): ReaderArticle {
    val link = content as? ContentDto.Link
    val text = content as? ContentDto.Text
    val html = link?.htmlContent
    val plain = htmlToPlainText(html) ?: text?.text
    return ReaderArticle(
        bookmark = toDomain(assetUrl),
        htmlContent = html ?: text?.text?.let { plainTextToHtml(it) },
        textContent = plain,
    )
}

/**
 * Turn a plain-text note ([ContentDto.Text]) into safe reader HTML.
 *
 * Crucially this HTML-escapes the text first: an un-escaped `<`, `>` or `&`
 * would be parsed as markup by the reader's Jsoup sanitizer and then silently
 * stripped — dropping the user's own words (e.g. "a < b", "I love <html>").
 * Blank-line-separated paragraphs become `<p>` blocks and single newlines
 * become `<br>`, so the note keeps its shape.
 */
internal fun plainTextToHtml(text: String): String {
    val escaped = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    return escaped
        .split(Regex("\\n{2,}"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("") { "<p>${it.replace("\n", "<br>")}</p>" }
        .ifEmpty { "<p></p>" }
}

fun ReaderArticle.toCacheEntity(now: Long): CachedArticleEntity = CachedArticleEntity(
    bookmarkId = bookmark.id,
    title = bookmark.displayTitle,
    url = bookmark.url,
    siteName = bookmark.siteName,
    author = bookmark.author,
    excerpt = bookmark.excerpt,
    imageUrl = bookmark.imageUrl,
    faviconUrl = bookmark.faviconUrl,
    html = htmlContent,
    text = textContent,
    createdAt = bookmark.createdAt,
    datePublished = bookmark.datePublished,
    readingTimeMinutes = bookmark.readingTimeMinutes,
    archived = bookmark.archived,
    favourited = bookmark.favourited,
    cachedAt = now,
)

fun CachedArticleEntity.toReaderArticle(): ReaderArticle = ReaderArticle(
    bookmark = Bookmark(
        id = bookmarkId,
        title = title,
        url = url,
        siteName = siteName,
        author = author,
        excerpt = excerpt,
        faviconUrl = faviconUrl,
        imageUrl = imageUrl,
        createdAt = createdAt,
        datePublished = datePublished,
        archived = archived,
        favourited = favourited,
        tags = emptyList(),
        note = null,
        summary = null,
        readingTimeMinutes = readingTimeMinutes,
        contentType = ContentType.LINK,
    ),
    htmlContent = html,
    textContent = text,
)

fun ListDto.toDomain(): KarakeepList = KarakeepList(
    id = id,
    name = name,
    icon = icon,
    description = description,
    type = type,
    parentId = parentId,
)

fun TagDto.toDomain(): Tag = Tag(id = id, name = name, count = numBookmarks)

fun HighlightDto.toDomain(): Highlight = Highlight(
    id = id,
    bookmarkId = bookmarkId,
    startOffset = startOffset,
    endOffset = endOffset,
    color = color,
    text = text,
    note = note,
)
