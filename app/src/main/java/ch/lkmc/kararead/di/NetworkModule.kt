package ch.lkmc.kararead.di

import android.content.Context
import ch.lkmc.kararead.data.remote.ApiProvider
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * A Coil [ImageLoader] that attaches the Karakeep bearer token to image
     * requests aimed at the configured server (so cached asset images load),
     * while leaving third-party remote images untouched.
     */
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        apiProvider: ApiProvider,
    ): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val auth = apiProvider.authHeaderForUrl(request.url.toString())
                val newRequest = if (auth != null) {
                    request.newBuilder().header("Authorization", auth).build()
                } else {
                    request
                }
                chain.proceed(newRequest)
            }
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(client)
            .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(80L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
