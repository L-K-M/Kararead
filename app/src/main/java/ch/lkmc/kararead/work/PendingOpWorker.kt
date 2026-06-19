package ch.lkmc.kararead.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.lkmc.kararead.data.repository.KarakeepRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Replays the offline outbox — archive/favourite changes the user made while
 * disconnected — against the server. Runs only when online (a connectivity
 * constraint is set when scheduled); if any op can't be sent yet it asks
 * WorkManager to retry with backoff.
 */
@HiltWorker
class PendingOpWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: KarakeepRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        runCatching { repository.flushPendingOps() }
            .fold(
                onSuccess = { allCleared -> if (allCleared) Result.success() else Result.retry() },
                onFailure = { Result.retry() },
            )

    companion object {
        const val UNIQUE_NAME = "pending-op-flush"
    }
}
