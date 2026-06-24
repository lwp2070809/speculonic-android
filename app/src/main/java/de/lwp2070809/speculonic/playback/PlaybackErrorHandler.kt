@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.lwp2070809.speculonic.playback

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import de.lwp2070809.speculonic.data.CacheManager
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class PlaybackErrorHandler(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val isMetered: () -> Boolean,
    private val mobilePlayAllowed: () -> Boolean,
    private val dbProvider: () -> AppDatabase?
) {
    private val consecutiveErrorCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastErrorMediaId: String? = null

    fun resetErrorCount() {
        consecutiveErrorCount.set(0)
        lastErrorMediaId = null
    }

    fun handlePlayerError(player: Player, error: PlaybackException) {
        val currentMediaId = player.currentMediaItem?.mediaId
        LogManager.e("Player error on item $currentMediaId: ${error.errorCodeName} (${error.errorCode})", error)

        if (currentMediaId == lastErrorMediaId) {
            consecutiveErrorCount.incrementAndGet()
        } else {
            lastErrorMediaId = currentMediaId
            consecutiveErrorCount.set(1)
        }

        if (consecutiveErrorCount.get() > 3) {
            LogManager.w("Consecutive errors detected for the same item. Stopping playback to prevent infinite loop.")
            player.pause()
            consecutiveErrorCount.set(0)
            return
        }

        val isNetworkRestricted = isMetered() && !mobilePlayAllowed() || error.cause is NetworkRestrictedException
        val isNetworkError = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                error.cause is IOException

        if (error.cause is NetworkRestrictedException) {
            de.lwp2070809.speculonic.di.NetworkModule.ServerReachableManager.emitEvent(de.lwp2070809.speculonic.di.NetworkModule.NetworkEvent.NetworkRestricted)
        }

        if (isNetworkRestricted || isNetworkError) {
            val currentIndex = player.currentMediaItemIndex
            val itemCount = player.mediaItemCount
            val upcomingItems = mutableListOf<Pair<Int, String>>()
            for (i in (currentIndex + 1) until itemCount) {
                upcomingItems.add(i to player.getMediaItemAt(i).mediaId)
            }

            if (upcomingItems.isEmpty()) {
                LogManager.w("No next item to skip to. Pausing.")
                player.pause()
                return
            }

            serviceScope.launch(Dispatchers.IO) {
                try {
                    var nextCachedIndex = -1
                    val downloadCache = try {
                        CacheManager.getDownloadCache(context)
                    } catch (e: Exception) { null }

                    val db = dbProvider()
                    for ((index, mediaId) in upcomingItems) {
                        val isCachedInMedia3 = downloadCache?.keys?.contains(mediaId) == true
                        val song = db?.musicDao()?.getSongById(mediaId)
                        val hasLocalUri = song?.isFullyCached == true && !song.localUri.isNullOrBlank()

                        if (isCachedInMedia3 || hasLocalUri) {
                            nextCachedIndex = index
                            break
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (nextCachedIndex != -1 && nextCachedIndex < player.mediaItemCount) {
                            LogManager.i("Auto-skip: Jump to cached item at index $nextCachedIndex.")
                            player.seekToDefaultPosition(nextCachedIndex)
                            player.prepare()
                            player.play()
                        } else {
                            LogManager.w("Auto-skip: No cached items found. Pausing.")
                            player.pause()
                        }
                    }
                } catch (e: Exception) {
                    LogManager.e("Error checking cache for skip", e)
                    withContext(Dispatchers.Main) { player.pause() }
                }
            }
        }
    }
}
