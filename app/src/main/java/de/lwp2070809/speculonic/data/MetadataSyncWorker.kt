package de.lwp2070809.speculonic.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.domain.usecase.SyncAllDataUseCase
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@HiltWorker
class MetadataSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SubsonicRepository,
    private val syncAllDataUseCase: SyncAllDataUseCase,
    private val preferencesManager: PreferencesManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val forceRefresh = inputData.getBoolean("force_refresh", false)
        val isQuickOnly = inputData.getBoolean("is_quick_only", false)
        val isBackground = inputData.getBoolean("is_background", true)
        
        val context = applicationContext
        
        
        if (isBackground && !preferencesManager.backgroundSyncEnabled.first()) {
            LogManager.d("MetadataSync: Background sync is disabled by user. Skipping.")
            return Result.success()
        }

        
        val lastSync = preferencesManager.lastSyncTime.first()
        val lastFullSync = preferencesManager.lastFullSyncTime.first()
        val currentTime = System.currentTimeMillis()

        
        val needsSync = forceRefresh || isQuickOnly || (currentTime - lastSync >= TimeUnit.HOURS.toMillis(12))
        if (!needsSync) {
            LogManager.d("MetadataSync: Interval rule not met. Skipping.")
            return Result.success()
        }

        
        
        val needsFullSync = forceRefresh || (!isQuickOnly && (currentTime - lastFullSync >= TimeUnit.DAYS.toMillis(3)))

        LogManager.i("MetadataSync: Starting synchronization (Full: $needsFullSync, QuickOnly: $isQuickOnly, Force: $forceRefresh)...")
        
        val db = AppDatabase.getDatabase(context)
        val serverUrl = preferencesManager.serverUrl.first()
        
        if (serverUrl.isBlank()) return Result.success()

        try {
            if (needsFullSync) {
                LogManager.i("MetadataSync: Executing deep synchronization...")
                syncAllDataUseCase(forceRefresh = forceRefresh, onProgress = { status ->
                    LogManager.d("MetadataSync Progress: $status")
                    setProgress(workDataOf("status" to status))
                })
            } else {
                LogManager.i("MetadataSync: Executing lightweight synchronization...")
                repository.quickSync(onProgress = { status ->
                    LogManager.d("MetadataSync Progress: $status")
                    setProgress(workDataOf("status" to status))
                })
            }

            LogManager.i("MetadataSync: Synchronization completed successfully.")
            return Result.success()
        } catch (e: Exception) {
            LogManager.e("MetadataSync: Failed to synchronize", e)
            return Result.retry()
        }
    }

    companion object {
        private const val SYNC_WORK_NAME = "MetadataSyncWork"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) 
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<MetadataSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .setInputData(workDataOf("is_background" to true))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            LogManager.i("MetadataSync: Scheduled periodic sync (12h, WiFi).")
        }

        fun runOnce(context: Context, forceRefresh: Boolean = false, isQuickOnly: Boolean = false) {
            val data = workDataOf("force_refresh" to forceRefresh, "is_quick_only" to isQuickOnly, "is_background" to false)
            val request = OneTimeWorkRequestBuilder<MetadataSyncWorker>()
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                SYNC_WORK_NAME + (if (isQuickOnly) "_Quick" else "_Once"),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
