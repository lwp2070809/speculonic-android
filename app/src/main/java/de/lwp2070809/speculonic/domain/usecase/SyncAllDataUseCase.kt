package de.lwp2070809.speculonic.domain.usecase

import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.domain.repository.SyncOutcome
import de.lwp2070809.speculonic.network.ServerReachableManager
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
    ): SyncOutcome {
        if (!repository.isConfigured) {
            LogManager.d("SyncAllDataUseCase: syncAllData skipped because repository is not configured.")
            return SyncOutcome.SKIPPED_NOT_CONFIGURED
        }
        
        if (forceRefresh) {
            ServerReachableManager.reset()
        }
        
        
        try {
            repository.ping()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LogManager.w("SyncAllDataUseCase: ping failed before sync, proceeding anyway")
        }
        
        return repository.syncAllData(
            forceRefresh = forceRefresh,
            ignoreLastModified = ignoreLastModified,
            ignoreSafetyGuard = ignoreSafetyGuard,
            keepSyncingState = keepSyncingState,
            onProgress = onProgress
        )
    }
}
