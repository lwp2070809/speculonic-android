package de.lwp2070809.speculonic.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.model.SearchResult3
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val results: SearchResult3 = SearchResult3(),
    val error: String? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SubsonicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")

    init {
        
        viewModelScope.launch {
            DownloadTracker.downloadedSongIds.collectLatest { downloadedIds ->
                val currentResults = _uiState.value.results
                if (currentResults.song.isNotEmpty()) {
                    val updatedSongs = currentResults.song.map { song ->
                        song.copy(isFullyCached = downloadedIds.contains(song.id))
                    }
                    if (updatedSongs != currentResults.song) {
                        _uiState.value = _uiState.value.copy(
                            results = currentResults.copy(song = updatedSongs)
                        )
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
                        _uiState.value = _uiState.value.copy(results = SearchResult3(), isLoading = false, error = null)
                    } else {
                        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                        try {
                            val results = repository.search(query)
                            _uiState.value = _uiState.value.copy(results = results, isLoading = false, error = null)
                        } catch (e: Exception) {
                            if (e !is kotlinx.coroutines.CancellationException) {
                                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                            }
                        }
                    }
                }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        searchQuery.value = query
    }
}
