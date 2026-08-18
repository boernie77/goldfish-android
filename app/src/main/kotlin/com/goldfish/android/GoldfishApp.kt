package com.goldfish.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import dagger.hilt.android.HiltAndroidApp
import com.goldfish.android.data.api.ApiClientProvider
import javax.inject.Inject

@HiltAndroidApp
class GoldfishApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var apiClientProvider: ApiClientProvider

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                // 30 % des verfügbaren App-Heap-Speichers für Bild-RAM-Cache
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.30)
                    .build()
            }
            .diskCache {
                // 1 GB Disk-Cache für Poster, Trickplay-Sprites, TMDB-Bilder
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil_image_cache").toOkioPath())
                    .maxSizeBytes(1024L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { apiClientProvider.okHttpClient }))
            }
            .build()
    }
}
