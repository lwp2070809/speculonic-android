package de.lwp2070809.speculonic.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.core.content.edit
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
import de.lwp2070809.speculonic.network.interceptor.ServerUnavailableException
import de.lwp2070809.speculonic.util.FormatUtils
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okio.ByteString.Companion.encodeUtf8
import java.io.EOFException
import java.io.File
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
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
    private val networkMonitor: NetworkMonitor,
    private val downloadManagerHelper: DownloadManagerHelper
) {
    private val _downloadedSongIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedSongIds = _downloadedSongIds.asStateFlow()

    private val _activeDownloadIds = MutableStateFlow<Set<String>>(emptySet())
    val activeDownloadIds = _activeDownloadIds.asStateFlow()

    private val _allDownloads = MutableStateFlow<List<DownloadTaskInfo>>(emptyList())
    val allDownloadsFlow = _allDownloads.asStateFlow()

    val activeNonSilentCountFlow: Flow<Int> = activeDownloadIds
        .map { it.size }
        .distinctUntilChanged()

    private val tracking = DownloadTrackingSession(::startTracking)
    private val exportJobs = ConcurrentHashMap<String, Job>()
    // Media3 persists FAILED but discards the exception that distinguished an outage from a denial.
    private val pendingRawExports = appContext.getSharedPreferences("pending_raw_exports", Context.MODE_PRIVATE)
    private fun recoveryKey(download: Download) = download.request.uri.toString().encodeUtf8().sha256().hex()
    private fun isPendingRawExport(download: Download) =
        pendingRawExports.getString(download.request.id, null) == recoveryKey(download)
    private val pendingFileDeletionIds = ConcurrentHashMap.newKeySet<String>()
    private val redownloadingIds = ConcurrentHashMap.newKeySet<String>()
    private val visibleDownloadPages = MutableStateFlow(0)
    // Main-looper changes take precedence over the index snapshot being read on IO.
    private var changedDuringRestore: MutableSet<String>? = null

    suspend fun requestProgressWhileVisible(): Nothing {
        visibleDownloadPages.update { it + 1 }
        try {
            awaitCancellation()
        } finally {
            visibleDownloadPages.update { it - 1 }
        }
    }

    fun markForFileDeletion(songId: String) {
        pendingFileDeletionIds.add(songId)
    }

    fun consumePendingFileDeletion(songId: String): Boolean {
        return pendingFileDeletionIds.remove(songId)
    }

    fun hasDownload(songId: String): Boolean {
        return _allDownloads.value.any { it.task.request.id == songId }
    }

    suspend fun discardInconsistentDownload(songId: String) = withContext(Dispatchers.IO) {
        pendingRawExports.edit { remove(songId) }
        exportJobs.remove(songId)?.cancelAndJoin()
        pendingFileDeletionIds.remove(songId)
        val downloadManager = downloadManagerHelper.getDownloadManagerSuspend()
        val hasExistingTask = withContext(Dispatchers.Main) {
            downloadManager.currentDownloads.any { it.request.id == songId }
        } || (downloadManager.downloadIndex.getDownload(songId) != null)

        PlaybackBuffer.remove(cacheManager.acquirePlaybackCache(), songId)
        cacheManager.acquireDownloadCache().removeResource(songId)
        if (hasExistingTask) {
            // The resolver owns file/DB cleanup, for both delete and redownload.
            redownloadingIds.add(songId)
        }
        withContext(Dispatchers.Main.immediate) {
            changedDuringRestore?.add(songId)
            _downloadedSongIds.update { it - songId }
            _activeDownloadIds.update { it - songId }
            _allDownloads.update { current -> current.filter { it.task.request.id != songId } }
        }
        if (hasExistingTask) {
            try {
                androidx.media3.exoplayer.offline.DownloadService.sendRemoveDownload(
                    appContext,
                    de.lwp2070809.speculonic.playback.DownloadService::class.java,
                    songId,
                    false
                )
            } catch (e: Exception) {
                redownloadingIds.remove(songId)
                throw e
            }
        }
    }

    private suspend fun clearInternalAudioCaches(songId: String) {
        try {
            PlaybackBuffer.remove(cacheManager.acquirePlaybackCache(), songId)
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

    suspend fun deleteExportedSongAndCache(songId: String) = withContext(Dispatchers.IO) {
        cacheExporter.withSongExport(songId) {
            val dao = database.musicDao()
            dao.getSongById(songId)?.localUri?.let { deleteExportedFiles(songId, it) }
            dao.updateSongCacheStatus(songId, null, false, false)
            clearInternalAudioCaches(songId)
        }
    }

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
            deleteSafAudioAndCompanionLrc(songId, docFile)
        } else if (uriString.startsWith("file:")) {
            deletePrivateAudioAndCompanionLrc(songId, uri)
        }
    }

    private fun deleteSafAudioAndCompanionLrc(
        songId: String,
        docFile: DocumentFile
    ) {
        if (!docFile.isFile) return
        val fileName = docFile.name
        if (!docFile.delete()) return
        LogManager.i("DownloadTracker: Successfully deleted SAF file for $songId")
        if (fileName == null || !FormatUtils.isSupportedAudioFile(fileName)) return
        val name = FormatUtils.replaceExtensionWithLrc(fileName)
        try {
            val sibling = SafDirectoryHelper.listChildEntries(appContext, docFile.uri).singleOrNull { it.name == name }
            if (sibling != null && isRegularSafFileType(sibling.mimeType)) {
                DocumentFile.fromSingleUri(appContext, sibling.uri)?.takeIf { it.isFile }?.delete()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.w("DownloadTracker: Unable to remove companion lyrics for $songId", e)
        }
    }

    private fun deletePrivateAudioAndCompanionLrc(songId: String, uri: android.net.Uri) {
        try {
            val path = uri.path ?: return
            val file = File(path)
            if (!file.isFile) return
            file.delete()
            val lrcFile = File(FormatUtils.replaceExtensionWithLrc(file.absolutePath))
            if (lrcFile.isFile) lrcFile.delete()
        } catch (e: Exception) {
            LogManager.e("DownloadTracker: Failed to delete private file for $songId", e)
        }
    }

    suspend fun resetWhileTrackingPaused(clearDownloads: Boolean, reset: suspend () -> Unit) = tracking.restartAfter {
        // Media3 delivers listener callbacks on the main looper. Drain any in-flight callback
        // from the detached listener before clearing the old session's observable state.
        withContext(Dispatchers.Main.immediate) {
            _downloadedSongIds.value = emptySet()
            _activeDownloadIds.value = emptySet()
            _allDownloads.value = emptyList()
            if (clearDownloads) {
                pendingRawExports.edit { clear() }
                pendingFileDeletionIds.clear()
                redownloadingIds.clear()
            }
        }
        reset()
    }

    fun init() = tracking.start()

    private fun startTracking(scope: CoroutineScope) {
        scope.launch {
            val downloadManager = downloadManagerHelper.getDownloadManagerSuspend()
            val listener = DownloadManagerListener(scope)
            withContext(Dispatchers.Main.immediate) {
                changedDuringRestore = mutableSetOf()
                downloadManager.addListener(listener)
                scope.coroutineContext.job.invokeOnCompletion { downloadManager.removeListener(listener) }
            }
            try {
                refreshDownloads(downloadManager)
                launch { observeProgressDemand(downloadManager) }
                recoverPendingExports(listener, downloadManager)
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) { changedDuringRestore = null }
            }
        }
    }

    private suspend fun recoverPendingExports(listener: DownloadManagerListener, manager: DownloadManager) {
        val downloadCache = cacheManager.acquireDownloadCache()
        val retained = downloadCache.keys
        val candidates = _allDownloads.value.filter {
            (it.state == Download.STATE_COMPLETED && it.task.request.id in retained) ||
                (it.state == Download.STATE_FAILED && isPendingRawExport(it.task))
        }
        if (candidates.isEmpty()) return

        if (!canRecoverExports()) {
            LogManager.w("DownloadTracker: Skipping startup export recovery because SAF permission expired")
            return
        }

        candidates.forEach { info ->
            val songId = info.task.request.id
            val songEntity = database.musicDao().getSongById(songId)
            val alreadyExported = songEntity?.isFullyCached == true &&
                !songEntity.localUri.isNullOrBlank() &&
                doesExportedFileExist(songEntity.localUri)
            if (songEntity == null || alreadyExported) {
                pendingRawExports.edit { remove(songId) }
                runCatching { downloadCache.removeResource(songId) }.onFailure { err ->
                    if (err is CancellationException) throw err
                    LogManager.w("DownloadTracker: Failed cleaning retained cache for $songId: ${err.message}")
                }
            } else if (RawAudioDownloader.handles(info.task.request)) {
                listener.resumeRawExport(manager, info.task)
            } else {
                listener.scheduleExport(info.task).join()
            }
        }
    }

    private suspend fun canRecoverExports(): Boolean {
        val location = preferencesManager.cacheLocation.first()
        return location.isBlank() || appContext.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == location && it.isReadPermission && it.isWritePermission
        }
    }

    private fun doesExportedFileExist(uriString: String): Boolean {
        val uri = uriString.toUri()
        return if (uriString.startsWith("file:")) {
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
    }

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
        val currentById = getCurrentDownloadsSafe(downloadManager).associateByTo(mutableMapOf()) { it.request.id }
        fun add(download: Download) {
            all.add(DownloadTaskInfo(download))
            classifyDownloadStatus(download, downloaded, active)
        }
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val dbDownload = cursor.download
                add(currentById.remove(dbDownload.request.id) ?: dbDownload)
            }
        }
        currentById.values.forEach(::add)
        withContext(Dispatchers.Main.immediate) {
            val changed = checkNotNull(changedDuringRestore)
            if (changed.isNotEmpty()) {
                all.removeAll { it.task.request.id in changed }
                downloaded.removeAll(changed)
                active.removeAll(changed)
                all.addAll(_allDownloads.value)
                downloaded.addAll(_downloadedSongIds.value)
                active.addAll(_activeDownloadIds.value)
            }
            _downloadedSongIds.value = downloaded
            _activeDownloadIds.value = active
            _allDownloads.value = all
        }
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
            Download.STATE_FAILED,
            Download.STATE_REMOVING,
            Download.STATE_RESTARTING,
            Download.STATE_STOPPED -> Unit
        }
    }

    private suspend fun sampleDownloadProgress(downloadManager: DownloadManager) {
        val beforeSample = _allDownloads.value
        val currentActiveMap = getCurrentDownloadsSafe(downloadManager).associateBy { it.request.id }
        val sampled = beforeSample.map { item ->
            val active = currentActiveMap[item.task.request.id]
            if (item.state == Download.STATE_DOWNLOADING && active?.state == Download.STATE_DOWNLOADING) {
                item.copy(progress = active.percentDownloaded)
            } else item
        }
        // A listener may have paused, removed or replaced the task during sampling.
        _allDownloads.compareAndSet(beforeSample, sampled)
    }

    private suspend fun observeProgressDemand(downloadManager: DownloadManager) {
        combine(visibleDownloadPages, allDownloadsFlow) { visible, tasks ->
            visible > 0 && tasks.any { it.state == Download.STATE_DOWNLOADING }
        }.distinctUntilChanged().collectLatest { shouldSample ->
            if (shouldSample) {
                while (true) {
                    sampleDownloadProgress(downloadManager)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    private inner class DownloadManagerListener(private val scope: CoroutineScope) : DownloadManager.Listener {
        fun resumeRawExport(manager: DownloadManager, download: Download, retry: Boolean = false) {
            scope.launch {
                if (retry) delay(EXPORT_RETRY_DELAY_MS)
                combine(networkMonitor.physicalOnline, preferencesManager.offlineModeEnabled) { online, offlineMode ->
                    online && !offlineMode
                }.first { it }
                withContext(Dispatchers.Main.immediate) {
                    val id = download.request.id
                    if (!retry && changedDuringRestore?.contains(id) == true) return@withContext
                    if (exportJobs.containsKey(id) || _allDownloads.value.none { it.task === download }) return@withContext
                    if (download.state == Download.STATE_FAILED && !isPendingRawExport(download)) return@withContext
                    pendingRawExports.edit { putString(id, recoveryKey(download)) }
                    manager.addDownload(download.request)
                }
            }
        }

        private fun isRetryableExportFailure(error: Throwable?): Boolean =
            generateSequence(error) { it.cause }.any {
                it is ServerUnavailableException || it is SocketException || it is SocketTimeoutException ||
                    it is UnknownHostException || it is EOFException
            }

        fun scheduleExport(download: Download): Job {
            val id = download.request.id
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    exportDownloadedSong(download)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogManager.e("DownloadTracker: Export deferred for $id", e)
                }
            }
            val existing = exportJobs.putIfAbsent(id, job)
            if (existing != null) {
                job.cancel()
                return existing
            }
            job.invokeOnCompletion { exportJobs.remove(id, job) }
            job.start()
            return job
        }

        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            if (!scope.isActive) return
            changedDuringRestore?.add(download.request.id)
            val isSilent = isSilentDownload(download)

            when (download.state) {
                Download.STATE_COMPLETED -> {
                    pendingRawExports.edit { remove(download.request.id) }
                    redownloadingIds.remove(download.request.id)
                    _downloadedSongIds.update { it + download.request.id }
                    _activeDownloadIds.update { it - download.request.id }
                    LogManager.i("DownloadTracker: Download completed for ${download.request.id}. Silent: $isSilent")

                    scheduleExport(download)
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
                    if (download.state == Download.STATE_FAILED && isPendingRawExport(download)) {
                        if (isRetryableExportFailure(finalException)) {
                            resumeRawExport(downloadManager, download, retry = true)
                        } else {
                            pendingRawExports.edit { remove(download.request.id) }
                        }
                    }
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
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            if (!scope.isActive) return
            pendingRawExports.edit { remove(download.request.id) }
            changedDuringRestore?.add(download.request.id)
            if (redownloadingIds.remove(download.request.id)) {
                // STATE_REMOVING was published after discard cleared the list. Remove that
                // old row, but preserve a replacement that has already reached a new state.
                _allDownloads.update { current ->
                    current.filterNot { it.task.request.id == download.request.id && it.state == Download.STATE_REMOVING }
                }
                LogManager.i("DownloadTracker: Skipped resolver-owned file cleanup for ${download.request.id}")
                return
            }
            exportJobs.remove(download.request.id)?.cancel()

            _downloadedSongIds.update { it - download.request.id }
            _activeDownloadIds.update { it - download.request.id }
            _allDownloads.update { current ->
                current.filter { it.task.request.id != download.request.id }
            }

            val isExplicitlyDeleted = consumePendingFileDeletion(download.request.id)

            scope.launch {
                val songEntity = database.musicDao().getSongById(download.request.id)
                var keepCacheStatus = false
                if (!isExplicitlyDeleted && songEntity?.localUri != null && songEntity.isFullyCached) {
                    if (doesExportedFileExist(songEntity.localUri)) {
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

        private suspend fun exportDownloadedSong(download: Download) {
            cacheExporter.withSongExport(download.request.id) { exportAndRegisterDownloadedSong(download) }
        }

        private suspend fun exportAndRegisterDownloadedSong(download: Download) {
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

            val register: suspend (String) -> Unit = { uri ->
                check(database.musicDao().updateSongCacheStatus(song.id, uri, true, isTranscoded) == 1) {
                    "Song removed during export"
                }
            }
            val localUriResult = try {
                if (isSafEnabled) {
                    cacheExporter.exportToSaf(
                        song = song,
                        lyrics = lyrics,
                        cacheDataSourceFactory = cacheDataSourceFactory,
                        targetTranscodeFormat = targetFormat
                    ).mapCatching { uri ->
                        register(uri)
                        uri
                    }.onFailure { if (it is CancellationException) throw it }
                } else {
                    cacheExporter.exportToPrivate(
                        song = song,
                        lyrics = lyrics,
                        cacheDataSourceFactory = cacheDataSourceFactory,
                        targetTranscodeFormat = targetFormat,
                        register = register
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

        private suspend fun fetchLyricsForExport(songId: String, artist: String?, title: String?): String? {
            return try {
                val repo = repository.get()
                if (repo.isConfigured) {
                    repo.getLyricsData(songId, artist, title).first
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

        private suspend fun handleExportResult(
            download: Download,
            songTitle: String,
            isSafEnabled: Boolean,
            isTranscoded: Boolean,
            cache: androidx.media3.datasource.cache.Cache,
            localUriResult: Result<String>
        ) {
            localUriResult.onSuccess { localUri ->
                val targetLabel = if (isSafEnabled) "SAF" else "Private"
                LogManager.i(
                    "DownloadTracker: Song ${download.request.id} exported to $targetLabel " +
                        "and database status marked: $localUri (Transcoded: $isTranscoded)"
                )

                LogManager.i("DownloadTracker: Cleaning up internal cache for exported song ${download.request.id}")
                cache.removeResource(download.request.id)
                try {
                    PlaybackBuffer.remove(cacheManager.acquirePlaybackCache(), download.request.id)
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
        private const val EXPORT_RETRY_DELAY_MS = 30_000L
        private const val POLL_INTERVAL_MS = 1000L

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

/** Owns all tracker work that can still read or export from the live Media3 caches. */
internal class DownloadTrackingSession(private val onStart: (CoroutineScope) -> Unit) {
    private val resetMutex = Mutex()
    private var session: CoroutineScope? = null
    private var paused = false

    @Synchronized
    fun start() {
        if (paused || session != null) return
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        session = next
        onStart(next)
    }

    suspend fun restartAfter(reset: suspend () -> Unit) = resetMutex.withLock {
        val previous = synchronized(this) {
            paused = true
            session.also { session = null }
        }
        try {
            // Join before taking the cache-release mutex: recovery or export may itself be
            // waiting to acquire it. A cancelled settings screen must also drain that work.
            withContext(NonCancellable) { previous?.coroutineContext?.job?.cancelAndJoin() }
            reset()
        } finally {
            synchronized(this) {
                paused = false
                start()
            }
        }
    }
}
