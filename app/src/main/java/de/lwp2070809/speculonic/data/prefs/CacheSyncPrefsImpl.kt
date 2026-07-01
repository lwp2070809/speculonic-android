package de.lwp2070809.speculonic.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import de.lwp2070809.speculonic.data.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CacheSyncPrefsImpl(private val context: Context) : CacheSyncPrefs {

    companion object {
        private val CACHE_LOCATION = stringPreferencesKey("cache_location")
        private val MAX_CACHE_SIZE = longPreferencesKey("max_cache_size")
        private val MAX_COVER_CACHE_SIZE = longPreferencesKey("max_cover_cache_size")
        private val SILENT_CACHE_ENABLED = booleanPreferencesKey("silent_cache_enabled")
        private val AUTO_EXPORT_SILENT_CACHE = booleanPreferencesKey("auto_export_silent_cache")
        private val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
        private val LAST_FULL_SYNC_TIME = longPreferencesKey("last_full_sync_time")
        private val SERVER_LAST_MODIFIED = longPreferencesKey("server_last_modified")
        private val IS_SYNCING = booleanPreferencesKey("is_syncing")
        private val SYNC_PROGRESS = stringPreferencesKey("sync_progress")
        private val SYNC_ERROR = stringPreferencesKey("sync_error")
        private val BACKGROUND_SYNC_ENABLED = booleanPreferencesKey("background_sync_enabled")
        private val LAST_CACHE_SCAN_TIME = longPreferencesKey("last_cache_scan_time")
        private val SYNC_COVER_ART_ON_FORCE = booleanPreferencesKey("sync_cover_art_on_force")
    }

    override val cacheLocation: Flow<String> = context.dataStore.data.map { it[CACHE_LOCATION] ?: "" }
    override val maxCacheSize: Flow<Long> = context.dataStore.data.map { it[MAX_CACHE_SIZE] ?: (1024L * 1024 * 1024) }
    override val maxCoverCacheSize: Flow<Long> = context.dataStore.data.map { it[MAX_COVER_CACHE_SIZE] ?: (512L * 1024 * 1024) }
    override val silentCacheEnabled: Flow<Boolean> = context.dataStore.data.map { it[SILENT_CACHE_ENABLED] ?: true }
    override val autoExportSilentCache: Flow<Boolean> = context.dataStore.data.map { it[AUTO_EXPORT_SILENT_CACHE] ?: false }
    override val lastSyncTime: Flow<Long> = context.dataStore.data.map { it[LAST_SYNC_TIME] ?: 0L }
    override val lastFullSyncTime: Flow<Long> = context.dataStore.data.map { it[LAST_FULL_SYNC_TIME] ?: 0L }
    override val serverLastModified: Flow<Long> = context.dataStore.data.map { it[SERVER_LAST_MODIFIED] ?: 0L }
    override val isSyncing: Flow<Boolean> = context.dataStore.data.map { it[IS_SYNCING] ?: false }
    override val syncProgress: Flow<String?> = context.dataStore.data.map { it[SYNC_PROGRESS] }
    override val syncError: Flow<String?> = context.dataStore.data.map { it[SYNC_ERROR] }
    override val backgroundSyncEnabled: Flow<Boolean> = context.dataStore.data.map { it[BACKGROUND_SYNC_ENABLED] ?: false }
    override val lastCacheScanTime: Flow<Long> = context.dataStore.data.map { it[LAST_CACHE_SCAN_TIME] ?: 0L }
    override val syncCoverArtOnForce: Flow<Boolean> = context.dataStore.data.map { it[SYNC_COVER_ART_ON_FORCE] ?: false }

    override suspend fun saveCacheLocation(location: String) {
        context.dataStore.edit { preferences ->
            preferences[CACHE_LOCATION] = location
        }
    }

    override suspend fun saveMaxCacheSize(size: Long) {
        context.dataStore.edit { preferences ->
            preferences[MAX_CACHE_SIZE] = size
        }
    }

    override suspend fun saveMaxCoverCacheSize(size: Long) {
        context.dataStore.edit { preferences ->
            preferences[MAX_COVER_CACHE_SIZE] = size
        }
    }

    override suspend fun saveSilentCacheEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SILENT_CACHE_ENABLED] = enabled
        }
    }

    override suspend fun saveAutoExportSilentCache(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_EXPORT_SILENT_CACHE] = enabled
        }
    }


    override suspend fun saveLastSyncTime(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SYNC_TIME] = time
        }
    }

    override suspend fun saveLastFullSyncTime(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_FULL_SYNC_TIME] = time
        }
    }

    override suspend fun saveServerLastModified(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_LAST_MODIFIED] = time
        }
    }

    override suspend fun saveIsSyncing(syncing: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_SYNCING] = syncing
        }
    }

    override suspend fun saveSyncProgress(progress: String?) {
        context.dataStore.edit { preferences ->
            if (progress == null) preferences.remove(SYNC_PROGRESS)
            else preferences[SYNC_PROGRESS] = progress
        }
    }

    override suspend fun saveSyncError(error: String?) {
        context.dataStore.edit { preferences ->
            if (error == null) preferences.remove(SYNC_ERROR)
            else preferences[SYNC_ERROR] = error
        }
    }

    override suspend fun saveBackgroundSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BACKGROUND_SYNC_ENABLED] = enabled
        }
    }

    override suspend fun saveLastCacheScanTime(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_CACHE_SCAN_TIME] = time
        }
    }

    override suspend fun saveSyncCoverArtOnForce(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SYNC_COVER_ART_ON_FORCE] = enabled
        }
    }
}
