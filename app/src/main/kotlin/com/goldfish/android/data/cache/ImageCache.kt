package com.goldfish.android.data.cache

import android.content.Context
import com.goldfish.android.data.api.ApiClientProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistenter Bild-Cache fuer Offline-Anzeige von Postern + Thumbs.
 *
 * Speichert Bytes pro URL unter [Context.filesDir]/image_cache/<sha256(url)>.
 * Idee: bei jedem Download (DownloadRepository) UND bei jedem Backfill-Lauf
 * werden die relevanten Bild-URLs (Video-Thumb + Metadata-Poster) parallel
 * runtergeladen. Im Offline-Mode geben die ViewModel-URL-Builder dann
 * `file://<localPath>` zurueck statt der Server-URL — Coil rendert das
 * direkt von Disk, kein Netz noetig.
 *
 * Nicht gecached: TMDB-Episoden-Stills (extern, nicht ueber unseren OkHttp-
 * Client geroutet, kommen aus der SeasonResponse die wir noch nicht
 * cachen). Spaeterer Schritt.
 */
@Singleton
class ImageCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClientProvider: ApiClientProvider
) {
    private val cacheDir: File by lazy {
        File(context.filesDir, "image_cache").also { it.mkdirs() }
    }

    // Pro URL ein Mutex, damit zwei parallele cache(url)-Aufrufe nicht
    // gleichzeitig denselben File schreiben. Sehr leichtgewichtig.
    private val locks = mutableMapOf<String, Mutex>()
    private val locksMutex = Mutex()

    private suspend fun lockFor(url: String): Mutex = locksMutex.withLock {
        locks.getOrPut(url) { Mutex() }
    }

    /** Liefert den lokalen Dateipfad fuer eine bereits gecachte URL, sonst null. */
    fun localPathForUrl(url: String): String? {
        val file = fileForUrl(url)
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    /** Liefert eine file://-URI fuer eine gecachte URL, sonst null. */
    fun localUriForUrl(url: String): String? = localPathForUrl(url)?.let { "file://$it" }

    /**
     * Laedt eine URL via OkHttp herunter und speichert sie lokal. Idempotent —
     * bereits gecachte URLs werden NICHT erneut geholt. Returns localPath oder
     * null bei Fehler. Best-effort: scheitert lautlos im Offline-Fall.
     */
    suspend fun cache(url: String): String? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        // Schnell-Check ohne Lock
        val cached = fileForUrl(url)
        if (cached.exists() && cached.length() > 0) return@withContext cached.absolutePath

        val lock = lockFor(url)
        lock.withLock {
            // Recheck unter Lock (Race vermeiden)
            if (cached.exists() && cached.length() > 0) return@withLock cached.absolutePath
            try {
                val client = apiClientProvider.okHttpClient
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withLock null
                    val body = resp.body ?: return@withLock null
                    val tmp = File(cached.parentFile, cached.name + ".tmp")
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                    if (tmp.length() == 0L) {
                        tmp.delete()
                        return@withLock null
                    }
                    if (cached.exists()) cached.delete()
                    tmp.renameTo(cached)
                    cached.absolutePath
                }
            } catch (_: Exception) { null }
        }
    }

    /** Mehrere URLs parallel cachen — beste-effort, jeder Einzelfehler
     *  wird ignoriert. Wird vom DownloadRepository nach Download +
     *  Backfill aufgerufen. */
    suspend fun cacheAll(urls: List<String>) {
        urls.forEach { cache(it) }
    }

    /** Liefert die optimal anzuzeigende URL: lokal wenn vorhanden, sonst
     *  Original. Wird in den ViewModel-URL-Builders verwendet. */
    fun preferLocal(originalUrl: String): String {
        if (originalUrl.isBlank()) return originalUrl
        return localUriForUrl(originalUrl) ?: originalUrl
    }

    private fun fileForUrl(url: String): File = File(cacheDir, sha256(url))

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
