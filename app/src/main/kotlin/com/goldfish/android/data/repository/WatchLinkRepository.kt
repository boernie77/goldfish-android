package com.goldfish.android.data.repository

import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.model.OtherUser
import com.goldfish.android.data.model.WatchLink
import com.goldfish.android.data.model.WatchLinkRequest
import org.json.JSONObject
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gesehen-Sync zwischen zwei Konten. Der Server spiegelt „gesehen" zwischen
 * zwei gegenseitig bestätigten Konten (Server-Repo: internal/api/watch_links.go)
 * und wirkt damit für alle Clients — hier liegen nur Anfrage, Bestätigung und
 * Trennen. Bewusst ohne ApiCache: nach jeder Aktion muss die Liste frisch sein.
 */
@Singleton
class WatchLinkRepository @Inject constructor(
    private val apiClientProvider: ApiClientProvider
) {
    suspend fun getWatchLinks(): Result<List<WatchLink>> =
        call { apiClientProvider.api.getWatchLinks() }.map { it ?: emptyList() }

    suspend fun getOtherUsers(): Result<List<OtherUser>> =
        call { apiClientProvider.api.getOtherUserNames() }.map { it ?: emptyList() }

    suspend fun request(username: String): Result<Unit> =
        call { apiClientProvider.api.requestWatchLink(WatchLinkRequest(username)) }.map { }

    suspend fun confirm(partnerId: Int): Result<Unit> =
        call { apiClientProvider.api.confirmWatchLink(partnerId) }.map { }

    /** Trennen bzw. Ablehnen — serverseitig derselbe Endpoint. */
    suspend fun unlink(partnerId: Int): Result<Unit> =
        call { apiClientProvider.api.deleteWatchLink(partnerId) }.map { }

    private inline fun <T, R> Result<T>.map(f: (T) -> R): Result<R> = when (this) {
        is Result.Success -> Result.Success(f(data))
        is Result.Error -> this
    }

    private suspend fun <T> call(block: suspend () -> Response<T>): Result<T?> {
        return try {
            val response = block()
            if (response.isSuccessful) Result.Success(response.body())
            else Result.Error(errorMessage(response))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    /** Der Server liefert Fehler als {"error": "…"} — diesen Text anzeigen,
     *  sonst den HTTP-Code. */
    private fun errorMessage(response: Response<*>): String {
        val raw = try { response.errorBody()?.string() } catch (_: Exception) { null }
        val msg = try {
            raw?.let { JSONObject(it).optString("error") }
        } catch (_: Exception) { null }
        return msg?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code()}"
    }
}
