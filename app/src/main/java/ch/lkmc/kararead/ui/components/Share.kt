package ch.lkmc.kararead.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Fire the system share sheet with [text] (and an optional [subject]). */
fun shareText(context: Context, text: String, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share")) }
}

/** Fire the system share sheet with an image [uri] (e.g. a rendered quote card). */
fun shareImage(context: Context, uri: Uri, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share quote")) }
}
