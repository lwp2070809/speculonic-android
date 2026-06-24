package de.lwp2070809.speculonic

import android.app.Application
import android.content.Context
import android.os.storage.StorageManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import dagger.hilt.android.HiltAndroidApp
import de.lwp2070809.speculonic.data.CacheSyncWorker
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.data.MetadataSyncWorker
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.di.NetworkModule
import de.lwp2070809.speculonic.util.SubsonicCoverArtFetcher
import de.lwp2070809.speculonic.util.SubsonicCoverArtKeyer
import de.lwp2070809.speculonic.util.SubsonicCoverArtStringKeyer
import javax.inject.Inject

@HiltAndroidApp
class SpeculonicApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    companion object {
        lateinit var instance: SpeculonicApp
            private set
        
        val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        
        val prefsManager = PreferencesManager.getInstance(this)
        val trustAll = prefsManager.getTrustAllCertificatesSync()
        if (trustAll) {
            NetworkModule.rebuildClientIfNeeded(true)
        }
        
        
        
        
        
        NetworkModule.provideOkHttpClient()
        
        
        DownloadTracker.init(this)
        
        
        CacheSyncWorker.schedule(this)
        
        
        MetadataSyncWorker.schedule(this)
    }

    
    override fun newImageLoader(context: Context): ImageLoader {
        val cacheDir = context.cacheDir
        val storageManager = context.getSystemService(StorageManager::class.java)
        val usableSpace = try {
            storageManager.getAllocatableBytes(StorageManager.UUID_DEFAULT)
        } catch (e: Exception) {
            cacheDir.usableSpace
        }
        val imageCacheLimit = (usableSpace * 0.05).toLong().coerceIn(256 * 1024 * 1024L, 1024 * 1024 * 1024L)

        return ImageLoader.Builder(context)
            .components {
                
                add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { NetworkModule.provideOkHttpClient() }))
                add(SubsonicCoverArtKeyer())
                add(SubsonicCoverArtStringKeyer())
                
                add(SubsonicCoverArtFetcher.Factory(context))
                
                add(SvgDecoder.Factory())
                
                add(AnimatedImageDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(imageCacheLimit) 
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
