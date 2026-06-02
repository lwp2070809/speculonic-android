package de.lwp2070809.speculonic.playback

import androidx.media3.common.Player
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.data.db.entities.PlaybackQueueEntity
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.util.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackStatePersistence(
    private val serviceScope: CoroutineScope,
    private val preferencesManagerProvider: () -> PreferencesManager?,
    private val dbProvider: () -> AppDatabase?,
    private val currentQueueTitleProvider: () -> String?,
    private val onQueueTitleRestored: (String?) -> Unit
) {
    private var saveStateJob: Job? = null
    private var positionPersistenceJob: Job? = null

    suspend fun restorePlaybackState(player: Player, repo: SubsonicRepository) {
        withContext(Dispatchers.IO) {
            val db = dbProvider() ?: return@withContext
            val prefs = preferencesManagerProvider() ?: return@withContext
            
            val savedQueue = db.musicDao().getPlaybackQueue()
            val lastRepeatMode = prefs.repeatMode.first()
            val lastShuffleMode = prefs.shuffleMode.first()
            val lastQueueTitle = prefs.lastQueueTitle.first()

            if (savedQueue.isNotEmpty()) {
                val mediaItems = savedQueue.map { it.toMediaItem(repo) }
                val lastIndex = prefs.lastQueueIndex.first().coerceIn(0, mediaItems.size - 1)
                val lastPosition = prefs.lastPosition.first()

                withContext(Dispatchers.Main) {
                    onQueueTitleRestored(lastQueueTitle)
                    player.repeatMode = lastRepeatMode
                    player.shuffleModeEnabled = lastShuffleMode
                    player.setMediaItems(mediaItems, lastIndex, lastPosition)
                    player.prepare()
                }
            } else {
                withContext(Dispatchers.Main) {
                    player.repeatMode = lastRepeatMode
                    player.shuffleModeEnabled = lastShuffleMode
                }
            }
        }
    }

    fun savePlaybackState(player: Player, immediate: Boolean = false) {
        val prefs = preferencesManagerProvider() ?: return
        val mediaId = player.currentMediaItem?.mediaId
        val position = player.currentPosition
        val index = player.currentMediaItemIndex
        val repeatMode = player.repeatMode
        val shuffleMode = player.shuffleModeEnabled
        val queueTitle = currentQueueTitleProvider()

        if (immediate) {
            saveStateJob?.cancel()
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                prefs.savePlaybackState(mediaId, position, index, queueTitle, repeatMode, shuffleMode)
            }
        } else {
            saveStateJob?.cancel()
            saveStateJob = serviceScope.launch(Dispatchers.IO) {
                delay(2000)
                prefs.savePlaybackState(mediaId, position, index, queueTitle, repeatMode, shuffleMode)
            }
        }
    }

    fun savePlaybackQueue(player: Player) {
        val currentDb = dbProvider() ?: return
        val mediaItems = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }

        serviceScope.launch(Dispatchers.IO) {
            currentDb.musicDao().updatePlaybackQueue(mediaItems.mapIndexed { index, item ->
                PlaybackQueueEntity(index, item.mediaId)
            })
        }
    }

    fun startPositionPersistence(player: Player) {
        positionPersistenceJob?.cancel()
        positionPersistenceJob = serviceScope.launch {
            while (isActive) {
                delay(15000)
                savePlaybackState(player)
            }
        }
    }

    fun stopPositionPersistence() {
        positionPersistenceJob?.cancel()
        positionPersistenceJob = null
    }

    fun cancelAll() {
        saveStateJob?.cancel()
        stopPositionPersistence()
    }
}
