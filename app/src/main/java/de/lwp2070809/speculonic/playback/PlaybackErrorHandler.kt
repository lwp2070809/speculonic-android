@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.lwp2070809.speculonic.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.cache.Cache
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.CacheManager
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.network.NetworkEvent
import de.lwp2070809.speculonic.network.ServerReachableManager
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class PlaybackErrorHandler(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val cacheManager: CacheManager,
    private val isMetered: () -> Boolean,
    private val mobilePlayAllowed: () -> Boolean,
    private val dbProvider: () -> AppDatabase?
) {
    private val consecutiveErrorCount = AtomicInteger(0)
    private val consecutiveFormatSkipCount = AtomicInteger(0)
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

        if (consecutiveErrorCount.get() > MAX_CONSECUTIVE_ITEM_ERRORS) {
            LogManager.w("Consecutive errors detected for the same item. Stopping playback to prevent infinite loop.")
            player.pause()
            consecutiveErrorCount.set(0)
            return
        }

        if (error.cause is NetworkRestrictedException) {
            ServerReachableManager.emitEvent(NetworkEvent.NetworkRestricted)
        }

        if (isFormatOrDecoderError(error)) {
            handleFormatOrDecoderError(player)
            return
        }

        val isNetworkRestricted = (isMetered() && !mobilePlayAllowed()) || error.cause is NetworkRestrictedException
        if (isNetworkRestricted || isNetworkError(error)) {
            handleNetworkError(player, currentMediaId)
        }
    }

    private fun isNetworkError(error: PlaybackException): Boolean {
        return error.errorCode in NETWORK_ERROR_CODES || error.cause is IOException
    }

    private fun isFormatOrDecoderError(error: PlaybackException): Boolean {
        return error.errorCode in FORMAT_ERROR_CODES
    }

    private fun handleFormatOrDecoderError(player: Player) {
        val title = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Unknown"
        val now = System.currentTimeMillis()
        if (now - lastToastTime > ERROR_TOAST_THROTTLE_MS) {
            lastToastTime = now
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    context.getString(R.string.unsupported_format_skipped, title),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val skipCount = consecutiveFormatSkipCount.incrementAndGet()
        val maxAllowedSkips = minOf(player.mediaItemCount.coerceAtLeast(1), MAX_FORMAT_AUTO_SKIPS)

        if (skipCount >= maxAllowedSkips) {
            LogManager.w(
                "Auto-skip: Consecutive format skips reached limit ($skipCount >= $maxAllowedSkips). " +
                    "Pausing to break loop."
            )
            player.pause()
            consecutiveFormatSkipCount.set(0)
            return
        }

        if (player.hasNextMediaItem()) {
            LogManager.i(
                "Auto-skip: Unrecognized/Unsupported format. Skipping to next item " +
                    "(attempt $skipCount/$maxAllowedSkips)."
            )
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        } else {
            LogManager.w("Auto-skip: Unsupported format on last item in playlist. Pausing.")
            player.pause()
            consecutiveFormatSkipCount.set(0)
        }
    }

    private fun handleNetworkError(player: Player, currentMediaId: String?) {
        val upcomingItems = collectUpcomingItems(player)
        if (upcomingItems.isEmpty()) {
            LogManager.w("No next item to skip to. Pausing.")
            player.pause()
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            findAndJumpToNextCachedItem(player, currentMediaId, upcomingItems)
        }
    }

    private fun collectUpcomingItems(player: Player): List<Pair<Int, String>> {
        val currentIndex = player.currentMediaItemIndex
        val itemCount = player.mediaItemCount
        val timeline = player.currentTimeline
        val upcomingItems = mutableListOf<Pair<Int, String>>()
        if (timeline.isEmpty || itemCount <= 0) {
            for (i in (currentIndex + 1) until itemCount) {
                upcomingItems.add(i to player.getMediaItemAt(i).mediaId)
            }
            return upcomingItems
        }

        var nextIndex = timeline.getNextWindowIndex(currentIndex, player.repeatMode, player.shuffleModeEnabled)
        val visited = mutableSetOf<Int>()
        while (nextIndex != C.INDEX_UNSET && nextIndex !in visited && visited.size < itemCount) {
            visited.add(nextIndex)
            if (nextIndex in 0 until itemCount) {
                upcomingItems.add(nextIndex to player.getMediaItemAt(nextIndex).mediaId)
            }
            nextIndex = timeline.getNextWindowIndex(nextIndex, player.repeatMode, player.shuffleModeEnabled)
        }
        return upcomingItems
    }

    private suspend fun findAndJumpToNextCachedItem(
        player: Player,
        currentMediaId: String?,
        upcomingItems: List<Pair<Int, String>>
    ) {
        try {
            val downloadCache = try {
                cacheManager.acquireDownloadCache()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                LogManager.w("PlaybackErrorHandler: Failed to acquire download cache during skip check", e)
                null
            }

            val nextCachedIndex = findFirstCachedIndex(upcomingItems, downloadCache, dbProvider())

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
            if (e is CancellationException) throw e
            LogManager.e("Error checking cache for skip", e)
            withContext(Dispatchers.Main) { player.pause() }
        }
    }

    private suspend fun findFirstCachedIndex(
        upcomingItems: List<Pair<Int, String>>,
        downloadCache: Cache?,
        db: AppDatabase?
    ): Int {
        val musicDao = db?.musicDao()
        for ((index, mediaId) in upcomingItems) {
            val isCachedInMedia3 = downloadCache?.keys?.contains(mediaId) == true
            val song = musicDao?.getSongById(mediaId)
            val hasLocalUri = song?.isFullyCached == true && !song.localUri.isNullOrBlank()
            if (isCachedInMedia3 || hasLocalUri) {
                return index
            }
        }
        return -1
    }

    private companion object {
        const val MAX_CONSECUTIVE_ITEM_ERRORS = 3
        const val ERROR_TOAST_THROTTLE_MS = 1500L
        const val MAX_FORMAT_AUTO_SKIPS = 5

        val NETWORK_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )

        val FORMAT_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
        )
    }
}
