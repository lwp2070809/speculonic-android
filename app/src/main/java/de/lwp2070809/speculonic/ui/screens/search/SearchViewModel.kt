package de.lwp2070809.speculonic.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.model.SearchResult3
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val OPTIMISTIC_STAR_MARKER = "starred"

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val results: SearchResult3 = SearchResult3(),
    val transcodeIncompatible: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SubsonicRepository,
    private val preferencesManager: PreferencesManager,
    private val downloadTracker: DownloadTracker
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SearchUiState(transcodeIncompatible = preferencesManager.getCachedTranscodeIncompatibleFormats())
    )
    val uiState = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")
    private var knownStarredIds: Set<String>? = null
    private val inFlightStarOverrides = HashMap<String, Boolean>()
    private val baselineStarredById = HashMap<String, String?>()
    private val starGenById = HashMap<String, Long>()

    init {
        viewModelScope.launch {
            preferencesManager.transcodeIncompatibleFormats.collectLatest { enabled ->
                _uiState.update { it.copy(transcodeIncompatible = enabled) }
            }
        }

        viewModelScope.launch {
            repository.getStarredFlow().collectLatest { starredSongs ->
                val newIds = starredSongs.mapTo(HashSet()) { it.id }
                val prevIds = knownStarredIds
                knownStarredIds = newIds
                val addedIds = if (prevIds == null) newIds else newIds - prevIds
                val removedIds = if (prevIds == null) emptySet() else prevIds - newIds
                if (addedIds.isEmpty() && removedIds.isEmpty()) return@collectLatest
                _uiState.update { state ->
                    val currentResults = state.results
                    if (currentResults.song.isEmpty()) return@update state
                    var changed = false
                    val updatedSongs = currentResults.song.map { song ->
                        val pending = inFlightStarOverrides[song.id]
                        val nextStarred = when {
                            pending != null -> if (pending) (song.starred ?: OPTIMISTIC_STAR_MARKER) else null
                            song.id in addedIds -> song.starred ?: OPTIMISTIC_STAR_MARKER
                            song.id in removedIds -> null
                            else -> song.starred
                        }
                        if (nextStarred != song.starred) {
                            changed = true
                            song.copy(starred = nextStarred)
                        } else {
                            song
                        }
                    }
                    if (changed) state.copy(results = currentResults.copy(song = updatedSongs)) else state
                }
            }
        }

        viewModelScope.launch {
            downloadTracker.downloadedSongIds.collectLatest { downloadedIds ->
                _uiState.update { state ->
                    val currentResults = state.results
                    if (currentResults.song.isNotEmpty()) {
                        val updatedSongs = currentResults.song.map { song ->
                            song.copy(isFullyCached = downloadedIds.contains(song.id))
                        }
                        if (updatedSongs != currentResults.song) {
                            state.copy(results = currentResults.copy(song = updatedSongs))
                        } else {
                            state
                        }
                    } else {
                        state
                    }
                }
            }
        }

        @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            searchQuery
                .debounce(500)
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _uiState.update { it.copy(results = SearchResult3(), isLoading = false, error = null) }
                    } else {
                        _uiState.update { it.copy(isLoading = true, error = null) }
                        try {
                            val rawResults = repository.search(query)
                            val updatedSongs = if (inFlightStarOverrides.isEmpty()) {
                                rawResults.song
                            } else {
                                rawResults.song.map { song ->
                                    val pending = inFlightStarOverrides[song.id]
                                    if (pending != null) {
                                        song.copy(starred = if (pending) (song.starred ?: OPTIMISTIC_STAR_MARKER) else null)
                                    } else {
                                        song
                                    }
                                }
                            }
                            val results = if (updatedSongs === rawResults.song) {
                                rawResults
                            } else {
                                rawResults.copy(song = updatedSongs)
                            }
                            _uiState.update { it.copy(results = results, isLoading = false, error = null) }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            _uiState.update { it.copy(results = SearchResult3(), isLoading = false, error = e.message) }
                        }
                    }
                }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        searchQuery.value = query
    }

    fun toggleStarSong(songId: String, star: Boolean) {
        val gen = (starGenById[songId] ?: 0L) + 1L
        starGenById[songId] = gen
        if (!baselineStarredById.containsKey(songId)) {
            baselineStarredById[songId] = _uiState.value.results.song.find { it.id == songId }?.starred
        }
        inFlightStarOverrides[songId] = star
        val targetMarker = if (star) (baselineStarredById[songId] ?: OPTIMISTIC_STAR_MARKER) else null
        _uiState.update { state -> applySongStarUpdate(state, songId, targetMarker) }

        viewModelScope.launch {
            val ok = try {
                repository.starSong(songId, star)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                LogManager.e("SearchViewModel: toggleStarSong failed", e)
                false
            }
            if (ok) {
                if (starGenById[songId] == gen) {
                    inFlightStarOverrides.remove(songId)
                    baselineStarredById.remove(songId)
                    starGenById.remove(songId)
                } else {
                    baselineStarredById[songId] = targetMarker
                }
            } else if (starGenById[songId] == gen) {
                inFlightStarOverrides.remove(songId)
                val rollbackStarred = baselineStarredById.remove(songId)
                starGenById.remove(songId)
                _uiState.update { state -> applySongStarUpdate(state, songId, rollbackStarred) }
            }
        }
    }

    private fun applySongStarUpdate(state: SearchUiState, songId: String, starred: String?): SearchUiState {
        val currentSongs = state.results.song
        if (currentSongs.none { it.id == songId && it.starred != starred }) return state
        val updatedSongs = currentSongs.map { song ->
            if (song.id == songId) song.copy(starred = starred) else song
        }
        return state.copy(results = state.results.copy(song = updatedSongs))
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
