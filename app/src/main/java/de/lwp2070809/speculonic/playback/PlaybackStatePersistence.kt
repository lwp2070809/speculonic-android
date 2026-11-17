package de.lwp2070809.speculonic.playback

import androidx.media3.common.Player
import de.lwp2070809.speculonic.SpeculonicApp
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.data.db.entities.PlaybackQueueEntity
import de.lwp2070809.speculonic.data.prefs.PlaybackPrefs
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.toMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class PlaybackStatePersistence(
    private val serviceScope: CoroutineScope,
    private val preferencesManagerProvider: () -> PlaybackPrefs?,
    private val dbProvider: () -> AppDatabase?,
    private val currentQueueTitleProvider: () -> String?,
    private val onQueueTitleRestored: (String?) -> Unit
) {
    private companion object {
        private const val SAVE_STATE_DEBOUNCE_MS = 2000L
        private const val SAVE_QUEUE_DEBOUNCE_MS = 1000L
        private const val POSITION_PERSISTENCE_INTERVAL_MS = 15000L
    }

    private data class PlaybackSnapshot(
        val mediaId: String?,
        val position: Long,
        val index: Int,
        val repeatMode: Int,
        val shuffleMode: Boolean,
        val queueTitle: String?
    )

    private var saveStateJob: Job? = null
    private var positionPersistenceJob: Job? = null
    private var saveQueueJob: Job? = null
    private val saveStateMutex = Mutex()
    private val saveGeneration = AtomicLong(0L)
    private val lastPersistedGeneration = AtomicLong(0L)

    suspend fun restorePlaybackState(player: Player, repo: SubsonicRepository) {
        withContext(Dispatchers.IO) {
            val db = dbProvider() ?: return@withContext
            val prefs = preferencesManagerProvider() ?: return@withContext
            
            val savedQueue = db.musicDao().getPlaybackQueue()
            val lastRepeatMode = prefs.repeatMode.first()
            val lastShuffleMode = prefs.shuffleMode.first()
            val lastQueueTitle = prefs.lastQueueTitle.first()

            val effectiveRepeatMode = if (lastShuffleMode && lastRepeatMode == Player.REPEAT_MODE_ONE) {
                Player.REPEAT_MODE_ALL
            } else {
                lastRepeatMode
            }

            if (savedQueue.isNotEmpty()) {
                val mediaItems = savedQueue.map { it.toMediaItem(repo) }
                val lastMediaId = prefs.lastSongId.first()
                val savedIndex = prefs.lastQueueIndex.first()
                val lastPosition = prefs.lastPosition.first()

                val effectiveIndex = if (savedIndex in mediaItems.indices && mediaItems[savedIndex].mediaId == lastMediaId) {
                    savedIndex
                } else {
                    val matchedIndex = mediaItems.indexOfFirst { it.mediaId == lastMediaId }
                    if (matchedIndex != -1) matchedIndex else savedIndex.coerceIn(0, mediaItems.size - 1)
                }
                val effectivePosition = if (mediaItems.getOrNull(effectiveIndex)?.mediaId == lastMediaId) {
                    lastPosition
                } else {
                    0L
                }

                applyRestoredQueue(
                    player = player,
                    mediaItems = mediaItems,
                    queueTitle = lastQueueTitle,
                    repeatMode = effectiveRepeatMode,
                    shuffleMode = lastShuffleMode,
                    effectiveIndex = effectiveIndex,
                    effectivePosition = effectivePosition
                )
            } else {
                withContext(Dispatchers.Main) {
                    player.repeatMode = effectiveRepeatMode
                    player.shuffleModeEnabled = lastShuffleMode
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun applyRestoredQueue(
        player: Player,
        mediaItems: List<androidx.media3.common.MediaItem>,
        queueTitle: String?,
        repeatMode: Int,
        shuffleMode: Boolean,
        effectiveIndex: Int,
        effectivePosition: Long
    ) {
        try {
            withContext(Dispatchers.Main) {
                onQueueTitleRestored(queueTitle)
                player.repeatMode = repeatMode
                player.shuffleModeEnabled = shuffleMode
                player.setMediaItems(mediaItems, effectiveIndex, effectivePosition)
                player.prepare()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("PlaybackStatePersistence: Failed to restore playback state", e)
            withContext(Dispatchers.Main) {
                player.clearMediaItems()
            }
        }
    }

    fun savePlaybackState(player: Player, immediate: Boolean = false) {
        val prefs = preferencesManagerProvider() ?: return
        val seq = saveGeneration.incrementAndGet()
        saveStateJob?.cancel()

        if (immediate) {
            val snapshot = captureSnapshot(player)
            SpeculonicApp.applicationScope.launch(Dispatchers.IO + NonCancellable) {
                persistSnapshotIfLatest(prefs, seq, requireUnpreemptedSchedule = false, snapshot = snapshot)
            }
        } else {
            saveStateJob = serviceScope.launch(Dispatchers.IO) {
                delay(SAVE_STATE_DEBOUNCE_MS)
                val snapshot = withContext(Dispatchers.Main.immediate) {
                    if (seq != saveGeneration.get()) null else captureSnapshot(player)
                } ?: return@launch
                persistSnapshotIfLatest(prefs, seq, requireUnpreemptedSchedule = true, snapshot = snapshot)
            }
        }
    }

    private fun captureSnapshot(player: Player): PlaybackSnapshot = PlaybackSnapshot(
        mediaId = player.currentMediaItem?.mediaId,
        position = player.currentPosition,
        index = player.currentMediaItemIndex,
        repeatMode = player.repeatMode,
        shuffleMode = player.shuffleModeEnabled,
        queueTitle = currentQueueTitleProvider()
    )

    private suspend fun persistSnapshotIfLatest(
        prefs: PlaybackPrefs,
        seq: Long,
        requireUnpreemptedSchedule: Boolean,
        snapshot: PlaybackSnapshot
    ) {
        saveStateMutex.withLock {
            if (seq <= lastPersistedGeneration.get()) return@withLock
            if (requireUnpreemptedSchedule && seq != saveGeneration.get()) return@withLock
            prefs.savePlaybackState(
                snapshot.mediaId,
                snapshot.position,
                snapshot.index,
                snapshot.queueTitle,
                snapshot.repeatMode,
                snapshot.shuffleMode
            )
            lastPersistedGeneration.set(seq)
        }
    }

    fun savePlaybackQueue(player: Player) {
        val currentDb = dbProvider() ?: return
        val mediaItems = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }

        saveQueueJob?.cancel()
        saveQueueJob = serviceScope.launch(Dispatchers.IO) {
            delay(SAVE_QUEUE_DEBOUNCE_MS)
            currentDb.musicDao().updatePlaybackQueue(mediaItems.mapIndexed { index, item ->
                PlaybackQueueEntity(index, item.mediaId)
            })
        }
    }

    fun startPositionPersistence(player: Player) {
        positionPersistenceJob?.cancel()
        positionPersistenceJob = serviceScope.launch {
            while (isActive) {
                delay(POSITION_PERSISTENCE_INTERVAL_MS)
                savePlaybackState(player)
            }
        }
    }

    fun stopPositionPersistence() {
        positionPersistenceJob?.cancel()
        positionPersistenceJob = null
    }

    fun cancelAll() {
        saveGeneration.incrementAndGet()
        saveStateJob?.cancel()
        saveQueueJob?.cancel()
        stopPositionPersistence()
    }
}
