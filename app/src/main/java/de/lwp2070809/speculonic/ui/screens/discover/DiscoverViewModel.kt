package de.lwp2070809.speculonic.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.domain.usecase.SyncAllDataUseCase
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.toMediaItem
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoverUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isInitialLoadComplete: Boolean = false,
    val pinnedPlaylists: List<de.lwp2070809.speculonic.network.model.Playlist> = emptyList(),
    val favoriteSongs: List<Song> = emptyList(),
    val favoriteAlbums: List<Album> = emptyList(),
    val recentlyAdded: List<Album> = emptyList(),
    val randomAlbums: List<Album> = emptyList(),
    val mostPlayed: List<Album> = emptyList(),
    val error: String? = null
)

private data class DiscoverDataPackage(
    val starredSongs: List<Song>,
    val pinnedPlaylists: List<de.lwp2070809.speculonic.network.model.Playlist>,
    val starredAlbums: List<Album>,
    val newest: List<Album>,
    val frequent: List<Album>,
    val random: List<Album>
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val repository: SubsonicRepository,
    private val syncAllDataUseCase: SyncAllDataUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()



    init {
        loadData()
        observeData()
        observeServerConfigChanges()
    }

    private fun observeServerConfigChanges() {
        viewModelScope.launch {
            repository.preferencesManagerGet.serverUrl.collectLatest { url ->
                if (url.isNotBlank() && !url.contains("unconfigured")) {
                    val hasData = repository.hasLocalData()
                    if (!hasData) {
                        LogManager.i("DiscoverViewModel: Server configuration detected ready for the first time - triggering initial full sync to light up home page!")
                        loadData()
                    }
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeData() {
        viewModelScope.launch {
            val starredSongsFlow = repository.getStarredFlow()
            val pinnedPlaylistsFlow = repository.getPinnedPlaylistsFlow()
            val starredAlbumsFlow = repository.getStarredAlbumsFlow()
            val newestFlow = repository.getAlbumsByListTypeFlow("newest")
            val frequentFlow = repository.getAlbumsByListTypeFlow("frequent")
            val randomFlow = repository.getAlbumsByListTypeFlow("random")

            var isFirst = true

            combine(
                combine(starredSongsFlow, pinnedPlaylistsFlow, starredAlbumsFlow) { a, b, c -> Triple(a, b, c) },
                combine(newestFlow, frequentFlow, randomFlow) { a, b, c -> Triple(a, b, c) }
            ) { firstTriple, secondTriple ->
                DiscoverDataPackage(
                    starredSongs = firstTriple.first,
                    pinnedPlaylists = firstTriple.second,
                    starredAlbums = firstTriple.third,
                    newest = secondTriple.first,
                    frequent = secondTriple.second,
                    random = secondTriple.third
                )
            }
            .distinctUntilChanged()
            .debounce {
                if (isFirst) {
                    isFirst = false
                    0L
                } else {
                    150L
                }
            }
            .collectLatest { data ->
                val hasAnyData = data.starredSongs.isNotEmpty() ||
                        data.pinnedPlaylists.isNotEmpty() ||
                        data.starredAlbums.isNotEmpty() ||
                        data.newest.isNotEmpty() ||
                        data.frequent.isNotEmpty() ||
                        data.random.isNotEmpty()

                _uiState.update { state ->
                    state.copy(
                        favoriteSongs = data.starredSongs.take(10),
                        pinnedPlaylists = data.pinnedPlaylists,
                        favoriteAlbums = data.starredAlbums.take(10),
                        recentlyAdded = data.newest.take(20),
                        mostPlayed = data.frequent.take(20),
                        randomAlbums = data.random.take(20),
                        isInitialLoadComplete = state.isInitialLoadComplete || hasAnyData
                    )
                }
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            val hasData = repository.hasLocalData()
            if (!hasData) {
                _uiState.update { it.copy(isLoading = true) }
                try {
                    syncAllDataUseCase()
                } catch (e: Exception) {
                    LogManager.e("DiscoverViewModel: initial sync failed", e)
                } finally {
                    _uiState.update { it.copy(isLoading = false, isInitialLoadComplete = true) }
                }
            }
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            LogManager.d("DiscoverViewModel: Targeted refresh triggered.")
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                repository.getAlbumList("newest", forceRefresh = true)
                repository.getAlbumList("frequent", forceRefresh = true)
                repository.getAlbumList("random", forceRefresh = true)
                repository.getStarred(forceRefresh = true)
                repository.getStarredAlbums(forceRefresh = true)
                
                _uiState.update { state ->
                    state.copy(error = null)
                }
                LogManager.i("DiscoverViewModel: Targeted refresh completed.")
            } catch (e: Exception) {
                LogManager.e("DiscoverViewModel: refreshData failed", e)
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun refreshFavoriteAlbums() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val starredAlbums = repository.getStarredAlbums(forceRefresh = true)
                _uiState.update { state ->
                    state.copy(
                        favoriteAlbums = if (starredAlbums.isNotEmpty()) starredAlbums.take(10) else state.favoriteAlbums,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                LogManager.e("DiscoverViewModel: refreshFavoriteAlbums failed", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun playFavoriteSong(song: Song, playbackController: PlaybackController) {
        viewModelScope.launch {
            try {
                val allFavoriteSongs = repository.getStarred()
                val mediaItems = allFavoriteSongs.map { it.toMediaItem(repository) }
                val index = allFavoriteSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                if (mediaItems.isNotEmpty()) {
                    playbackController.play(mediaItems, index, queueTitle = "Favorite")
                }
            } catch (e: Exception) {
                LogManager.e("DiscoverViewModel: Play favorite song failed", e)
            }
        }
    }
}
