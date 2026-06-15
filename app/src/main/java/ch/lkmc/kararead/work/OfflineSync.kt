package ch.lkmc.kararead.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ch.lkmc.kararead.data.model.OfflinePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules the offline prefetch worker according to the user's preferences. */
@Singleton
class OfflineSync @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** (Re)schedule (or cancel) periodic prefetch to match [prefs]. */
    fun apply(prefs: OfflinePreferences) {
        if (!prefs.enabled) {
            workManager.cancelUniqueWork(OfflinePrefetchWorker.PERIODIC_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<OfflinePrefetchWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints(prefs.wifiOnly))
            .build()
        workManager.enqueueUniquePeriodicWork(
            OfflinePrefetchWorker.PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Run a prefetch now (used by "Download now" and on enabling offline mode). */
    fun runNow() {
        val request = OneTimeWorkRequestBuilder<OfflinePrefetchWorker>()
            .setConstraints(constraints(wifiOnly = false))
            .build()
        workManager.enqueueUniqueWork(
            OfflinePrefetchWorker.ONESHOT_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()
}
