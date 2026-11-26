package de.lwp2070809.speculonic.domain.repository

import android.util.LruCache
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.network.ServerReachableManager
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.util.LocalLyricsProvider
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LyricLine
import de.lwp2070809.speculonic.util.LyricsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LyricsRepository(
    private val api: SubsonicService,
    private val musicDao: MusicDao,
    private val authManager: AuthManager,
    private val localLyricsProvider: LocalLyricsProvider
) {
    private val repositoryScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    fun close() {
        repositoryScope.cancel()
    }

    private val negativeCache = LruCache<String, Boolean>(200)
    private val positiveCache = LruCache<String, Pair<String?, List<LyricLine>>>(50)
    private val activeFetches = mutableMapOf<String, Deferred<Pair<String?, List<LyricLine>>>>()

    suspend fun getLyricsData(
        songId: String, 
        artist: String?, 
        title: String?,
        hasSongLyricsExtension: Boolean
    ): Pair<String?, List<LyricLine>> {
        
        val request = synchronized(activeFetches) {
            positiveCache.get(songId)?.let { return it }
            activeFetches[songId]?.let { return@synchronized it }
            val fetch = repositoryScope.async(start = CoroutineStart.LAZY) {
                val result = fetchLyricsDataInternal(songId, artist, title, hasSongLyricsExtension)
                if (result.second.isNotEmpty() || !result.first.isNullOrBlank()) {
                    positiveCache.put(songId, result)
                }
                result
            }
            activeFetches[songId] = fetch
            fetch.invokeOnCompletion { synchronized(activeFetches) { activeFetches.remove(songId) } }
            fetch
        }
        return request.await()
    }

    private suspend fun fetchLyricsDataInternal(
        songId: String,
        artist: String?,
        title: String?,
        hasSongLyricsExtension: Boolean
    ): Pair<String?, List<LyricLine>> {
        val song = musicDao.getSongById(songId)
        val localResult = readLocalLyricsOrNull(songId, song?.localUri)
        if (localResult != null) return localResult

        if (negativeCache.get(songId) == true) {
            LogManager.d("LyricsRepository: $songId is in negative cache, skipping network request")
            return Pair(null, emptyList())
        }

        if (ServerReachableManager.isOfflineOrUnreachable()) {
            LogManager.d(
                "LyricsRepository: Device is offline or server is unreachable. " +
                    "Blocking network lyrics request for $songId"
            )
            return Pair(null, emptyList())
        }

        val (u, t, s) = authManager.getAuthParams()
        if (hasSongLyricsExtension) {
            fetchOpenSubsonicLyrics(songId, song?.localUri, u, t, s)?.let { return it }
        }
        return fetchLegacyLyrics(songId, artist, title, song?.localUri, Triple(u, t, s))
    }

    private suspend fun readLocalLyricsOrNull(
        songId: String,
        localUri: String?
    ): Pair<String?, List<LyricLine>>? {
        if (localUri == null) return null
        val lrc = localLyricsProvider.getLyricsFromLrcFile(localUri)
        if (lrc.isNullOrBlank()) return null
        val lines = LyricsParser.parse(lrc)
        LogManager.d("LyricsRepository: Using local LRC file for $songId")
        return Pair(lrc, lines)
    }

    private suspend fun fetchOpenSubsonicLyrics(
        songId: String,
        localUri: String?,
        u: String,
        t: String,
        s: String
    ): Pair<String?, List<LyricLine>>? {
        return try {
            val response = api.getLyricsBySongId(songId, u, t, s)
            val lyricsList = response.response.lyricsList
            val structured = lyricsList?.structuredLyrics?.firstOrNull { it.line.isNotEmpty() }
            if (structured != null) {
                val lines = if (structured.synced) LyricsParser.fromStructured(structured.line, structured.offset ?: 0) else emptyList()
                val raw = if (lines.isEmpty()) structured.line.joinToString("\n") { it.value } else LyricsParser.toLrcString(lines)
                if (raw.isNotBlank()) {
                    persistLrcAsync(localUri, raw)
                    return Pair(raw, lines)
                }
            }

            val unstructured = lyricsList?.lyrics?.firstOrNull()?.content
            if (!unstructured.isNullOrBlank()) {
                val lines = LyricsParser.parse(unstructured)
                persistLrcAsync(localUri, unstructured)
                return Pair(unstructured, lines)
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("Failed to get lyrics via getLyricsBySongId, falling back to legacy API", e)
            null
        }
    }

    private suspend fun fetchLegacyLyrics(
        songId: String,
        artist: String?,
        title: String?,
        localUri: String?,
        authParams: Triple<String, String, String>
    ): Pair<String?, List<LyricLine>> {
        val (u, t, s) = authParams
        return try {
            val response = api.getLyrics(artist, title, u, t, s).response
            if (response.status != "ok") return Pair(null, emptyList())
            val raw = response.lyrics?.content
            val lines = LyricsParser.parse(raw)
            if (raw.isNullOrBlank()) {
                negativeCache.put(songId, true)
            } else {
                persistLrcAsync(localUri, raw)
            }
            Pair(raw, lines)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.w("LyricsRepository: Failed to get lyrics from network for $songId: ${e.message}")
            Pair(null, emptyList())
        }
    }

    private fun persistLrcAsync(localUri: String?, rawLyrics: String) {
        if (localUri == null || rawLyrics.isBlank()) return
        repositoryScope.launch {
            localLyricsProvider.saveLyricsToLrcFile(localUri, rawLyrics)
        }
    }
}

