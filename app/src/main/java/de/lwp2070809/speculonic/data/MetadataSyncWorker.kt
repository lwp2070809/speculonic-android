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
import de.lwp2070809.speculonic.domain.repository.IncompletePaginationException
import de.lwp2070809.speculonic.domain.repository.SafetyGuardException
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.domain.usecase.SyncAllDataUseCase
import de.lwp2070809.speculonic.network.model.SubsonicApiException
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
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

        if (!shouldProceedWithSync(isBackground, forceRefresh, isQuickOnly)) {
            return Result.success()
        }

        val lastFullSync = preferencesManager.lastFullSyncTime.first()
        val needsFullSync = forceRefresh ||
            (!isQuickOnly && (System.currentTimeMillis() - lastFullSync >= TimeUnit.DAYS.toMillis(FULL_SYNC_INTERVAL_DAYS)))

        LogManager.i(
            "MetadataSync: Starting synchronization " +
                "(Full: $needsFullSync, QuickOnly: $isQuickOnly, Force: $forceRefresh)..."
        )

        val serverUrl = preferencesManager.serverUrl.first()
        if (serverUrl.isBlank()) return Result.success()

        return try {
            executeSyncStrategy(needsFullSync, forceRefresh)
            LogManager.i("MetadataSync: Synchronization completed successfully.")
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val nonRetryable = isNonRetryableFailure(e)
            LogManager.e("MetadataSync: Failed to synchronize (nonRetryable=$nonRetryable, attempt=$runAttemptCount)", e)
            if (nonRetryable) Result.failure() else Result.retry()
        }
    }

    private suspend fun shouldProceedWithSync(
        isBackground: Boolean,
        forceRefresh: Boolean,
        isQuickOnly: Boolean
    ): Boolean {
        if (isBackground && !preferencesManager.backgroundSyncEnabled.first()) {
            LogManager.d("MetadataSync: Background sync is disabled by user. Skipping.")
            return false
        }
        val lastSync = preferencesManager.lastSyncTime.first()
        val needsSync = forceRefresh ||
            isQuickOnly ||
            (System.currentTimeMillis() - lastSync >= TimeUnit.HOURS.toMillis(QUICK_SYNC_INTERVAL_HOURS))
        if (!needsSync) {
            LogManager.d("MetadataSync: Interval rule not met. Skipping.")
            return false
        }
        return true
    }

    private suspend fun executeSyncStrategy(needsFullSync: Boolean, forceRefresh: Boolean) {
        if (needsFullSync) {
            LogManager.i("MetadataSync: Executing deep synchronization...")
            val outcome = syncAllDataUseCase(
                forceRefresh = forceRefresh,
                ignoreLastModified = true,
                onProgress = { status ->
                    LogManager.d("MetadataSync Progress: $status")
                    setProgress(workDataOf("status" to status))
                }
            )
            LogManager.i("MetadataSync: Deep synchronization completed with outcome=$outcome.")
        } else {
            LogManager.i("MetadataSync: Executing lightweight synchronization...")
            repository.quickSync(onProgress = { status ->
                LogManager.d("MetadataSync Progress: $status")
                setProgress(workDataOf("status" to status))
            })
        }
    }

    private fun isNonRetryableFailure(e: Exception): Boolean {
        return when {
            e is SafetyGuardException -> true
            e is IncompletePaginationException -> true
            e is IllegalArgumentException -> true
            e is SubsonicApiException && e.code in NON_RETRYABLE_SUBSONIC_CODES -> true
            runAttemptCount >= MAX_RETRY_ATTEMPTS -> true
            else -> false
        }
    }

    companion object {
        private const val SYNC_WORK_NAME = "MetadataSyncWork"
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val QUICK_SYNC_INTERVAL_HOURS = 12L
        private const val FULL_SYNC_INTERVAL_DAYS = 3L
        private val NON_RETRYABLE_SUBSONIC_CODES = setOf(10, 20, 30, 40, 41, 50, 60)

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<MetadataSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .setInputData(workDataOf("is_background" to true))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                syncRequest
            )
            LogManager.i("MetadataSync: Scheduled periodic sync (12h, Charging + WiFi).")
        }

        fun runOnce(
            context: Context,
            forceRefresh: Boolean = false,
            isQuickOnly: Boolean = false,
            existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
        ) {
            val data = workDataOf("force_refresh" to forceRefresh, "is_quick_only" to isQuickOnly, "is_background" to false)
            val request = OneTimeWorkRequestBuilder<MetadataSyncWorker>()
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                SYNC_WORK_NAME + (if (isQuickOnly) "_Quick" else "_Once"),
                existingWorkPolicy,
                request
            )
        }
    }
}
