package de.lwp2070809.speculonic.data.prefs

import kotlinx.coroutines.flow.Flow

interface CacheSyncPrefs {
    val cacheLocation: Flow<String>
    val maxCacheSize: Flow<Long>
    val maxCoverCacheSize: Flow<Long>
    val silentCacheEnabled: Flow<Boolean>
    val autoExportSilentCache: Flow<Boolean>
    val lastSyncTime: Flow<Long>
    val lastFullSyncTime: Flow<Long>
    val serverLastModified: Flow<Long>
    val isSyncing: Flow<Boolean>
    val syncProgress: Flow<String?>
    val syncError: Flow<String?>
    val backgroundSyncEnabled: Flow<Boolean>
    val lastCacheScanTime: Flow<Long>
    val syncCoverArtOnForce: Flow<Boolean>

    suspend fun saveCacheLocation(location: String)
    suspend fun saveMaxCacheSize(size: Long)
    suspend fun saveMaxCoverCacheSize(size: Long)
    suspend fun saveSilentCacheEnabled(enabled: Boolean)
    suspend fun saveAutoExportSilentCache(enabled: Boolean)
    suspend fun saveLastSyncTime(time: Long)
    suspend fun saveLastFullSyncTime(time: Long)
    suspend fun saveServerLastModified(time: Long)
    suspend fun saveIsSyncing(syncing: Boolean)
    suspend fun saveSyncProgress(progress: String?)
    suspend fun saveSyncError(error: String?)
    suspend fun saveBackgroundSyncEnabled(enabled: Boolean)
    suspend fun saveLastCacheScanTime(time: Long)
    suspend fun saveSyncCoverArtOnForce(enabled: Boolean)
}
