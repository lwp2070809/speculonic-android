package de.lwp2070809.speculonic.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.data.PlayerBackgroundMode
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.domain.usecase.GetNowPlayingLyricsUseCase
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import javax.inject.Inject

data class NowPlayingUiState(
    val lyricsLines: List<LyricLine> = emptyList(),
    val rawLyrics: String? = null,
    val isLoadingLyrics: Boolean = false,
    val isStarred: Boolean = false,
    val currentSong: de.lwp2070809.speculonic.network.model.Song? = null,
    val lastSleepTimerMinutes: Int = 30,
    val lastSleepTimerSongCount: Int = 10,
    val playerBackgroundMode: PlayerBackgroundMode = PlayerBackgroundMode.GAUSSIAN_BLUR
)

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val repository: SubsonicRepository,
    private val getNowPlayingLyricsUseCase: GetNowPlayingLyricsUseCase,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

    private var currentSongId: String? = null
    private var starObservationJob: Job? = null
    private var lyricsJob: Job? = null

    init {
        
        viewModelScope.launch {
            val initialMode = preferencesManager.playerBackgroundMode.first()
            val initialSleepMinutes = preferencesManager.lastSleepTimerMinutes.first()
            val initialSleepSongCount = preferencesManager.lastSleepTimerSongCount.first()
            
            _uiState.value = _uiState.value.copy(
                playerBackgroundMode = initialMode,
                lastSleepTimerMinutes = initialSleepMinutes,
                lastSleepTimerSongCount = initialSleepSongCount
            )

            
            preferencesManager.playerBackgroundMode.collect { mode ->
                _uiState.value = _uiState.value.copy(playerBackgroundMode = mode)
            }
        }
    }

    fun updateSleepTimerPreferences(minutes: Int? = null, songCount: Int? = null) {
        viewModelScope.launch {
            minutes?.let {
                preferencesManager.saveLastSleepTimerMinutes(it)
                _uiState.value = _uiState.value.copy(lastSleepTimerMinutes = it)
            }
            songCount?.let {
                preferencesManager.saveLastSleepTimerSongCount(it)
                _uiState.value = _uiState.value.copy(lastSleepTimerSongCount = it)
            }
        }
    }

    fun updateCurrentSongId(songId: String): Boolean {
        if (currentSongId == songId) return false
        currentSongId = songId
        
        starObservationJob?.cancel()
        starObservationJob = viewModelScope.launch {
            var previousLocalUri: String? = null
            repository.getSongByIdFlow(songId).collectLatest { song ->
                _uiState.value = _uiState.value.copy(
                    isStarred = song?.starred != null,
                    currentSong = song
                )
                
                
                val currentLocalUri = song?.localUri
                if (previousLocalUri == null && currentLocalUri != null) {
                    if (_uiState.value.lyricsLines.isEmpty() && _uiState.value.rawLyrics.isNullOrBlank()) {
                        LogManager.d("NowPlayingViewModel: Song $songId finished caching, triggering lyrics reload")
                        lyricsSongId = null 
                        loadLyrics(song.artist ?: "", song.title, song.id)
                    }
                }
                previousLocalUri = currentLocalUri
            }
        }
        return true
    }

    private var lyricsSongId: String? = null

    fun loadLyrics(artist: String, title: String, songId: String) {
        updateCurrentSongId(songId)
        
        if (lyricsSongId == songId) return 
        lyricsSongId = songId
        
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingLyrics = true,
                lyricsLines = emptyList(),
                rawLyrics = null
            )
            try {
                val (raw, lines) = getNowPlayingLyricsUseCase(songId, artist, title)
                _uiState.value = _uiState.value.copy(
                    rawLyrics = raw,
                    lyricsLines = lines,
                    isLoadingLyrics = false
                )
                LogManager.d("NowPlayingViewModel: Loaded ${lines.size} lyric lines for $title")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LogManager.e("NowPlayingViewModel: Failed to load lyrics", e)
                _uiState.value = _uiState.value.copy(
                    isLoadingLyrics = false,
                    lyricsLines = emptyList(),
                    rawLyrics = null
                )
            }
        }
    }

    fun toggleFavorite() {
        val id = currentSongId ?: return
        val currentState = _uiState.value.isStarred
        viewModelScope.launch {
            repository.starSong(id, !currentState)
        }
    }
}
