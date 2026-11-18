package de.lwp2070809.speculonic.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.repository.EntityMapper
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheStrategyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val database: AppDatabase,
    private val downloadController: DownloadController
) {

    
    suspend fun triggerSilentCache(songId: String, title: String) {
        try {
            
                if (!preferencesManager.silentCacheEnabled.first()) {
                    LogManager.d("CacheStrategy: Silent cache is disabled in settings.")
                    return
                }

                
                if (!canDownloadSilent()) {
                    LogManager.d("CacheStrategy: Silent cache skipped due to network constraints.")
                    return
                }

                val songEntity = database.musicDao().getSongById(songId) ?: return
                
                
                if (songEntity.isFullyCached) return

                val song = EntityMapper.toSong(songEntity)

                LogManager.i("CacheStrategy: Triggering verified silent cache for $title")
            downloadController.downloadSong(song, isSilent = true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
