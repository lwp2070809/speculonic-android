package de.lwp2070809.speculonic.domain.repository

import kotlinx.serialization.Serializable

@Serializable
data class ServerCapabilities(
    val type: String? = null,
    val serverVersion: String? = null,
    val isOpenSubsonic: Boolean = false,
    val extensions: List<String> = emptyList(),
    val subsonicApiVersion: String? = null
)

class SafetyGuardException(
    val entity: Entity,
    val remoteCount: Int,
    val localCount: Int,
    val reason: Reason = Reason.DROP_RATIO_EXCEEDED,
    val candidateCount: Int = 0
) : Exception(
    "Sync stopped for $entity (reason=$reason, remote=$remoteCount, local=$localCount, candidates=$candidateCount)"
) {
    enum class Entity { SONGS, ALBUMS, ARTISTS }
    enum class Reason { DROP_RATIO_EXCEEDED, VERIFY_CANDIDATES_EXCEEDED }
}

internal class IncompletePaginationException(message: String) : Exception(message)

/**
 * 当服务端返回 Subsonic Code 70 时抛出的专用领域异常，标识该歌单在服务器端已不存在或已被物理删除。
 */
class PlaylistNotFoundException(
    val playlistId: String,
    message: String = "Playlist not found on server"
) : Exception(message)
