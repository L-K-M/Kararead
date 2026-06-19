package ch.lkmc.kararead.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the outbox flush: replay queued offline archive/favourite changes
 * once the device is back online. Backed by WorkManager so it survives the app
 * being closed and waits for connectivity on its own.
 */
@Singleton
class PendingOpSync @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /**
     * Ask for a flush as soon as there's a connection. Replaces any pending
     * request so a fresh run always sees the full, latest queue; the flush
     * itself is idempotent (each op is dropped only once the server confirms).
     */
    fun schedule() {
        val request = OneTimeWorkRequestBuilder<PendingOpWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            PendingOpWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
