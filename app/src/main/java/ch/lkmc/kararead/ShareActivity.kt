package ch.lkmc.kararead

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import ch.lkmc.kararead.data.prefs.SettingsRepository
import ch.lkmc.kararead.data.remote.ApiProvider
import ch.lkmc.kararead.data.repository.KarakeepRepository
import ch.lkmc.kararead.di.ApplicationScope
import ch.lkmc.kararead.util.extractFirstUrl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

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

        // Save on the application scope and finish at once. Running the network
        // call in lifecycleScope kept this (invisible, full-screen) activity on
        // top of the sharing app — blocking its touches for as long as a slow
        // server took — and leaving mid-save cancelled the coroutine, silently
        // losing the link.
        appScope.launch {
            val connection = settings.connectionOnce()
            if (!connection.isComplete) {
                toast("Connect Kararead to a server first")
                return@launch
            }
            apiProvider.configure(connection)
            // Save into the configured read-later list, if any.
            val listId = runCatching { settings.readLaterList.first() }.getOrNull()?.first
            val result = runCatching { repository.saveLink(url, listId) }
            val saved = result.getOrNull()
            toast(
                when {
                    saved == null -> "Couldn't save — try again"
                    // The bookmark exists but never joined the user's queue;
                    // pretending full success hid exactly the part they care about.
                    !saved.addedToList -> "Saved — but couldn't add it to your read-later list"
                    else -> "Saved to Karakeep ✓"
                },
            )
        }
        toast("Saving to Karakeep…")
        finish()
    }

    private fun toast(message: String) {
        // Callable from any thread — toasts must be shown from the main looper.
        appScope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
