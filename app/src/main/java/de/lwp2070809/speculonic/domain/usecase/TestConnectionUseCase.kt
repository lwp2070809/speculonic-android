package de.lwp2070809.speculonic.domain.usecase

import de.lwp2070809.speculonic.di.NetworkModule
import de.lwp2070809.speculonic.domain.repository.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class TestConnectionUseCase @Inject constructor() {
    suspend operator fun invoke(
        url: String,
        user: String,
        pass: String,
        allowInsecureConnections: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var testClient: okhttp3.OkHttpClient? = null
        val authManager = AuthManager(user, pass.toCharArray())
        try {
            val (service, client) = NetworkModule.createTestSubsonicServiceClient(
                baseUrl = url,
                allowInsecureConnections = allowInsecureConnections
            )
            testClient = client
            val (u, t, s) = authManager.getAuthParams()
            val response = service.ping(u, t, s)
            if (response.response.status == "ok") {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Ping failed: status is not ok"))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        } finally {
            authManager.clearPassword()
            testClient?.let { NetworkModule.evictClientConnections(it, shutdownExecutor = true) }
        }
    }
}
