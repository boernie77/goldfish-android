package com.goldfish.android.data.repository

import com.goldfish.android.data.api.ApiCache
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.api.PersistentCookieJar
import com.goldfish.android.data.model.AuthStatus
import com.goldfish.android.data.model.LoginRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class AuthResult {
    data class Success(val status: AuthStatus) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

@Singleton
class AuthRepository @Inject constructor(
    private val apiClientProvider: ApiClientProvider,
    private val cookieJar: PersistentCookieJar,
    private val apiCache: ApiCache
) {
    private val _authStatus = MutableStateFlow<AuthStatus?>(null)
    val authStatus: StateFlow<AuthStatus?> = _authStatus

    suspend fun login(username: String, password: String): AuthResult {
        return try {
            // Cache vom letzten User leeren — sonst sieht der neue User
            // die gecachte Library-Liste / Home-Daten des Vorgaengers.
            // Insbesondere ItemRepository.getLibraries() ist 5min gecached
            // und liefert sonst die ACL-Liste des falschen Users.
            apiCache.clear()
            val response = apiClientProvider.api.login(LoginRequest(username, password))
            if (response.isSuccessful) {
                val status = response.body() ?: AuthStatus(isAuthenticated = true, username = username)
                _authStatus.value = status.copy(isAuthenticated = true, username = username)
                AuthResult.Success(_authStatus.value!!)
            } else {
                val code = response.code()
                val msg = when (code) {
                    401 -> "Falscher Benutzername oder Passwort"
                    else -> "Fehler: HTTP $code"
                }
                AuthResult.Error(msg)
            }
        } catch (e: Exception) {
            AuthResult.Error("Verbindungsfehler: ${e.message}")
        }
    }

    suspend fun checkAuthStatus(): AuthStatus {
        return try {
            val response = apiClientProvider.api.authStatus()
            val status = if (response.isSuccessful) {
                response.body() ?: AuthStatus()
            } else {
                AuthStatus()
            }
            _authStatus.value = status
            status
        } catch (e: Exception) {
            AuthStatus().also { _authStatus.value = it }
        }
    }

    suspend fun logout() {
        try {
            apiClientProvider.api.logout()
        } catch (_: Exception) {}
        cookieJar.clearAll()
        // Cache leeren damit der NAECHSTE Login frische Daten holt und
        // nicht versehentlich die alte ACL-Liste sieht.
        apiCache.clear()
        _authStatus.value = AuthStatus()
    }

    fun hasSavedSession(): Boolean = cookieJar.hasSession()

    fun getCurrentStatus(): AuthStatus? = _authStatus.value
}
