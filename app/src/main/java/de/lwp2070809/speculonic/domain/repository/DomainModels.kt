package de.lwp2070809.speculonic.domain.repository

import kotlinx.serialization.Serializable

@Serializable
data class ServerCapabilities(
    val type: String? = null,
    val serverVersion: String? = null,
    val isOpenSubsonic: Boolean = false,
    val extensions: List<String> = emptyList()
)

sealed class DomainException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    class NetworkError(cause: Throwable) : DomainException("Network Error", cause)
}

class SafetyGuardException(message: String) : Exception(message)
