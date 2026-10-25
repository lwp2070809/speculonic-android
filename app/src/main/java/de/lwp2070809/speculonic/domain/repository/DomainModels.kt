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


class SafetyGuardException(message: String) : Exception(message)

/**
 * 当服务端返回 Subsonic Code 70 时抛出的专用领域异常，标识该歌单在服务器端已不存在或已被物理删除。
 */
class PlaylistNotFoundException(
    val playlistId: String,
    message: String = "Playlist not found on server"
) : Exception(message)

