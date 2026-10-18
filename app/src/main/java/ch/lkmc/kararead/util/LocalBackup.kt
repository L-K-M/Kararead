package ch.lkmc.kararead.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A portable snapshot of Kararead's *local-only* data — reading progress and
 * the per-day reading tally that powers streaks and stats. None of this lives
 * on the Karakeep server, so without a backup it's lost with the device (H9).
 *
 * The offline article cache and the sync outbox are deliberately excluded: the
 * cache is re-fetchable and large, and outbox ops are transient, per-account
 * state that must never be replayed against a different server.
 */
@Serializable
data class LocalBackup(
    val version: Int = FORMAT_VERSION,
    val exportedAt: Long = 0L,
    val progress: List<ProgressEntry> = emptyList(),
    val readingDays: List<ReadingDayEntry> = emptyList(),
) {
    val itemCount: Int get() = progress.size + readingDays.size

    companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
data class ProgressEntry(
    val bookmarkId: String,
    val fraction: Float,
    val updatedAt: Long,
    val anchor: String? = null,
)

@Serializable
data class ReadingDayEntry(
    val date: String,
    val seconds: Long,
    val updatedAt: Long,
)

/** JSON encode/decode for [LocalBackup], tolerant of unknown/newer fields. */
object LocalBackupCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(backup: LocalBackup): String = json.encodeToString(backup)

    /**
     * Parse a backup document, or null if it isn't valid JSON or doesn't carry
     * a recognizable version — so a wrong file picked at import time is refused
     * rather than silently importing nothing.
     */
    fun decode(text: String): LocalBackup? = runCatching {
        json.decodeFromString<LocalBackup>(text).takeIf { it.version >= 1 }
    }.getOrNull()
}
