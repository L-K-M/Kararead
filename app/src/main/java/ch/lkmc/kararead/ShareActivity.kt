package ch.lkmc.kararead

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import ch.lkmc.kararead.data.prefs.SettingsRepository
import ch.lkmc.kararead.data.remote.ApiProvider
import ch.lkmc.kararead.data.repository.KarakeepRepository
import ch.lkmc.kararead.util.extractFirstUrl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Invisible share target: receive a shared URL from any app and save it to
 * Karakeep (optionally into the configured read-later list).
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {

    @Inject lateinit var repository: KarakeepRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var apiProvider: ApiProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
        val url = extractFirstUrl(shared)

        if (url == null) {
            toast("No link found to save")
            finish()
            return
        }

        lifecycleScope.launch {
            val connection = settings.connectionOnce()
            if (!connection.isComplete) {
                toast("Connect Kararead to a server first")
                finish()
                return@launch
            }
            apiProvider.configure(connection)
            // Save into the configured read-later list, if any.
            val listId = runCatching { settings.readLaterList.first() }.getOrNull()?.first
            val result = runCatching { repository.saveLink(url, listId) }
            toast(if (result.isSuccess) "Saved to Karakeep ✓" else "Couldn't save — try again")
            finish()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
