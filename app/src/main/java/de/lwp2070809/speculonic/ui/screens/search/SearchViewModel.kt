package de.lwp2070809.speculonic.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.model.SearchResult3
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val OPTIMISTIC_STAR_MARKER = "starred"
private const val SEARCH_DEBOUNCE_MS = 500L

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val isLocalFallback: Boolean = false,
    val results: SearchResult3 = SearchResult3(),
    val transcodeIncompatible: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SubsonicRepository,
    private val preferencesManager: PreferencesManager,
    private val downloadTracker: DownloadTracker,
    private val networkMonitor: NetworkMonitor
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

        observeSearchExecution()
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun observeSearchExecution() {
        val isEffectivelyOnlineFlow = combine(
            networkMonitor.networkStatus,
            preferencesManager.offlineModeEnabled
        ) { status, offline ->
            status.isOnline && !offline
        }.distinctUntilChanged()

        viewModelScope.launch {
            combine(
                searchQuery.debounce(SEARCH_DEBOUNCE_MS),
                isEffectivelyOnlineFlow,
                ::Pair
            )
                .distinctUntilChanged()
                .collectLatest { (query, isOnline) ->
                    executeSearch(query, isOnline)
                }
        }
    }

    private suspend fun executeSearch(query: String, isOnline: Boolean) {
        if (query.isBlank()) {
            _uiState.update {
                it.copy(results = SearchResult3(), isLoading = false, isLocalFallback = false, error = null)
            }
            return
        }

        _uiState.update { it.copy(isLoading = true, isLocalFallback = false, error = null) }
        if (!isOnline) {
            runLocalSearch(query = query, isFallback = false)
            return
        }

        try {
            val rawResults = repository.search(query)
            val resolvedResults = applyInFlightStarOverrides(rawResults)
            _uiState.update {
                it.copy(results = resolvedResults, isLoading = false, isLocalFallback = false, error = null)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.w("SearchViewModel: remote search failed, falling back to local search: ${e.message}")
            runLocalSearch(query = query, isFallback = true)
        }
    }

    private suspend fun runLocalSearch(query: String, isFallback: Boolean) {
        try {
            val localResults = repository.searchLocal(query)
            val resolvedResults = applyInFlightStarOverrides(localResults)
            _uiState.update {
                it.copy(
                    results = resolvedResults,
                    isLoading = false,
                    isLocalFallback = isFallback,
                    error = null
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _uiState.update {
                it.copy(
                    results = SearchResult3(),
                    isLoading = false,
                    isLocalFallback = isFallback,
                    error = e.message
                )
            }
        }
    }

    private fun applyInFlightStarOverrides(rawResults: SearchResult3): SearchResult3 {
        if (inFlightStarOverrides.isEmpty()) return rawResults
        val updatedSongs = rawResults.song.map { song ->
            val pending = inFlightStarOverrides[song.id]
            if (pending != null) {
                song.copy(starred = if (pending) (song.starred ?: OPTIMISTIC_STAR_MARKER) else null)
            } else {
                song
            }
        }
        return rawResults.copy(song = updatedSongs)
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
