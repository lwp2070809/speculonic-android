package de.lwp2070809.speculonic.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CacheStrategyManager(
    private val context: Context,
    repository: SubsonicRepository,
    private val preferencesManager: PreferencesManager
) {
    private val downloadController = DownloadController(context, repository)

    
    suspend fun triggerSilentCache(songId: String, title: String, artist: String, album: String, duration: Int?, coverArt: String?) {
        try {
            
                if (!preferencesManager.silentCacheEnabled.first()) {
                    LogManager.d("CacheStrategy: Silent cache is disabled in settings.")
                    return
                }

                
                if (!canDownloadSilent()) {
                    LogManager.d("CacheStrategy: Silent cache skipped due to network constraints.")
                    return
                }

                val db = AppDatabase.getDatabase(context)
                val songEntity = db.musicDao().getSongById(songId) ?: return
                
                
                if (songEntity.isFullyCached) return

                val song = Song(
                    id = songId,
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    coverArt = coverArt,
                    md5 = songEntity.md5,
                    size = songEntity.size
                )

            LogManager.i("CacheStrategy: Triggering verified silent cache for $title")
            downloadController.downloadSong(song, isSilent = true)
        } catch (e: Exception) {
            LogManager.e("CacheStrategy: Error in silent cache trigger", e)
        }
    }

    
    private suspend fun canDownloadSilent(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            return true
        }

        
        
        return preferencesManager.mobilePlayAllowed.first()
    }
}
