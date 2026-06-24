package de.lwp2070809.speculonic.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.di.NetworkModule
import de.lwp2070809.speculonic.domain.repository.AuthManager
import de.lwp2070809.speculonic.domain.repository.EntityMapper
import de.lwp2070809.speculonic.domain.repository.LyricsRepository
import de.lwp2070809.speculonic.domain.repository.UrlBuilder
import de.lwp2070809.speculonic.util.LogManager
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(UnstableApi::class)
object DownloadTracker {
    private val _downloadedSongIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedSongIds = _downloadedSongIds.asStateFlow()

    private val _activeDownloadIds = MutableStateFlow<Set<String>>(emptySet())
    val activeDownloadIds = _activeDownloadIds.asStateFlow()

    private val _allDownloads = MutableStateFlow<List<Download>>(emptyList())
    val allDownloadsFlow = _allDownloads.asStateFlow()

    private val isInitialized = AtomicBoolean(false)
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entityMapper = EntityMapper

    private val pollJob = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)

    fun clearAll() {
        _downloadedSongIds.value = emptySet()
        _activeDownloadIds.value = emptySet()
        _allDownloads.value = emptyList()
    }

    fun init(context: Context) {
        if (isInitialized.getAndSet(true)) return

        scope.launch {
            val downloadManager = DownloadManagerHelper.getDownloadManagerSuspend(context)
            downloadManager.addListener(DownloadManagerListener(context))
            loadDownloads(downloadManager)
        }
    }

    private fun loadDownloads(downloadManager: DownloadManager) {
        updateAllDownloads(downloadManager)
    }

    private fun updateAllDownloads(downloadManager: DownloadManager) {
        val all = mutableListOf<Download>()
        val downloaded = mutableSetOf<String>()
        val active = mutableSetOf<String>()
        val currentActiveDownloads = downloadManager.currentDownloads
        val currentActiveMap = currentActiveDownloads.associateBy { it.request.id }
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val dbDownload = cursor.download
                val download = currentActiveMap[dbDownload.request.id] ?: dbDownload
                all.add(download)
                if (download.state == Download.STATE_COMPLETED) {
                    downloaded.add(download.request.id)
                } else if (download.state == Download.STATE_DOWNLOADING || download.state == Download.STATE_QUEUED) {
                    if (!isSilentDownload(download)) {
                        active.add(download.request.id)
                    }
                }
            }
        }
        _downloadedSongIds.value = downloaded
        _activeDownloadIds.value = active
        _allDownloads.value = all
        checkAndStartPolling(downloadManager)
    }

    private fun checkAndStartPolling(downloadManager: DownloadManager) {
        val hasActive = _allDownloads.value.any { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        if (hasActive) {
            val currentJob = pollJob.get()
            if (currentJob == null || !currentJob.isActive) {
                val newJob = scope.launch {
                    while (true) {
                        kotlinx.coroutines.delay(1000)
                        updateAllDownloads(downloadManager)
                    }
                }
                if (!pollJob.compareAndSet(currentJob, newJob)) {
                    newJob.cancel()
                }
            }
        } else {
            pollJob.getAndSet(null)?.cancel()
        }
    }

    private fun isSilentDownload(download: Download): Boolean {
        return try {
            if (download.request.data.isNotEmpty()) {
                val json = JSONObject(Util.fromUtf8Bytes(download.request.data))
                json.optBoolean("isSilent", false)
            } else false
        } catch (e: Exception) {
            false
        }
    }

    private class DownloadManagerListener(private val context: Context) : DownloadManager.Listener {
        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            val isSilent = isSilentDownload(download)

            when (download.state) {
                Download.STATE_COMPLETED -> {
                    _downloadedSongIds.update { it + download.request.id }
                    _activeDownloadIds.update { it - download.request.id }
                    LogManager.i("DownloadTracker: Download completed for ${download.request.id}. Silent: $isSilent")
                    
                    scope.launch {
                        exportDownloadedSong(context, download)
                    }
                }
                Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> {
                    if (!isSilentDownload(download)) {
                        _activeDownloadIds.update { it + download.request.id }
                    } else {
                        _activeDownloadIds.update { it - download.request.id }
                    }
                    _downloadedSongIds.update { it - download.request.id }
                }
                Download.STATE_FAILED, Download.STATE_REMOVING, Download.STATE_STOPPED -> {
                    _activeDownloadIds.update { it - download.request.id }
                    _downloadedSongIds.update { it - download.request.id }
                }
                else -> {
                    _activeDownloadIds.update { it - download.request.id }
                }
            }
            updateAllDownloads(downloadManager)
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            _downloadedSongIds.update { it - download.request.id }
            _activeDownloadIds.update { it - download.request.id }
            updateAllDownloads(downloadManager)
            
            scope.launch {
                val db = AppDatabase.getDatabase(context)
                val songEntity = db.musicDao().getSongById(download.request.id)
                var keepCacheStatus = false
                if (songEntity?.localUri != null && songEntity.isFullyCached) {
                    val uri = android.net.Uri.parse(songEntity.localUri)
                    val exists = if (songEntity.localUri.startsWith("file:")) {
                        val path = uri.path
                        path != null && java.io.File(path).exists()
                    } else {
                        try {
                            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                        } catch (e: Exception) {
                            false
                        }
                    }
                    if (exists) {
                        keepCacheStatus = true
                    }
                }

                if (!keepCacheStatus) {
                    db.musicDao().updateSongCacheStatus(download.request.id, null, false)
                    
                    val songId = download.request.id
                    try {
                        CacheManager.getPlaybackCache(context).removeResource(songId)
                    } catch (e: Exception) {
                        LogManager.e("DownloadTracker: Failed to clean playback cache on remove", e)
                    }
                    
                    try {
                        val maxCacheSize = PreferencesManager.getInstance(context).maxCacheSize.first()
                        val downloadCache = CacheManager.getDownloadCache(context, maxCacheSize)
                        downloadCache.removeResource(songId)
                    } catch (e: Exception) {
                        LogManager.e("DownloadTracker: Failed to clean persistent download cache on remove", e)
                    }
                } else {
                    LogManager.i("DownloadTracker: Keep cache status for " + download.request.id)
                }
            }
        }

        private suspend fun exportDownloadedSong(context: Context, download: Download) {
            val preferencesManager = PreferencesManager.getInstance(context)
            val cacheLocation = preferencesManager.cacheLocation.first()
            val isSafEnabled = cacheLocation.isNotBlank()

            val isSilent = isSilentDownload(download)
            LogManager.d("DownloadTracker: Preparing export for ${download.request.id}. Silent: $isSilent")

            val maxCacheSize = preferencesManager.maxCacheSize.first()
            
            val cache = CacheManager.getDownloadCache(context, maxCacheSize)
            val httpDataSourceFactory = OkHttpDataSource.Factory(NetworkModule.provideOkHttpClient())
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(httpDataSourceFactory)

            val db = AppDatabase.getDatabase(context)
            val songEntity = db.musicDao().getSongById(download.request.id) ?: return
            val song = entityMapper.toSong(songEntity)
            
            var lyrics: String? = null
            var coverArtBytes: ByteArray? = null
            
            try {
                val baseUrl = preferencesManager.serverUrl.first()
                val user = preferencesManager.username.first()
                val pass = preferencesManager.password.first()
                
                if (baseUrl.isNotBlank() && user.isNotBlank()) {
                    val api = NetworkModule.provideSubsonicService(baseUrl)
                    val authManager = AuthManager(user, pass.toCharArray())
                    
                    val lyricsRepo = LyricsRepository(context, api, db.musicDao(), authManager)
                    val (rawLyrics, _) = lyricsRepo.getLyricsData(song.id, song.artist, song.title, true)
                    lyrics = rawLyrics
                    
                    if (!song.coverArt.isNullOrBlank()) {
                        val urlBuilder = UrlBuilder(baseUrl, authManager)
                        val coverUrl = urlBuilder.buildCoverArtUrl(song.coverArt)
                        val imageLoader = SingletonImageLoader.get(context)
                        val request = coil3.request.ImageRequest.Builder(context)
                            .data(coverUrl)
                            .build()
                        val result = imageLoader.execute(request)
                        if (result is SuccessResult) {
                            val bitmap = (result.image as? BitmapImage)?.bitmap
                            if (bitmap != null) {
                                val bos = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                                coverArtBytes = bos.toByteArray()
                                LogManager.d("DownloadTracker: Cover art fetched for export")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                LogManager.e("DownloadTracker: Failed to fetch additional metadata for export", e)
            }

            val localUriResult = try {
                if (isSafEnabled) {
                    CacheExporter.exportToSaf(
                        context,
                        song,
                        lyrics,
                        coverArtBytes,
                        cacheDataSourceFactory
                    )
                } else {
                    CacheExporter.exportToPrivate(
                        context,
                        song,
                        lyrics,
                        coverArtBytes,
                        cacheDataSourceFactory
                    )
                }
            } catch (e: SecurityException) {
                LogManager.e("DownloadTracker: SAF permission expired during export", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(de.lwp2070809.speculonic.R.string.saf_permission_expired), Toast.LENGTH_LONG).show()
                }
                kotlin.Result.failure(e)
            }

            localUriResult.onSuccess { localUri ->
                db.musicDao().updateSongCacheStatus(download.request.id, localUri, true)
                LogManager.i("DownloadTracker: Song ${download.request.id} exported to ${if (isSafEnabled) "SAF" else "Private"} and database status marked: $localUri")
                
                
                LogManager.i("DownloadTracker: Cleaning up internal cache for exported song ${download.request.id}")
                cache.removeResource(download.request.id)
                try {
                    CacheManager.getPlaybackCache(context).removeResource(download.request.id)
                } catch (e: Exception) {
                    LogManager.e("DownloadTracker: Failed to clean playback cache after export", e)
                }
            }.onFailure {
                LogManager.e("DownloadTracker: Export failed for ${song.title}: ${it.message}")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "导出失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
