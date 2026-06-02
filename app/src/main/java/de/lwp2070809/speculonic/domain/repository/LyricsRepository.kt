package de.lwp2070809.speculonic.domain.repository

import android.content.Context
import android.util.LruCache
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.di.NetworkModule
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.util.LocalLyricsProvider
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LyricLine
import de.lwp2070809.speculonic.util.LyricsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LyricsRepository(
    private val context: Context,
    private val api: SubsonicService,
    private val musicDao: MusicDao,
    private val authManager: AuthManager
) {
    private val repositoryScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    
    private val negativeCache = LruCache<String, Boolean>(200)
    
    private val positiveCache = LruCache<String, Pair<String?, List<LyricLine>>>(50)
    
    
    private val mutexMap = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    suspend fun getLyricsData(
        songId: String, 
        artist: String?, 
        title: String?,
        hasSongLyricsExtension: Boolean
    ): Pair<String?, List<LyricLine>> {
        
        
        positiveCache.get(songId)?.let {
            LogManager.d("LyricsRepository: Using positive memory cache for $songId")
            return it
        }

        val mutex = mutexMap.getOrPut(songId) { Mutex() }
        return try {
            mutex.withLock {
                
                positiveCache.get(songId)?.let { return@withLock it }
                
                val result = fetchLyricsDataInternal(songId, artist, title, hasSongLyricsExtension)
                if (result.second.isNotEmpty() || !result.first.isNullOrBlank()) {
                    positiveCache.put(songId, result)
                }
                result
            }
        } finally {
            mutexMap.remove(songId)
        }
    }

    private suspend fun fetchLyricsDataInternal(
        songId: String, 
        artist: String?, 
        title: String?,
        hasSongLyricsExtension: Boolean
    ): Pair<String?, List<LyricLine>> {
        
        val song = musicDao.getSongById(songId)
        if (song?.localUri != null) {
            val lrc = LocalLyricsProvider.getLyricsFromLrcFile(context, song.localUri)
            if (!lrc.isNullOrBlank()) {
                val lines = LyricsParser.parse(lrc)
                if (lines.isNotEmpty()) {
                    LogManager.d("LyricsRepository: Using local LRC file for $songId")
                    return Pair(lrc, lines)
                }
            }
        }

        
        if (negativeCache.get(songId) == true) {
            LogManager.d("LyricsRepository: $songId is in negative cache, skipping network request")
            return Pair(null, emptyList())
        }

        
        if (NetworkModule.ServerReachableManager.isOfflineOrUnreachable()) {
            LogManager.d("LyricsRepository: Device is offline or server is unreachable. Blocking network lyrics request for $songId")
            return Pair(null, emptyList())
        }

        val (u, t, s) = authManager.getAuthParams()
        
        
        
        try {
            val response = api.getLyricsBySongId(songId, u, t, s)
            val lyricsList = response.response.lyricsList
            val structured = lyricsList?.structuredLyrics?.firstOrNull()
            if (structured != null) {
                val lines = LyricsParser.fromStructured(structured.line)
                
                val raw = LyricsParser.toLrcString(lines)
                
                if (song?.localUri != null && raw.isNotBlank()) {
                    repositoryScope.launch {
                        LocalLyricsProvider.saveLyricsToLrcFile(context, song.localUri, raw)
                    }
                }
                
                return Pair(raw, lines)
            }
            
            val unstructured = lyricsList?.lyrics?.firstOrNull()?.content
            if (!unstructured.isNullOrBlank()) {
                val lines = LyricsParser.parse(unstructured)
                
                if (song?.localUri != null) {
                    repositoryScope.launch {
                        LocalLyricsProvider.saveLyricsToLrcFile(context, song.localUri, unstructured)
                    }
                }
                
                return Pair(unstructured, lines)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("Failed to get lyrics via getLyricsBySongId, falling back to legacy API", e)
        }
        
        
        return try {
            val raw = api.getLyrics(artist, title, u, t, s).response.lyrics?.content
            val lines = LyricsParser.parse(raw)
            if (raw.isNullOrBlank() && lines.isEmpty()) {
                negativeCache.put(songId, true)
            } else if (song?.localUri != null && !raw.isNullOrBlank()) {
                repositoryScope.launch {
                    LocalLyricsProvider.saveLyricsToLrcFile(context, song.localUri, raw)
                }
            }
            Pair(raw, lines)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            
            negativeCache.put(songId, true)
            Pair(null, emptyList())
        }
    }
}

