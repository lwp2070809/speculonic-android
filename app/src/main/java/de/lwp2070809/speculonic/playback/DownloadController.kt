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

@OptIn(UnstableApi::class)
class DownloadController(
    private val context: Context,
    private val repository: SubsonicRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    
    fun downloadSong(song: Song, isSilent: Boolean = false) {
        
        if (!isSilent) {
            val activeIds = DownloadTracker.activeDownloadIds.value
            val downloadedIds = DownloadTracker.downloadedSongIds.value
            if (activeIds.contains(song.id) || downloadedIds.contains(song.id)) {
                LogManager.d("DownloadController: Song ${song.title} is already active or downloaded. Skipping AddRequest.")
                return
            }
        }

        val streamUrl = repository.buildDownloadUrl(song.id)
        LogManager.d("DownloadController: Requesting download for ${song.title} (ID: ${song.id}, Silent: $isSilent)")
        
        
        val dataJson = JSONObject().apply {
            put("title", song.title)
            put("isSilent", isSilent)
        }
        val data = Util.getUtf8Bytes(dataJson.toString())
        
        val downloadRequest = DownloadRequest.Builder(song.id, streamUrl.toUri())
            .setCustomCacheKey(song.id)
            .setData(data)
            .build()

        try {
            
            
            DownloadService.sendRemoveDownload(
                context,
                de.lwp2070809.speculonic.playback.DownloadService::class.java,
                song.id,
                false
            )

            DownloadService.sendAddDownload(
                context,
                de.lwp2070809.speculonic.playback.DownloadService::class.java,
                downloadRequest,
                false
            )
            LogManager.i("DownloadController: Remove & AddDownload intents sent for ${song.id}")
        } catch (e: Exception) {
            LogManager.e("DownloadController: Failed to send download intents", e)
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
            
            
            scope.launch {
                try {
                    val playbackCache = de.lwp2070809.speculonic.data.CacheManager.getPlaybackCache(context)
                    playbackCache.removeResource(songId)
                    LogManager.d("DownloadController: Cleared playback cache for $songId")
                } catch (e: Exception) {
                    LogManager.e("DownloadController: Failed to clear playback cache for $songId", e)
                }
            }
        } catch (e: Exception) {
            LogManager.e("DownloadController: Failed to send RemoveDownload intent", e)
        }

        
        scope.launch {
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
}
