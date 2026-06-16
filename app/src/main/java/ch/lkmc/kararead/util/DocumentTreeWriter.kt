package ch.lkmc.kararead.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Helpers for saving exports into a user-granted SAF folder (an
 * `OPEN_DOCUMENT_TREE` tree URI) — e.g. a Syncthing-synced directory.
 *
 * Uses [DocumentsContract] directly so no extra dependency (documentfile) is
 * needed. All calls do blocking I/O, so invoke them off the main thread.
 */

// Characters reserved on FAT/exFAT/NTFS/ext filesystems.
private val RESERVED_FILE_CHARS = Regex("[\\\\/:*?\"<>|]")
private val WHITESPACE = Regex("\\s+")

/** Strip filesystem-reserved characters from a file name; fall back to [fallback]. */
private fun safeFileName(name: String, fallback: String): String {
    val cleaned = name
        .replace(RESERVED_FILE_CHARS, "")
        .replace(WHITESPACE, " ") // collapse runs of whitespace (incl. newlines)
        .trim()
        .take(120)
        .trim()
    return cleaned.ifBlank { fallback }
}

/**
 * Write [content] as a Markdown file named after [baseName] into the granted
 * folder [treeUri], overwriting a file of the same name so re-saving keeps a
 * single, up-to-date copy (handy for Syncthing). Returns the saved file's
 * display name on success, or null on any failure (e.g. permission revoked).
 */
fun saveMarkdownToFolder(
    context: Context,
    treeUri: Uri,
    baseName: String,
    content: String,
): String? = runCatching {
    val resolver = context.contentResolver
    val fileName = safeFileName(baseName, "highlights") + ".md"
    val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
    val target = findChildByName(context, treeUri, treeDocId, fileName)
        ?: DocumentsContract.createDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId),
            "text/markdown",
            fileName,
        )
        ?: return null
    // "wt" = truncate then write, so overwriting an existing file replaces it.
    resolver.openOutputStream(target, "wt")?.use { out ->
        out.write(content.toByteArray(Charsets.UTF_8))
    } ?: return null
    fileName
}.getOrNull()

/** The document URI of a direct child of [treeDocId] named [name], or null. */
private fun findChildByName(
    context: Context,
    treeUri: Uri,
    treeDocId: String,
    name: String,
): Uri? = runCatching {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
    context.contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        ),
        null, null, null,
    )?.use { c ->
        while (c.moveToNext()) {
            if (c.getString(1) == name) {
                return@use DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
            }
        }
        null
    }
}.getOrNull()

/** A friendly path for a granted folder, e.g. "Documents/Syncthing/Highlights". */
fun folderDisplayName(treeUriString: String): String = runCatching {
    val docId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUriString))
    docId.substringAfter(':', docId).ifBlank { docId }
}.getOrDefault(treeUriString)
