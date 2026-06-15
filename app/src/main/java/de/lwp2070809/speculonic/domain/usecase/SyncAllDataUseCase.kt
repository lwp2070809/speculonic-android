package de.lwp2070809.speculonic.domain.usecase

import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.util.LogManager
import javax.inject.Inject


class SyncAllDataUseCase @Inject constructor(
    private val repository: SubsonicRepository
) {
    suspend operator fun invoke(
        forceRefresh: Boolean = false,
        ignoreLastModified: Boolean = false,
        ignoreSafetyGuard: Boolean = false,
        keepSyncingState: Boolean = false,
        onProgress: (suspend (String) -> Unit)? = null
    ) {
        if (!repository.isConfigured) {
            LogManager.d("SyncAllDataUseCase: syncAllData skipped because repository is not configured.")
            return
        }
        
        if (forceRefresh) {
            de.lwp2070809.speculonic.di.NetworkModule.ServerReachableManager.reset()
        }
        
        
        try {
            repository.ping()
        } catch (e: Exception) {
            LogManager.w("SyncAllDataUseCase: ping failed before sync, proceeding anyway")
        }
        
        val hasLocal = repository.hasLocalData()
        val syncManager = repository.syncManagerGet
        val serverCapabilities = repository.serverCapabilitiesGet
        val musicDao = repository.musicDaoGet
        val preferencesManager = repository.preferencesManagerGet

        syncManager.syncAllData(
            serverCapabilities = serverCapabilities,
            forceRefresh = forceRefresh,
            ignoreLastModified = ignoreLastModified,
            ignoreSafetyGuard = ignoreSafetyGuard,
            hasLocalData = hasLocal,
            keepSyncingState = keepSyncingState,
            onProgress = onProgress,
            onSyncComplete = { currentTime, serverLastModified ->
                repository.getStarred(forceRefresh = true)
                
                repository.refreshAlbumList("newest")
                repository.refreshAlbumList("frequent")
                repository.refreshAlbumList("random")
                
                musicDao.deleteOrphanedAlbums()
                preferencesManager.saveLastSyncTime(currentTime)
                if (serverLastModified != 0L) {
                    preferencesManager.saveServerLastModified(serverLastModified)
                }
            }
        )

        
        preferencesManager.saveLastFullSyncTime(System.currentTimeMillis())

        
        if (forceRefresh || !hasLocal) {
            repository.preloadPlaylistsAndSongs()
            repository.getStarred(forceRefresh = true)
        }
    }
}
