package de.lwp2070809.speculonic

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import de.lwp2070809.speculonic.data.AppRuntimeCoordinator
import de.lwp2070809.speculonic.data.CoverArtSyncWorker
import de.lwp2070809.speculonic.data.CacheSyncWorker
import de.lwp2070809.speculonic.data.DownloadManagerHelper
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.data.MetadataSyncWorker
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.di.NetworkModule
import de.lwp2070809.speculonic.ui.widget.SpeculonicWidgetUpdater
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LogTag
import de.lwp2070809.speculonic.util.SubsonicCoverArtFetcher
import de.lwp2070809.speculonic.util.SubsonicCoverArtKeyer
import de.lwp2070809.speculonic.util.SubsonicCoverArtStringKeyer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SpeculonicApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    companion object {
        lateinit var instance: SpeculonicApp
            private set

        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private const val MEMORY_CACHE_PERCENT = 0.25
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var musicDao: MusicDao

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var downloadTracker: DownloadTracker

    @Inject
    lateinit var downloadManagerHelper: DownloadManagerHelper

    @Inject
    lateinit var appRuntimeCoordinator: AppRuntimeCoordinator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        LogManager.setupCrashHandler(this)

        // 必须在初次构造 OkHttpClient 与调度 Worker 前同步应用不安全连接设置，防止首帧请求错用默认 TLS 客户端
        val trustAll = preferencesManager.getAllowInsecureConnectionsSync()
        LogManager.i(
            LogTag.APP,
            "SpeculonicApp: Security settings loaded (Version: ${BuildConfig.VERSION_NAME}, " +
                "Code: ${BuildConfig.VERSION_CODE}, TrustAll: $trustAll)"
        )
        if (trustAll) {
            NetworkModule.rebuildClientIfNeeded(true)
        }

        applicationScope.launch(Dispatchers.IO) {
            downloadManagerHelper.initializeAsync()
            SpeculonicWidgetUpdater.reconcileStalePlayingState(this@SpeculonicApp)
        }

        NetworkModule.provideOkHttpClient()
        downloadTracker.init()
        appRuntimeCoordinator.start()
        CacheSyncWorker.schedule(this)
        CoverArtSyncWorker.schedule(this)
        MetadataSyncWorker.schedule(this)
    }

    override fun newImageLoader(context: Context): ImageLoader {
        val maxCoverLimit = preferencesManager.getCachedMaxCoverCacheSize()

        return ImageLoader.Builder(context)
            .components {
                add(SubsonicCoverArtKeyer())
                add(SubsonicCoverArtStringKeyer())
                add(de.lwp2070809.speculonic.util.EmbeddedCoverFallback)
                add(SubsonicCoverArtFetcher.Factory(context, musicDao))
                add(OkHttpNetworkFetcherFactory(callFactory = { NetworkModule.provideOkHttpClient() }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, MEMORY_CACHE_PERCENT)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(maxCoverLimit)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
