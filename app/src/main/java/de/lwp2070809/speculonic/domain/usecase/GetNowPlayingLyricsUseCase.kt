package de.lwp2070809.speculonic.domain.usecase

import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.util.LyricLine
import javax.inject.Inject


class GetNowPlayingLyricsUseCase @Inject constructor(
    private val repository: SubsonicRepository
) {
    suspend operator fun invoke(
        songId: String,
        artist: String?,
        title: String?
    ): Pair<String?, List<LyricLine>> {
        val lyricsRepository = repository.lyricsRepositoryGet
        val serverCapabilities = repository.serverCapabilitiesGet
        val hasSongLyricsExtension = serverCapabilities.extensions.contains("songLyrics")
        return lyricsRepository.getLyricsData(songId, artist, title, hasSongLyricsExtension)
    }
}
