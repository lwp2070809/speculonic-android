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

    
    private fun Context.findActivity(): android.app.Activity? {
        var ctx = this
        while (ctx is android.content.ContextWrapper) {
            if (ctx is android.app.Activity) {
                return ctx
            }
            ctx = ctx.baseContext
        }
        return null
    }

    fun downloadSong(song: Song, isSilent: Boolean = false) {
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

        if (!isSilent && android.os.Build.VERSION.SDK_INT >= 33) {
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (!hasPermission) {
                val activity = context.findActivity()
                if (activity != null) {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        101
                    )
                }
            }
        }

        val streamUrl = repository.buildDownloadUrl(song.id)
        LogManager.d("DownloadController: Requesting download for ${song.title} (ID: ${song.id}, Silent: $isSilent)")
        
        val dataJson = JSONObject().apply {
            put("title", song.title)
            put("artist", song.artist)
            put("isSilent", isSilent)
        }
        val data = Util.getUtf8Bytes(dataJson.toString())
        
        val downloadRequest = DownloadRequest.Builder(song.id, streamUrl.toUri())
            .setCustomCacheKey(song.id)
            .setData(data)
            .build()

        try {
            if (isSilent) {
                de.lwp2070809.speculonic.data.DownloadManagerHelper.getDownloadManager(context).addDownload(downloadRequest)
                LogManager.i("DownloadController: Silent download added directly to DownloadManager for ${song.id}")
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

        scope.launch {
            try {
                val playbackCache = de.lwp2070809.speculonic.data.CacheManager.getPlaybackCache(context)
                playbackCache.removeResource(songId)
                LogManager.d("DownloadController: Cleared playback cache for $songId")
            } catch (e: Exception) {
                LogManager.e("DownloadController: Failed to clear playback cache for $songId", e)
            }
            try {
                val db = AppDatabase.getDatabase(context)
                val song = db.musicDao().getSongById(songId)
                song?.localUri?.let { uriString ->
                    LogManager.d("DownloadController: Removing exported file from SAF: $uriString")
                    val uri = uriString.toUri()
                    
                    val docFile = try { DocumentFile.fromSingleUri(context, uri) } catch (e: Exception) { null }
                    
                    if (docFile?.exists() == true) {
                        val fileName = docFile.name
                        val parentDir = docFile.parentFile
                        
                        
                        val audioDeleted = docFile.delete()
                        
                        
                        if (fileName != null && de.lwp2070809.speculonic.util.FormatUtils.isSupportedAudioFile(fileName)) {
                            val lrcName = de.lwp2070809.speculonic.util.FormatUtils.replaceExtensionWithLrc(fileName)
                            
                            val lrcFile = parentDir?.findFile(lrcName)
                            if (lrcFile?.exists() == true) {
                                if (lrcFile.delete()) {
                                    LogManager.i("DownloadController: Successfully deleted associated .lrc file for $songId")
                                }
                            } else {
                                
                                try {
                                    val lrcUri = de.lwp2070809.speculonic.util.FormatUtils.replaceExtensionWithLrc(uriString).toUri()
                                    DocumentFile.fromSingleUri(context, lrcUri)?.takeIf { it.exists() }?.delete()
                                } catch (e: Exception) {
                                    LogManager.w("DownloadController: Fallback LRC deletion failed", e)
                                }
                            }
                        }

                        if (audioDeleted) {
                            LogManager.i("DownloadController: Successfully deleted SAF file for $songId")
                        } else {
                            LogManager.w("DownloadController: Failed to delete SAF file for $songId")
                        }
                    }
                    db.musicDao().updateSongLocalUri(songId, null)
                    db.musicDao().updateSongCacheStatus(songId, null, false)
                }
            } catch (e: Exception) {
                LogManager.e("DownloadController: Error while removing SAF file for $songId", e)
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
