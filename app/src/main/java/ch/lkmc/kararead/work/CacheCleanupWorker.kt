package ch.lkmc.kararead.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.lkmc.kararead.data.local.CachedArticleDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodically trims the offline article cache so it doesn't grow without bound.
 * Articles untouched for longer than [RETENTION] are dropped; reopening one
 * simply re-fetches and re-caches it.
 */
@HiltWorker
class CacheCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val cacheDao: CachedArticleDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        return runCatching { cacheDao.deleteOlderThan(cutoff) }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        const val UNIQUE_NAME = "cache-cleanup"
        /** A cached article survives 30 days without being reopened. */
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(30)
        /** How often the trim runs. */
        val INTERVAL_HOURS: Long = TimeUnit.DAYS.toHours(1)
    }
}
