package ch.lkmc.kararead.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import ch.lkmc.kararead.data.model.prettyHost
import java.io.File

/**
 * Renders a highlighted quote into a small, shareable image card — the article's
 * best line, set on warm paper with a tidy attribution, ready for the system
 * share sheet (I6 "Quote cards"). The text composition is factored into pure
 * helpers so it can be unit-tested without an Android canvas.
 */
object QuoteCard {

    /** Fixed card width in pixels; height grows to fit the quote. */
    private const val WIDTH = 1080
    private const val PADDING = 84f
    private const val CONTENT_WIDTH = (WIDTH - 2 * PADDING).toInt()

    // A calm, consistent palette so every card looks of a piece.
    private const val PAPER = 0xFFFBF7EF.toInt()
    private const val INK = 0xFF2A2620.toInt()
    private const val MUTED = 0xFF6A6259.toInt()
    private const val FAINT = 0xFF9A9186.toInt()
    private const val ACCENT = 0xFFBE7B37.toInt()

    /**
     * Wrap the raw highlight into the quote body: trimmed, inner runs of
     * whitespace collapsed, and framed with typographic quotation marks (unless
     * the text already opens with one).
     */
    fun quoteBody(raw: String?): String {
        val cleaned = raw.orEmpty().replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return ""
        val opensQuoted = cleaned.first() == '“' || cleaned.first() == '"'
        return if (opensQuoted) cleaned else "“$cleaned”"
    }

    /**
     * The attribution line shown under a quote: the article title, and the site
     * (from [url]) when it adds something the title doesn't already say.
     */
    fun attribution(title: String?, url: String?): String {
        val name = title?.trim().orEmpty().ifEmpty { "Untitled" }
        val site = url?.takeIf { it.isNotBlank() }?.let { prettyHost(it) }?.trim().orEmpty()
        return if (site.isNotEmpty() && !name.equals(site, ignoreCase = true)) {
            "$name · $site"
        } else {
            name
        }
    }

    /** A filesystem-safe file name for the cached card of a given highlight id. */
    fun fileName(highlightId: String): String {
        val safe = highlightId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .ifEmpty { "quote" }
        return "quote-$safe.png"
    }

    /**
     * Render and cache a quote card, returning a shareable content URI (or null
     * if there's nothing to quote). Safe to call off the main thread.
     */
    fun render(
        context: Context,
        highlightId: String,
        quote: String?,
        title: String?,
        url: String?,
    ): Uri? {
        val body = quoteBody(quote)
        if (body.isEmpty()) return null
        val attribution = attribution(title, url)

        val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = 52f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val attributionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED
            textSize = 34f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val quoteLayout = staticLayout(body, quotePaint, CONTENT_WIDTH)
        val attributionLayout = staticLayout(attribution, attributionPaint, CONTENT_WIDTH)

        // Vertical rhythm: opening glyph, quote, a rule, the attribution, then the
        // wordmark footer — all inside the page padding.
        val glyphTop = PADDING
        val glyphHeight = 120f
        val quoteTop = glyphTop + glyphHeight
        val ruleTop = quoteTop + quoteLayout.height + 44f
        val attributionTop = ruleTop + 40f
        val footerTop = attributionTop + attributionLayout.height + 56f
        val height = (footerTop + 40f + PADDING).toInt()

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER)

        // Oversized opening quotation mark as a soft watermark.
        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            textSize = 200f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            alpha = 60
        }
        canvas.drawText("“", PADDING - 12f, glyphTop + 150f, glyphPaint)

        canvas.withTranslation(PADDING, quoteTop) { quoteLayout.draw(this) }

        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            strokeWidth = 4f
        }
        canvas.drawLine(PADDING, ruleTop, PADDING + 96f, ruleTop, rulePaint)

        canvas.withTranslation(PADDING, attributionTop) { attributionLayout.draw(this) }

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FAINT
            textSize = 28f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            letterSpacing = 0.15f
        }
        canvas.drawText("KARAREAD", PADDING, footerTop + 24f, footerPaint)

        return writeToCache(context, bitmap, fileName(highlightId))
    }

    private fun staticLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(8f, 1.1f)
            .setIncludePad(false)
            .build()

    private fun writeToCache(context: Context, bitmap: Bitmap, name: String): Uri? = runCatching {
        val dir = File(context.cacheDir, "quote_cards").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}

/** Small Canvas helper mirroring Compose's withTranslation, kept local to the renderer. */
private inline fun Canvas.withTranslation(dx: Float, dy: Float, block: Canvas.() -> Unit) {
    val save = save()
    translate(dx, dy)
    try {
        block()
    } finally {
        restoreToCount(save)
    }
}
