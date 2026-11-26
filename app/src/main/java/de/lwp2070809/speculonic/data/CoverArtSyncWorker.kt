package de.lwp2070809.speculonic.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import java.util.concurrent.TimeUnit

@HiltWorker
class CoverArtSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SubsonicRepository
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        repository.ensureConfigured()
        if (!repository.isConfigured) return Result.success()
        val stopReason = repository.syncAllCoverArt { setProgress(workDataOf(CacheSyncWorker.STATUS to it)) }
        return Result.success(workDataOf(CacheSyncWorker.STATUS to stopReason))
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CoverArtSyncWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("CoverArtSync", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
