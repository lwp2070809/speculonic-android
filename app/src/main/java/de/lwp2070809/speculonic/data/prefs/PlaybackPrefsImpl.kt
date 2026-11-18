package de.lwp2070809.speculonic.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import de.lwp2070809.speculonic.data.dataStore
import kotlinx.coroutines.flow.distinctUntilChanged
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
        private val PAUSE_ON_VOLUME_ZERO = booleanPreferencesKey("pause_on_volume_zero")
        private val DUCK_ON_TRANSIENT_FOCUS_LOSS = booleanPreferencesKey("duck_on_transient_focus_loss")
        private val PAUSE_ON_AUDIO_FOCUS_LOSS = booleanPreferencesKey("pause_on_audio_focus_loss")
        private val SYNC_PLAYBACK_STATE = booleanPreferencesKey("sync_playback_state")
        private val TRANSCODE_INCOMPATIBLE_FORMATS = booleanPreferencesKey("transcode_incompatible_formats")
        private val TARGET_TRANSCODE_FORMAT = stringPreferencesKey("target_transcode_format")
        private const val SYNC_PREFS_NAME = "speculonic_playback_sync_prefs"
        private const val KEY_TRANSCODE_INCOMPATIBLE = "transcode_incompatible_formats"
    }

    private val syncPrefs by lazy {
        context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val cachedTranscodeIncompatible = java.util.concurrent.atomic.AtomicBoolean(
        context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRANSCODE_INCOMPATIBLE, false)
    )

    override fun getCachedTranscodeIncompatibleFormats(): Boolean = cachedTranscodeIncompatible.get()

    override val mobilePlayAllowed: Flow<Boolean> = context.dataStore.data.map { it[MOBILE_PLAY_ALLOWED] ?: true }.distinctUntilChanged()
    override val lastSongId: Flow<String?> = context.dataStore.data.map { it[LAST_SONG_ID] }.distinctUntilChanged()

    override suspend fun saveMobilePlayAllowed(allowed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[MOBILE_PLAY_ALLOWED] = allowed
        }
    }

    override val lastPosition: Flow<Long> = context.dataStore.data.map { it[LAST_POSITION] ?: 0L }.distinctUntilChanged()
    override val lastQueueIndex: Flow<Int> = context.dataStore.data.map { it[LAST_QUEUE_INDEX] ?: 0 }.distinctUntilChanged()
    override val lastQueueTitle: Flow<String?> = context.dataStore.data.map { it[LAST_QUEUE_TITLE] }.distinctUntilChanged()
    
    override val repeatMode: Flow<Int> = context.dataStore.data.map {
        val mode = it[REPEAT_MODE] ?: androidx.media3.common.Player.REPEAT_MODE_ALL
        if (mode == androidx.media3.common.Player.REPEAT_MODE_OFF) androidx.media3.common.Player.REPEAT_MODE_ALL else mode
    }.distinctUntilChanged()
    
    override val shuffleMode: Flow<Boolean> = context.dataStore.data.map { it[SHUFFLE_MODE] ?: false }.distinctUntilChanged()

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

    override val lastSleepTimerMinutes: Flow<Int> = context.dataStore.data.map { it[LAST_SLEEP_TIMER_MINUTES] ?: 30 }.distinctUntilChanged()
    override val lastSleepTimerSongCount: Flow<Int> =
        context.dataStore.data.map { it[LAST_SLEEP_TIMER_SONG_COUNT] ?: 10 }.distinctUntilChanged()

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

    override val skipSilenceEnabled: Flow<Boolean> = context.dataStore.data.map { it[SKIP_SILENCE_ENABLED] ?: false }.distinctUntilChanged()
    override val pauseOnVolumeZero: Flow<Boolean> = context.dataStore.data.map { it[PAUSE_ON_VOLUME_ZERO] ?: false }.distinctUntilChanged()
    override val duckOnTransientFocusLoss: Flow<Boolean> =
        context.dataStore.data.map { it[DUCK_ON_TRANSIENT_FOCUS_LOSS] ?: true }.distinctUntilChanged()
    override val pauseOnAudioFocusLoss: Flow<Boolean> =
        context.dataStore.data.map { it[PAUSE_ON_AUDIO_FOCUS_LOSS] ?: true }.distinctUntilChanged()
    override val syncPlaybackState: Flow<Boolean> = context.dataStore.data.map { it[SYNC_PLAYBACK_STATE] ?: false }.distinctUntilChanged()
    override val transcodeIncompatibleFormats: Flow<Boolean> = context.dataStore.data.map {
        val enabled = it[TRANSCODE_INCOMPATIBLE_FORMATS] ?: false
        if (cachedTranscodeIncompatible.getAndSet(enabled) != enabled) {
            syncPrefs.edit().putBoolean(KEY_TRANSCODE_INCOMPATIBLE, enabled).apply()
        }
        enabled
    }.distinctUntilChanged()
    override val targetTranscodeFormat: Flow<String> =
        context.dataStore.data.map { it[TARGET_TRANSCODE_FORMAT] ?: "mp3" }.distinctUntilChanged()

    override suspend fun saveSkipSilenceEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SKIP_SILENCE_ENABLED] = enabled
        }
    }

    override suspend fun savePauseOnVolumeZero(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PAUSE_ON_VOLUME_ZERO] = enabled
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

    override suspend fun saveTranscodeIncompatibleFormats(enabled: Boolean) {
        cachedTranscodeIncompatible.set(enabled)
        syncPrefs.edit().putBoolean(KEY_TRANSCODE_INCOMPATIBLE, enabled).apply()
        context.dataStore.edit { preferences ->
            preferences[TRANSCODE_INCOMPATIBLE_FORMATS] = enabled
        }
    }

    override suspend fun saveTargetTranscodeFormat(format: String) {
        context.dataStore.edit { preferences ->
            preferences[TARGET_TRANSCODE_FORMAT] = format
        }
    }
}
