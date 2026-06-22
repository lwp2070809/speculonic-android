package de.lwp2070809.speculonic.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import de.lwp2070809.speculonic.data.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PlaybackPrefsImpl(private val context: Context) : PlaybackPrefs {

    companion object {
        private val MOBILE_PLAY_ALLOWED = booleanPreferencesKey("mobile_play_allowed")
        private val LAST_SONG_ID = stringPreferencesKey("last_song_id")
        private val LAST_POSITION = longPreferencesKey("last_position")
        private val LAST_QUEUE_INDEX = intPreferencesKey("last_queue_index")
        private val LAST_QUEUE_TITLE = stringPreferencesKey("last_queue_title")
        private val REPEAT_MODE = intPreferencesKey("repeat_mode")
        private val SHUFFLE_MODE = booleanPreferencesKey("shuffle_mode")
        private val LAST_SLEEP_TIMER_MINUTES = intPreferencesKey("last_sleep_timer_minutes")
        private val LAST_SLEEP_TIMER_SONG_COUNT = intPreferencesKey("last_sleep_timer_song_count")
        private val SKIP_SILENCE_ENABLED = booleanPreferencesKey("skip_silence_enabled")
        private val DUCK_ON_TRANSIENT_FOCUS_LOSS = booleanPreferencesKey("duck_on_transient_focus_loss")
        private val PAUSE_ON_AUDIO_FOCUS_LOSS = booleanPreferencesKey("pause_on_audio_focus_loss")
        private val SYNC_PLAYBACK_STATE = booleanPreferencesKey("sync_playback_state")
    }

    override val mobilePlayAllowed: Flow<Boolean> = context.dataStore.data.map { it[MOBILE_PLAY_ALLOWED] ?: true }

    override suspend fun saveMobilePlayAllowed(allowed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[MOBILE_PLAY_ALLOWED] = allowed
        }
    }

    override val lastPosition: Flow<Long> = context.dataStore.data.map { it[LAST_POSITION] ?: 0L }
    override val lastQueueIndex: Flow<Int> = context.dataStore.data.map { it[LAST_QUEUE_INDEX] ?: 0 }
    override val lastQueueTitle: Flow<String?> = context.dataStore.data.map { it[LAST_QUEUE_TITLE] }
    
    override val repeatMode: Flow<Int> = context.dataStore.data.map {
        val mode = it[REPEAT_MODE] ?: androidx.media3.common.Player.REPEAT_MODE_ALL
        if (mode == androidx.media3.common.Player.REPEAT_MODE_OFF) androidx.media3.common.Player.REPEAT_MODE_ALL else mode
    }
    
    override val shuffleMode: Flow<Boolean> = context.dataStore.data.map { it[SHUFFLE_MODE] ?: false }

    override suspend fun savePlaybackState(
        songId: String?,
        position: Long,
        queueIndex: Int,
        queueTitle: String?,
        repeatMode: Int,
        shuffleMode: Boolean
    ) {
        context.dataStore.edit { preferences ->
            if (songId != null) {
                preferences[LAST_SONG_ID] = songId
            } else {
                preferences.remove(LAST_SONG_ID)
            }
            preferences[LAST_POSITION] = position
            preferences[LAST_QUEUE_INDEX] = queueIndex
            if (queueTitle != null) {
                preferences[LAST_QUEUE_TITLE] = queueTitle
            } else {
                preferences.remove(LAST_QUEUE_TITLE)
            }
            preferences[REPEAT_MODE] = repeatMode
            preferences[SHUFFLE_MODE] = shuffleMode
        }
    }

    override val lastSleepTimerMinutes: Flow<Int> = context.dataStore.data.map { it[LAST_SLEEP_TIMER_MINUTES] ?: 30 }
    override val lastSleepTimerSongCount: Flow<Int> = context.dataStore.data.map { it[LAST_SLEEP_TIMER_SONG_COUNT] ?: 10 }

    override suspend fun saveLastSleepTimerMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SLEEP_TIMER_MINUTES] = minutes
        }
    }

    override suspend fun saveLastSleepTimerSongCount(count: Int) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SLEEP_TIMER_SONG_COUNT] = count
        }
    }

    override val skipSilenceEnabled: Flow<Boolean> = context.dataStore.data.map { it[SKIP_SILENCE_ENABLED] ?: false }
    override val duckOnTransientFocusLoss: Flow<Boolean> = context.dataStore.data.map { it[DUCK_ON_TRANSIENT_FOCUS_LOSS] ?: true }
    override val pauseOnAudioFocusLoss: Flow<Boolean> = context.dataStore.data.map { it[PAUSE_ON_AUDIO_FOCUS_LOSS] ?: true }
    override val syncPlaybackState: Flow<Boolean> = context.dataStore.data.map { it[SYNC_PLAYBACK_STATE] ?: false }

    override suspend fun saveSkipSilenceEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SKIP_SILENCE_ENABLED] = enabled
        }
    }

    override suspend fun saveDuckOnTransientFocusLoss(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DUCK_ON_TRANSIENT_FOCUS_LOSS] = enabled
        }
    }

    override suspend fun savePauseOnAudioFocusLoss(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PAUSE_ON_AUDIO_FOCUS_LOSS] = enabled
        }
    }

    override suspend fun saveSyncPlaybackState(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SYNC_PLAYBACK_STATE] = enabled
        }
    }
}
