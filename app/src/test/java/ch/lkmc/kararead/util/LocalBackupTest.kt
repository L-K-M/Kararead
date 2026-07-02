package ch.lkmc.kararead.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBackupTest {

    private val sample = LocalBackup(
        version = 1,
        exportedAt = 1_700_000_000_000L,
        progress = listOf(
            ProgressEntry("a", 0.5f, 10L, anchor = "3:0.2"),
            ProgressEntry("b", 1.0f, 20L, anchor = null),
        ),
        readingDays = listOf(ReadingDayEntry("2026-06-15", 600L, 30L)),
    )

    @Test
    fun `encode then decode round-trips`() {
        val decoded = LocalBackupCodec.decode(LocalBackupCodec.encode(sample))
        assertEquals(sample, decoded)
    }

    @Test
    fun `itemCount counts progress and days together`() {
        assertEquals(3, sample.itemCount)
    }

    @Test
    fun `decode rejects non-JSON`() {
        assertNull(LocalBackupCodec.decode("not json at all"))
        assertNull(LocalBackupCodec.decode(""))
    }

    @Test
    fun `decode tolerates unknown and newer fields`() {
        val forwardCompatible = """
            {"version":1,"exportedAt":5,"progress":[],"readingDays":[],"somethingNew":true}
        """.trimIndent()
        val decoded = LocalBackupCodec.decode(forwardCompatible)
        assertTrue(decoded != null && decoded.itemCount == 0)
    }

    @Test
    fun `decode refuses a versionless document`() {
        // A stray JSON object that isn't ours parses to version 0 and is refused,
        // so importing the wrong file can't silently wipe or no-op confusingly.
        assertNull(LocalBackupCodec.decode("""{"version":0}"""))
    }
}
