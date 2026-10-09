package de.lwp2070809.speculonic.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@OptIn(UnstableApi::class)
class DownloadController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SubsonicRepository
) {
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val recentlyRequested = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    companion object {
        private val _permissionRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val permissionRequests = _permissionRequests

        fun requestNotificationPermission() {
            _permissionRequests.tryEmit(Unit)
        }
    }

    fun downloadSong(song: Song, isSilent: Boolean = false) {
        if (!isSilent && android.os.Build.VERSION.SDK_INT >= 33) {
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                requestNotificationPermission()
            }
        }
        if (!isSilent) {
            val activeIds = DownloadTracker.activeDownloadIds.value
            val downloadedIds = DownloadTracker.downloadedSongIds.value
            if (activeIds.contains(song.id) || downloadedIds.contains(song.id) || !recentlyRequested.add(song.id)) {
                LogManager.d("DownloadController: Song ${song.title} is already active or downloaded. Skipping AddRequest.")
                return
            }
            scope.launch {
                kotlinx.coroutines.delay(2000)
                recentlyRequested.remove(song.id)
            }
        }
        val playbackState = de.lwp2070809.speculonic.playback.PlaybackController.getInstance(context).playbackState.value
        if (!isSilent && playbackState.currentSongId == song.id) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, context.getString(de.lwp2070809.speculonic.R.string.download_server_limit_toast), android.widget.Toast.LENGTH_LONG).show()
            }
        }

        val streamUrl = repository.buildDownloadUrl(song.id, song.suffix)
        val isTranscodedDownload = streamUrl.contains("format=") && !streamUrl.contains("format=raw")
        val targetFormat = if (isTranscodedDownload) {
            streamUrl.toUri().getQueryParameter("format")?.lowercase()
        } else null
        de.lwp2070809.speculonic.util.LogManager.i(
            de.lwp2070809.speculonic.util.LogTag.CACHE,
            "DownloadController: Enqueued download for '${song.title}' (id=${song.id}, silent=$isSilent, transcoded=$isTranscodedDownload, format=$targetFormat)"
        )
        
        val dataJson = JSONObject().apply {
            put("title", song.title)
            put("artist", song.artist)
            put("isSilent", isSilent)
            put("isTranscoded", isTranscodedDownload)
            if (targetFormat != null) {
                put("targetFormat", targetFormat)
            }
        }
        val data = Util.getUtf8Bytes(dataJson.toString())
        
        val downloadRequest = DownloadRequest.Builder(song.id, streamUrl.toUri())
            .setCustomCacheKey(song.id)
            .setData(data)
            .build()

        try {
            if (isSilent) {
                DownloadService.sendAddDownload(
                    context,
                    de.lwp2070809.speculonic.playback.SilentDownloadService::class.java,
                    downloadRequest,
                    false
                )
                LogManager.i("DownloadController: Silent download added via SilentDownloadService for ${song.id}")
            } else {
                DownloadService.sendAddDownload(
                    context,
                    de.lwp2070809.speculonic.playback.DownloadService::class.java,
                    downloadRequest,
                    false
                )
                LogManager.i("DownloadController: AddDownload intent sent for ${song.id}")
            }
        } catch (e: Exception) {
            LogManager.e("DownloadController: Failed to send download intents", e)
        }
    }

    fun pauseDownload(songId: String) {
        try {
            DownloadService.sendSetStopReason(
                context,
                de.lwp2070809.speculonic.playback.DownloadService::class.java,
                songId,
                1,
                false
            )
            LogManager.i("DownloadController: Sent SetStopReason=1 (Pause) for $songId")
        } catch (e: Exception) {
            LogManager.e("DownloadController: Failed to send Pause intent", e)
        }
    }

    fun resumeDownload(songId: String) {
        try {
            DownloadService.sendSetStopReason(
                context,
                de.lwp2070809.speculonic.playback.DownloadService::class.java,
                songId,
                0,
                false
            )
            LogManager.i("DownloadController: Sent SetStopReason=0 (Resume) for $songId")
        } catch (e: Exception) {
            LogManager.e("DownloadController: Failed to send Resume intent", e)
        }
    }


    
    fun removeDownload(songId: String) {
        LogManager.i("DownloadController: Requesting removal of download for $songId")
        if (DownloadTracker.hasDownload(songId)) {
            DownloadTracker.markForFileDeletion(songId)
            try {
                DownloadService.sendRemoveDownload(
                    context,
                    de.lwp2070809.speculonic.playback.DownloadService::class.java,
                    songId,
                    false
                )
            } catch (e: Exception) {
                LogManager.e("DownloadController: Failed to send RemoveDownload intent", e)
            }
        } else {
            scope.launch {
                LogManager.i("DownloadController: Standalone file cleanup for $songId (task was not in Media3)")
                DownloadTracker.deleteExportedSongAndCache(context, songId)
            }
        }
    }

    fun cancelDownloadTaskOnly(songId: String) {
        try {
            DownloadService.sendRemoveDownload(
                context,
                de.lwp2070809.speculonic.playback.DownloadService::class.java,
                songId,
                false
            )
        } catch (e: Exception) {
            LogManager.e("DownloadController: Failed to send RemoveDownload intent (task only)", e)
        }
    }
}
