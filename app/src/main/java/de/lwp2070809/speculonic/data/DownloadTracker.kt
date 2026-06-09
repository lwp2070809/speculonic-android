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

    private val isInitialized = AtomicBoolean(false)
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entityMapper = EntityMapper

    fun init(context: Context) {
        if (isInitialized.getAndSet(true)) return

        scope.launch {
            val downloadManager = DownloadManagerHelper.getDownloadManagerSuspend(context)
            downloadManager.addListener(DownloadManagerListener(context))
            loadDownloads(downloadManager)
        }
    }

    private fun loadDownloads(downloadManager: DownloadManager) {
        val downloaded = mutableSetOf<String>()
        val active = mutableSetOf<String>()
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val download = cursor.download
                val isSilent = isSilentDownload(download)
                
                if (download.state == Download.STATE_COMPLETED) {
                    if (!isSilent) downloaded.add(download.request.id)
                } else if (download.state == Download.STATE_DOWNLOADING || download.state == Download.STATE_QUEUED) {
                    if (!isSilent) active.add(download.request.id)
                }
            }
        }
        _downloadedSongIds.value = downloaded
        _activeDownloadIds.value = active
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
                    if (!isSilent) _downloadedSongIds.update { it + download.request.id }
                    _activeDownloadIds.update { it - download.request.id }
                    LogManager.i("DownloadTracker: Download completed for ${download.request.id}. Silent: $isSilent")
                    
                    scope.launch {
                        exportDownloadedSong(context, download)
                    }
                }
                Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> {
                    if (!isSilent) {
                        _activeDownloadIds.update { it + download.request.id }
                    } else {
                        
                        _activeDownloadIds.update { it - download.request.id }
                    }
                    _downloadedSongIds.update { it - download.request.id }
                }
                Download.STATE_FAILED, Download.STATE_REMOVING, Download.STATE_STOPPED -> {
                    _activeDownloadIds.update { it - download.request.id }
                    _downloadedSongIds.update { it - download.request.id }
                    
                    if (download.state == Download.STATE_REMOVING) {
                        scope.launch {
                            val db = AppDatabase.getDatabase(context)
                            db.musicDao().updateSongCacheStatus(download.request.id, null, false)
                        }
                    }
                }
                else -> {
                    _activeDownloadIds.update { it - download.request.id }
                }
            }
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            _downloadedSongIds.update { it - download.request.id }
            _activeDownloadIds.update { it - download.request.id }
            
            scope.launch {
                val db = AppDatabase.getDatabase(context)
                db.musicDao().updateSongCacheStatus(download.request.id, null, false)
                
                val songId = download.request.id
                try {
                    CacheManager.getPlaybackCache(context).removeResource(songId)
                    LogManager.d("DownloadTracker: Cleared playbackCache for cancelled song $songId")
                } catch (e: Exception) {
                    LogManager.e("DownloadTracker: Failed to clean playback cache on remove", e)
                }
                
                try {
                    val maxCacheSize = PreferencesManager.getInstance(context).maxCacheSize.first()
                    val downloadCache = CacheManager.getDownloadCache(context, maxCacheSize)
                    downloadCache.removeResource(songId)
                    LogManager.i("DownloadTracker: Cleared downloadCache for cancelled song $songId (incomplete fragments wiped)")
                } catch (e: Exception) {
                    LogManager.e("DownloadTracker: Failed to clean persistent download cache on remove", e)
                }
            }
        }

        private suspend fun exportDownloadedSong(context: Context, download: Download) {
            val preferencesManager = PreferencesManager.getInstance(context)
            val cacheLocation = preferencesManager.cacheLocation.first()
            
            
            if (cacheLocation.isBlank()) {
                LogManager.d("DownloadTracker: Skipping export as SAF is not configured.")
                return
            }

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

            val localUri = try {
                CacheExporter.exportToSaf(
                    context,
                    song,
                    lyrics,
                    coverArtBytes,
                    cacheDataSourceFactory
                )
            } catch (e: SecurityException) {
                LogManager.e("DownloadTracker: SAF permission expired during export", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(de.lwp2070809.speculonic.R.string.saf_permission_expired), Toast.LENGTH_LONG).show()
                }
                null
            }

            if (localUri != null) {
                db.musicDao().updateSongCacheStatus(download.request.id, localUri, true)
                LogManager.i("DownloadTracker: Song ${download.request.id} exported to SAF and database status marked: $localUri")
                
                
                if (preferencesManager.autoCleanCacheAfterExport.first()) {
                    LogManager.i("DownloadTracker: Cleaning up internal cache for exported song ${download.request.id}")
                    cache.removeResource(download.request.id)
                    try {
                        CacheManager.getPlaybackCache(context).removeResource(download.request.id)
                    } catch (e: Exception) {
                        LogManager.e("DownloadTracker: Failed to clean playback cache after export", e)
                    }
                }
            }
        }
    }
}
