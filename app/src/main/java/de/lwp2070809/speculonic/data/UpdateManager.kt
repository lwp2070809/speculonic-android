package de.lwp2070809.speculonic.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import de.lwp2070809.speculonic.BuildConfig
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class UpdateManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val httpClient: OkHttpClient
) {
    suspend fun checkForUpdates(manual: Boolean = false): UpdateResult = withContext(Dispatchers.IO) {
        if (!BuildConfig.UPDATE_CHECK_ENABLED || BuildConfig.GITHUB_REPO.isBlank()) {
            return@withContext UpdateResult.NotConfigured
        }
        
        LogManager.i("UpdateCheck: Starting check for updates from ${BuildConfig.GITHUB_REPO} (manual=$manual)")

        if (!manual) {
            val interval = preferencesManager.updateCheckInterval.first()
            if (interval == UpdateCheckInterval.DISABLED) {
                return@withContext UpdateResult.NotConfigured
            }

            val lastCheckTime = preferencesManager.lastUpdateCheckTime.first()
            val currentTime = System.currentTimeMillis()
            val intervalMs = when (interval) {
                UpdateCheckInterval.DAILY -> 24 * 60 * 60 * 1000L
                UpdateCheckInterval.WEEKLY -> 7 * 24 * 60 * 60 * 1000L
                else -> 0L
            }

            if (intervalMs > 0 && currentTime - lastCheckTime < intervalMs) {
                LogManager.i("UpdateCheck: Skipped, within interval ($interval)")
                return@withContext UpdateResult.NoUpdate
            }
        }

        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body.string()
                val json = Json { ignoreUnknownKeys = true }
                val release = json.parseToJsonElement(responseBody).jsonObject
                val tagName = release["tag_name"]?.jsonPrimitive?.content ?: ""
                val releaseUrl = release["html_url"]?.jsonPrimitive?.content ?: ""
                val releaseNotes = release["body"]?.jsonPrimitive?.content ?: ""
                
                preferencesManager.saveLastUpdateCheckTime(System.currentTimeMillis())
                
                val currentVersion = BuildConfig.VERSION_NAME
                if (isNewerVersion(currentVersion, tagName)) {
                    LogManager.i("UpdateCheck: Found new version $tagName (current: $currentVersion)")
                    return@withContext UpdateResult.UpdateAvailable(tagName, releaseUrl, releaseNotes)
                } else {
                    LogManager.i("UpdateCheck: Already up to date (current: $currentVersion, latest: $tagName)")
                    return@withContext UpdateResult.NoUpdate
                }
            }
            return@withContext UpdateResult.Error("Failed to fetch latest release")
        } catch (e: Exception) {
            LogManager.e("UpdateCheck: Failed", e)
            return@withContext UpdateResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentClean = current.replace("v", "").split("-")[0]
        val latestClean = latest.replace("v", "").split("-")[0]
        
        val currentParts = currentClean.split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = latestClean.split(".").map { it.toIntOrNull() ?: 0 }
        
        val size = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until size) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    fun openBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    sealed class UpdateResult {
        data class UpdateAvailable(val version: String, val url: String, val releaseNotes: String) : UpdateResult()
        data object NoUpdate : UpdateResult()
        data object NotConfigured : UpdateResult()
        data class Error(val message: String) : UpdateResult()
    }
}
