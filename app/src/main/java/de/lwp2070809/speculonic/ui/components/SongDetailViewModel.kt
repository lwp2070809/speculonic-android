package de.lwp2070809.speculonic.ui.components

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.model.Song
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class SongDetailViewModel @Inject constructor(
    private val repository: SubsonicRepository
) : ViewModel() {

    fun getSongEntityByIdFlow(songId: String): Flow<SongEntity?> {
        return repository.getSongEntityByIdFlow(songId)
    }

    suspend fun getSongRemote(songId: String): Result<Song> {
        return repository.getSongRemote(songId)
    }
}
