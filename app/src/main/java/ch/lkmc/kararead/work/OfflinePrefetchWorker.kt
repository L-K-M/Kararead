package ch.lkmc.kararead.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.lkmc.kararead.data.model.BookmarkSource
import ch.lkmc.kararead.data.prefs.SettingsRepository
import ch.lkmc.kararead.data.remote.ApiProvider
import ch.lkmc.kararead.data.repository.KarakeepRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Keeps the top unread articles downloaded for offline reading. Runs
 * periodically (and on demand) when offline downloading is enabled.
 */
@HiltWorker
class OfflinePrefetchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val repository: KarakeepRepository,
    private val apiProvider: ApiProvider,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = settings.offlinePreferencesOnce()
        if (!prefs.enabled) return Result.success()

        // The worker process may start before the app wired up the client.
        if (!apiProvider.isConfigured()) {
            apiProvider.configure(settings.connectionOnce())
        }
        if (!apiProvider.isConfigured()) return Result.success() // not signed in yet

        // Mirror the user's reading queue: their read-later list if set, else inbox.
        val readLater = settings.readLaterList.first()
        val source = readLater?.let { BookmarkSource.ListSource(it.first, it.second) }
            ?: BookmarkSource.Inbox

        return runCatching { repository.syncOffline(source, prefs.keepCount) }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        const val PERIODIC_NAME = "offline-prefetch"
        const val ONESHOT_NAME = "offline-prefetch-now"
    }
}
