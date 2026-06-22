package de.lwp2070809.speculonic.data.prefs

import kotlinx.coroutines.flow.Flow

interface PlaybackPrefs {
    val mobilePlayAllowed: Flow<Boolean>
    val lastPosition: Flow<Long>
    val lastQueueIndex: Flow<Int>
    val lastQueueTitle: Flow<String?>
    val repeatMode: Flow<Int>
    val shuffleMode: Flow<Boolean>
    val lastSleepTimerMinutes: Flow<Int>
    val lastSleepTimerSongCount: Flow<Int>
    val skipSilenceEnabled: Flow<Boolean>
    val duckOnTransientFocusLoss: Flow<Boolean>
    val pauseOnAudioFocusLoss: Flow<Boolean>
    val syncPlaybackState: Flow<Boolean>

    suspend fun saveMobilePlayAllowed(allowed: Boolean)
    suspend fun savePlaybackState(
        songId: String?,
        position: Long,
        queueIndex: Int,
        queueTitle: String?,
        repeatMode: Int,
        shuffleMode: Boolean
    )
    suspend fun saveLastSleepTimerMinutes(minutes: Int)
    suspend fun saveLastSleepTimerSongCount(count: Int)
    suspend fun saveSyncPlaybackState(enabled: Boolean)
    suspend fun saveSkipSilenceEnabled(enabled: Boolean)
    suspend fun saveDuckOnTransientFocusLoss(enabled: Boolean)
    suspend fun savePauseOnAudioFocusLoss(enabled: Boolean)
}
