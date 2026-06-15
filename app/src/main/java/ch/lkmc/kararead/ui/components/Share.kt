package ch.lkmc.kararead.ui.components

import android.content.Context
import android.content.Intent

/** Fire the system share sheet with [text] (and an optional [subject]). */
fun shareText(context: Context, text: String, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share")) }
}
