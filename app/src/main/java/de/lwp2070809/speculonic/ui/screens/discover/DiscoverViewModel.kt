package de.lwp2070809.speculonic.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoverUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isInitialLoadComplete: Boolean = false,
    val pinnedPlaylists: List<de.lwp2070809.speculonic.network.model.Playlist> = emptyList(),
    val favoriteSongs: List<Song> = emptyList(),
    val favoriteAlbums: List<Album> = emptyList(),
    val allFavoriteAlbums: List<Album> = emptyList(),
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
    @ApplicationContext private val context: Context,
    private val repository: SubsonicRepository,
    private val syncAllDataUseCase: SyncAllDataUseCase,
    private val playbackController: de.lwp2070809.speculonic.playback.PlaybackController,
    private val preferencesManager: de.lwp2070809.speculonic.data.PreferencesManager
) : ViewModel() {

    private var loadDataJob: Job? = null
    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()



    init {
        // 进入启动首页时静默触发服务端连接探测与拓展能力发现
        viewModelScope.launch {
            try {
                repository.ping()
            } catch (e: Exception) {
                LogManager.e("DiscoverViewModel: Initial ping failed", e)
            }
        }
        loadData()
        observeData()
        observeServerConfigChanges()
    }

    private fun observeServerConfigChanges() {
        var isFirstEmission = true
        viewModelScope.launch {
            repository.serverUrlFlow.collectLatest { url ->
                val isFirst = isFirstEmission
                isFirstEmission = false
                if (url.isNotBlank() && !url.contains("unconfigured")) {
                    val hasData = repository.hasLocalData()
                    if (!hasData && (!isFirst || !_uiState.value.isLoading)) {
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
                        allFavoriteAlbums = data.starredAlbums,
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
        if (loadDataJob?.isActive == true || _uiState.value.isLoading) return
        loadDataJob = viewModelScope.launch {
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
                supervisorScope {
                    // 1. 核心任务：最新专辑（含曲目补全）与收藏数据
                    val newestDeferred = async { repository.refreshNewestAlbumsAndSyncSongs(windowSize = 20) }
                    val starredDeferred = async { repository.refreshStarredData(forceRefresh = true) }

                    // 2. 辅助推荐任务：最常播放与随机推荐（容错保护，避免影响核心数据）
                    val frequentDeferred = async {
                        runCatching { repository.getAlbumList("frequent", forceRefresh = true) }
                            .onFailure { LogManager.w("DiscoverViewModel: Failed to refresh frequent albums: ${it.message}") }
                    }
                    val randomDeferred = async {
                        runCatching { repository.getAlbumList("random", forceRefresh = true) }
                            .onFailure { LogManager.w("DiscoverViewModel: Failed to refresh random albums: ${it.message}") }
                    }

                    // 核心任务必须成功
                    newestDeferred.await()
                    starredDeferred.await()

                    // 等待辅助任务执行完成
                    frequentDeferred.await()
                    randomDeferred.await()
                }
                
                _uiState.update { state ->
                    state.copy(error = null)
                }
                LogManager.i("DiscoverViewModel: Targeted refresh completed successfully.")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LogManager.e("DiscoverViewModel: refreshData failed", e)
                _uiState.update { it.copy(error = e.localizedMessage ?: "刷新失败") }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun refreshFavoriteAlbums() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                repository.refreshStarredData(forceRefresh = true)
                _uiState.update { state ->
                    state.copy(
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LogManager.e("DiscoverViewModel: refreshFavoriteAlbums failed", e)
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "刷新失败") }
            }
        }
    }

    fun playFavoriteSong(song: Song) {
        viewModelScope.launch {
            try {
                val transcodeIncompatible = preferencesManager.transcodeIncompatibleFormats.first()
                val allFavoriteSongs = repository.getStarred()
                val playableSongs = allFavoriteSongs.filter {
                    de.lwp2070809.speculonic.util.MediaFormatUtils.isSongPlayable(it, transcodeIncompatible)
                }
                val index = playableSongs.indexOfFirst { it.id == song.id }
                if (index != -1) {
                    val mediaItems = playableSongs.map { it.toMediaItem(repository) }
                    if (mediaItems.isNotEmpty()) {
                        playbackController.play(mediaItems, index, queueTitle = context.getString(de.lwp2070809.speculonic.R.string.favorite))
                    }
                }
            } catch (e: Exception) {
                LogManager.e("DiscoverViewModel: Play favorite song failed", e)
            }
        }
    }

    fun toggleStarSong(songId: String, star: Boolean) {
        viewModelScope.launch {
            try {
                repository.starSong(songId, star)
            } catch (e: Exception) {
                LogManager.e("DiscoverViewModel: toggleStarSong failed", e)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
