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
    private val consecutiveFormatSkipCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastErrorMediaId: String? = null
    @Volatile private var lastToastTime = 0L

    fun resetErrorCount() {
        consecutiveErrorCount.set(0)
        consecutiveFormatSkipCount.set(0)
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

        val isFormatOrDecoderError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED

        if (error.cause is NetworkRestrictedException) {
            de.lwp2070809.speculonic.network.ServerReachableManager.emitEvent(de.lwp2070809.speculonic.network.NetworkEvent.NetworkRestricted)
        }

        if (isFormatOrDecoderError) {
            val title = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Unknown"
            val now = System.currentTimeMillis()
            if (now - lastToastTime > 1500L) {
                lastToastTime = now
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(de.lwp2070809.speculonic.R.string.unsupported_format_skipped, title),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }

            val skipCount = consecutiveFormatSkipCount.incrementAndGet()
            val maxAllowedSkips = minOf(player.mediaItemCount.coerceAtLeast(1), 5)

            if (skipCount >= maxAllowedSkips) {
                LogManager.w("Auto-skip: Consecutive format skips reached limit ($skipCount >= $maxAllowedSkips). Pausing to break loop.")
                player.pause()
                consecutiveFormatSkipCount.set(0)
                return
            }

            if (player.hasNextMediaItem()) {
                LogManager.i("Auto-skip: Unrecognized/Unsupported format. Skipping to next item (attempt $skipCount/$maxAllowedSkips).")
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            } else {
                LogManager.w("Auto-skip: Unsupported format on last item in playlist. Pausing.")
                player.pause()
                consecutiveFormatSkipCount.set(0)
            }
            return
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
                        if (player.currentMediaItem?.mediaId != currentMediaId) {
                            LogManager.w("Auto-skip aborted: User changed the track during resolution")
                            return@withContext
                        }
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
