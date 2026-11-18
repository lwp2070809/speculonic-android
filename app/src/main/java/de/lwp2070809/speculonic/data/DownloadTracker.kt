package de.lwp2070809.speculonic.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.repository.EntityMapper
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.util.FormatUtils
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
data class DownloadTaskInfo(
    val task: Download,
    val progress: Float = task.percentDownloaded,
    val state: Int = task.state,
    val isSilent: Boolean = DownloadTracker.isSilentDownload(task)
)

@OptIn(UnstableApi::class)
@Singleton
class DownloadTracker @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferencesManager: PreferencesManager,
    private val database: AppDatabase,
    private val repository: dagger.Lazy<SubsonicRepository>,
    private val cacheExporter: CacheExporter,
    private val cacheManager: CacheManager,
    private val downloadManagerHelper: DownloadManagerHelper
) {
    private val _downloadedSongIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedSongIds = _downloadedSongIds.asStateFlow()

    private val _activeDownloadIds = MutableStateFlow<Set<String>>(emptySet())
    val activeDownloadIds = _activeDownloadIds.asStateFlow()

    private val _allDownloads = MutableStateFlow<List<DownloadTaskInfo>>(emptyList())
    val allDownloadsFlow = _allDownloads.asStateFlow()

    // 仅向下游发布非静默活动下载数量，避免 1Hz 进度变化触发外层 Scaffold 整页重组
    val activeNonSilentCountFlow: Flow<Int> = _allDownloads
        .map { list ->
            list.count { info ->
                !info.isSilent && (info.state == Download.STATE_DOWNLOADING || info.state == Download.STATE_QUEUED)
            }
        }
        .distinctUntilChanged()

    private val isInitialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exportJobs = ConcurrentHashMap<String, Job>()
    private val pendingFileDeletionIds = ConcurrentHashMap.newKeySet<String>()
    private val pollJob = AtomicReference<Job?>(null)

    fun markForFileDeletion(songId: String) {
        pendingFileDeletionIds.add(songId)
    }

    fun consumePendingFileDeletion(songId: String): Boolean {
        return pendingFileDeletionIds.remove(songId)
    }

    fun hasDownload(songId: String): Boolean {
        return _allDownloads.value.any { it.task.request.id == songId }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun deleteExportedSongAndCache(songId: String) = withContext(Dispatchers.IO) {
        val song = database.musicDao().getSongById(songId)
        song?.localUri?.let { uriString ->
            deleteExportedFiles(songId, uriString)
        }
        database.musicDao().updateSongLocalUri(songId, null)
        database.musicDao().updateSongCacheStatus(songId, null, false)
        try {
            cacheManager.acquirePlaybackCache().removeResource(songId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("DownloadTracker: Failed to clean playback cache on remove", e)
        }
        try {
            cacheManager.acquireDownloadCache().removeResource(songId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("DownloadTracker: Failed to clean persistent download cache on remove", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deleteExportedFiles(songId: String, uriString: String) {
        LogManager.d("DownloadTracker: Removing exported file: $uriString")
        val uri = uriString.toUri()
        val docFile = try {
            DocumentFile.fromSingleUri(appContext, uri)
        } catch (e: Exception) {
            LogManager.w("DownloadTracker: Unable to resolve SAF document for $songId: ${e.message}")
            null
        }
        if (docFile?.exists() == true) {
            deleteSafAudioAndCompanionLrc(songId, uriString, docFile)
        } else if (uriString.startsWith("file:")) {
            deletePrivateAudioAndCompanionLrc(songId, uri)
        }
    }

    private fun deleteSafAudioAndCompanionLrc(
        songId: String,
        uriString: String,
        docFile: DocumentFile
    ) {
        val fileName = docFile.name
        val parentDir = docFile.parentFile
        val audioDeleted = docFile.delete()
        if (fileName != null && FormatUtils.isSupportedAudioFile(fileName)) {
            val lrcName = FormatUtils.replaceExtensionWithLrc(fileName)
            val lrcFile = parentDir?.findFile(lrcName)
            if (lrcFile?.exists() == true) {
                lrcFile.delete()
            } else {
                try {
                    val lrcUri = FormatUtils.replaceExtensionWithLrc(uriString).toUri()
                    DocumentFile.fromSingleUri(appContext, lrcUri)?.takeIf { it.exists() }?.delete()
                } catch (e: Exception) {
                    LogManager.w("DownloadTracker: Fallback LRC deletion failed", e)
                }
            }
        }
        if (audioDeleted) {
            LogManager.i("DownloadTracker: Successfully deleted SAF file for $songId")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deletePrivateAudioAndCompanionLrc(songId: String, uri: android.net.Uri) {
        try {
            val path = uri.path ?: return
            val file = File(path)
            if (!file.exists()) return
            file.delete()
            val lrcFile = File(FormatUtils.replaceExtensionWithLrc(file.absolutePath))
            if (lrcFile.exists()) lrcFile.delete()
        } catch (e: Exception) {
            LogManager.e("DownloadTracker: Failed to delete private file for $songId", e)
        }
    }

    fun clearAll() {
        _downloadedSongIds.value = emptySet()
        _activeDownloadIds.value = emptySet()
        _allDownloads.value = emptyList()
        isInitialized.set(false)
        pollJob.getAndSet(null)?.cancel()
    }

    fun init() {
        if (isInitialized.getAndSet(true)) return

        scope.launch {
            val downloadManager = downloadManagerHelper.getDownloadManagerSuspend()
            downloadManager.addListener(DownloadManagerListener())
            refreshDownloads(downloadManager)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun getCurrentDownloadsSafe(downloadManager: DownloadManager): List<Download> {
        return withContext(Dispatchers.Main) {
            try {
                downloadManager.currentDownloads.toList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.w("DownloadTracker: Failed reading currentDownloads: ${e.message}")
                emptyList()
            }
        }
    }

    private suspend fun refreshDownloads(downloadManager: DownloadManager) {
        val all = mutableListOf<DownloadTaskInfo>()
        val downloaded = mutableSetOf<String>()
        val active = mutableSetOf<String>()
        val currentActiveMap = getCurrentDownloadsSafe(downloadManager).associateBy { it.request.id }
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val dbDownload = cursor.download
                val download = currentActiveMap[dbDownload.request.id] ?: dbDownload
                all.add(DownloadTaskInfo(download))
                classifyDownloadStatus(download, downloaded, active)
            }
        }
        _downloadedSongIds.value = downloaded
        _activeDownloadIds.value = active
        _allDownloads.value = all
        checkAndStartPolling(downloadManager)
    }

    private fun classifyDownloadStatus(
        download: Download,
        downloaded: MutableSet<String>,
        active: MutableSet<String>
    ) {
        val id = download.request.id
        when (download.state) {
            Download.STATE_COMPLETED -> downloaded.add(id)
            Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> {
                if (!isSilentDownload(download)) {
                    active.add(id)
                }
            }
        }
    }

    private suspend fun pollActiveDownloadsProgress(downloadManager: DownloadManager) {
        val currentActiveDownloads = getCurrentDownloadsSafe(downloadManager)
        if (currentActiveDownloads.isEmpty()) {
            pollJob.getAndSet(null)?.cancel()
            return
        }

        val currentActiveMap = currentActiveDownloads.associateBy { it.request.id }
        _allDownloads.update { list ->
            list.map { item ->
                val active = currentActiveMap[item.task.request.id]
                if (active != null) DownloadTaskInfo(active) else item
            }
        }
        checkAndStartPolling(downloadManager)
    }

    private fun checkAndStartPolling(downloadManager: DownloadManager) {
        // 仅存在真正下载中的任务时以 1Hz 轮询进度；QUEUED 等待状态转换由 Listener 事件驱动唤醒
        val hasDownloading = _allDownloads.value.any { it.state == Download.STATE_DOWNLOADING }
        if (hasDownloading) {
            val currentJob = pollJob.get()
            if (currentJob == null || !currentJob.isActive) {
                val newJob = scope.launch {
                    while (true) {
                        delay(POLL_INTERVAL_MS)
                        pollActiveDownloadsProgress(downloadManager)
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

    private inner class DownloadManagerListener : DownloadManager.Listener {
        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            val isSilent = isSilentDownload(download)

            when (download.state) {
                Download.STATE_COMPLETED -> {
                    _downloadedSongIds.update { it + download.request.id }
                    _activeDownloadIds.update { it - download.request.id }
                    LogManager.i("DownloadTracker: Download completed for ${download.request.id}. Silent: $isSilent")

                    var job: Job? = null
                    job = scope.launch {
                        try {
                            exportDownloadedSong(download)
                        } finally {
                            if (exportJobs[download.request.id] === job) {
                                exportJobs.remove(download.request.id)
                            }
                        }
                    }
                    exportJobs[download.request.id] = job
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
                }
                else -> {
                    _activeDownloadIds.update { it - download.request.id }
                }
            }
            _allDownloads.update { current ->
                val updated = current.toMutableList()
                val index = updated.indexOfFirst { it.task.request.id == download.request.id }
                if (index != -1) {
                    updated[index] = DownloadTaskInfo(download)
                } else {
                    updated.add(DownloadTaskInfo(download))
                }
                updated
            }
            checkAndStartPolling(downloadManager)
        }

        @Suppress("TooGenericExceptionCaught")
        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            exportJobs.remove(download.request.id)?.cancel()

            _downloadedSongIds.update { it - download.request.id }
            _activeDownloadIds.update { it - download.request.id }
            _allDownloads.update { current ->
                current.filter { it.task.request.id != download.request.id }
            }
            checkAndStartPolling(downloadManager)

            val isExplicitlyDeleted = consumePendingFileDeletion(download.request.id)

            scope.launch {
                val songEntity = database.musicDao().getSongById(download.request.id)
                var keepCacheStatus = false
                if (!isExplicitlyDeleted && songEntity?.localUri != null && songEntity.isFullyCached) {
                    val uri = songEntity.localUri.toUri()
                    val exists = if (songEntity.localUri.startsWith("file:")) {
                        val path = uri.path
                        path != null && File(path).exists()
                    } else {
                        try {
                            appContext.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                        } catch (e: Exception) {
                            LogManager.w("DownloadTracker: Failed checking SAF file descriptor: ${e.message}")
                            false
                        }
                    }
                    if (exists) {
                        keepCacheStatus = true
                    }
                }

                if (!keepCacheStatus) {
                    deleteExportedSongAndCache(download.request.id)
                } else {
                    LogManager.i("DownloadTracker: Keep cache status for ${download.request.id}")
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun exportDownloadedSong(download: Download) {
            val cacheLocation = preferencesManager.cacheLocation.first()
            val isSafEnabled = cacheLocation.isNotBlank()
            val isSilent = isSilentDownload(download)
            LogManager.d("DownloadTracker: Preparing export for ${download.request.id}. Silent: $isSilent")

            val cache = cacheManager.acquireDownloadCache()
            val cacheDataSourceFactory = CacheDataSource.Factory().setCache(cache)

            val songEntity = database.musicDao().getSongById(download.request.id) ?: return
            val (isTranscoded, targetFormat) = parseDownloadFlags(download)
            val song = EntityMapper.toSong(songEntity).copy(isTranscoded = isTranscoded)
            val lyrics = fetchLyricsForExport(song.id, song.artist, song.title)

            val localUriResult = try {
                if (isSafEnabled) {
                    cacheExporter.exportToSaf(
                        song = song,
                        lyrics = lyrics,
                        cacheDataSourceFactory = cacheDataSourceFactory,
                        targetTranscodeFormat = targetFormat
                    )
                } else {
                    cacheExporter.exportToPrivate(
                        song = song,
                        lyrics = lyrics,
                        cacheDataSourceFactory = cacheDataSourceFactory,
                        targetTranscodeFormat = targetFormat
                    )
                }
            } catch (e: SecurityException) {
                LogManager.e("DownloadTracker: SAF permission expired during export", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.saf_permission_expired),
                        Toast.LENGTH_LONG
                    ).show()
                }
                Result.failure(e)
            }

            handleExportResult(download, song.title, isSafEnabled, isTranscoded, cache, localUriResult)
        }

        @Suppress("TooGenericExceptionCaught")
        private fun parseDownloadFlags(download: Download): Pair<Boolean, String?> {
            return try {
                if (download.request.data.isNotEmpty()) {
                    val json = JSONObject(Util.fromUtf8Bytes(download.request.data))
                    Pair(
                        json.optBoolean("isTranscoded", false),
                        json.optString("targetFormat").takeIf { it.isNotBlank() }
                    )
                } else {
                    Pair(false, null)
                }
            } catch (e: Exception) {
                LogManager.w("DownloadTracker: Failed parsing download flags: ${e.message}")
                Pair(false, null)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun fetchLyricsForExport(songId: String, artist: String?, title: String?): String? {
            return try {
                val repo = repository.get()
                if (repo.isConfigured) {
                    repo.getLyricsData(songId, artist, title, true).first
                } else {
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.e("DownloadTracker: Failed to fetch additional metadata for export", e)
                null
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun handleExportResult(
            download: Download,
            songTitle: String,
            isSafEnabled: Boolean,
            isTranscoded: Boolean,
            cache: androidx.media3.datasource.cache.Cache,
            localUriResult: Result<String>
        ) {
            localUriResult.onSuccess { localUri ->
                database.musicDao().updateSongCacheStatus(download.request.id, localUri, true, isTranscoded)
                val targetLabel = if (isSafEnabled) "SAF" else "Private"
                LogManager.i(
                    "DownloadTracker: Song ${download.request.id} exported to $targetLabel " +
                        "and database status marked: $localUri (Transcoded: $isTranscoded)"
                )

                LogManager.i("DownloadTracker: Cleaning up internal cache for exported song ${download.request.id}")
                cache.removeResource(download.request.id)
                try {
                    cacheManager.acquirePlaybackCache().removeResource(download.request.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogManager.e("DownloadTracker: Failed to clean playback cache after export", e)
                }
            }.onFailure { err ->
                if (err is CancellationException) {
                    LogManager.i("DownloadTracker: Export cancelled for $songTitle")
                    throw err
                } else {
                    LogManager.e("DownloadTracker: Export failed for $songTitle: ${err.message}")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.export_failed, err.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1000L

        @Suppress("TooGenericExceptionCaught")
        fun isSilentDownload(download: Download): Boolean {
            return try {
                if (download.request.data.isNotEmpty()) {
                    val json = JSONObject(Util.fromUtf8Bytes(download.request.data))
                    json.optBoolean("isSilent", false)
                } else {
                    false
                }
            } catch (e: Exception) {
                LogManager.w("DownloadTracker: Failed parsing isSilent flag: ${e.message}")
                false
            }
        }
    }
}
