package de.lwp2070809.speculonic.domain.usecase

import de.lwp2070809.speculonic.di.NetworkModule
import de.lwp2070809.speculonic.domain.repository.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class TestConnectionUseCase @Inject constructor() {
    suspend operator fun invoke(url: String, user: String, pass: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val service = NetworkModule.provideSubsonicService(url)
            val authManager = AuthManager(user, pass.toCharArray())
            val (u, t, s) = authManager.getAuthParams()
            val response = service.ping(u, t, s)
            if (response.response.status == "ok") {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Ping failed: status is not ok"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
