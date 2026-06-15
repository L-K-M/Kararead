package ch.lkmc.kararead

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ch.lkmc.kararead.data.prefs.SettingsRepository
import ch.lkmc.kararead.data.remote.ApiProvider
import ch.lkmc.kararead.work.CacheCleanupWorker
import ch.lkmc.kararead.work.OfflineSync
import java.util.concurrent.TimeUnit
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class KararreadApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var apiProvider: ApiProvider
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var offlineSync: OfflineSync

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Keep the API client in sync with stored connection settings.
        appScope.launch {
            settingsRepository.connection
                .onEach { apiProvider.configure(it) }
                .collect {}
        }
        // Keep offline prefetch scheduled to match the user's preferences.
        appScope.launch {
            settingsRepository.offlinePreferences
                .onEach { offlineSync.apply(it) }
                .collect {}
        }
        scheduleCacheCleanup()
    }

    /** Trim the offline article cache periodically so it can't grow forever. */
    private fun scheduleCacheCleanup() {
        val request = PeriodicWorkRequestBuilder<CacheCleanupWorker>(
            CacheCleanupWorker.INTERVAL_HOURS, TimeUnit.HOURS,
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            CacheCleanupWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader = imageLoader
}
