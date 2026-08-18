package com.goldfish.android.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.cache.ImageCache
import com.goldfish.android.data.local.AppDatabase
import com.goldfish.android.data.local.DownloadEntity
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed class DownloadResult {
    data class Progress(val bytesDownloaded: Long, val totalBytes: Long) : DownloadResult()
    data class Success(val localPath: String) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val apiClientProvider: ApiClientProvider,
    private val offlineRepository: OfflineRepository,
    private val imageCache: ImageCache,
    private val settingsDataStore: SettingsDataStore
) {
    private val dao = database.downloadDao()

    // Application-Scope: Downloads laufen weiter, wenn der User ein ViewModel verlässt
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // itemId → Fortschritt (0f..1f, NaN = unbestimmt). Sichtbar für Library + Detail.
    private val _activeDownloads = MutableStateFlow<Map<Int, Float>>(emptyMap())
    val activeDownloads: StateFlow<Map<Int, Float>> = _activeDownloads.asStateFlow()

    // Letzter abgeschlossener Download (für globale Toasts)
    private val _lastResult = MutableStateFlow<Pair<Int, DownloadResult>?>(null)
    val lastResult: StateFlow<Pair<Int, DownloadResult>?> = _lastResult.asStateFlow()

    fun consumeLastResult() { _lastResult.value = null }

    @Volatile private var backfillRunning = false

    init {
        // Backfill startet erst, sobald der ApiClientProvider eine gueltige
        // Server-URL bekommen hat (HomeViewModel.init → settingsDataStore →
        // apiClientProvider.configure). Vorher waeren alle api.getItem()-
        // Calls in den Catch-Block gefallen → kein Backfill, Offline-Filter
        // bleibt leer fuer Bestands-Downloads.
        scope.launch {
            try {
                apiClientProvider.configured.first { it }
                triggerBackfill()
            } catch (_: Exception) { /* ignore — Backfill ist best-effort */ }
        }
    }

    /** Stoesst den Backfill an, wenn nicht schon einer laeuft. Wird zusaetz-
     *  lich aus ItemRepository.getLibraries() success gefeuert, damit der
     *  Backfill auch dann nochmal greift, wenn beim App-Start was schiefging
     *  (Auth nicht da, Verbindung kurz weg, etc.). */
    fun triggerBackfill() {
        if (backfillRunning) return
        scope.launch {
            backfillRunning = true
            try { backfillLegacyMetadata() }
            finally { backfillRunning = false }
        }
    }

    private suspend fun backfillLegacyMetadata() {
        val needed = try { dao.getDownloadsNeedingBackfill() } catch (_: Exception) { return }
        if (needed.isEmpty()) return
        for (d in needed) {
            val item = try {
                apiClientProvider.api.getItem(d.itemId).body() ?: continue
            } catch (_: Exception) { continue }
            try {
                dao.insertDownload(
                    d.copy(
                        libraryId = item.libraryId,
                        relPath = item.relPath ?: "",
                        itemJson = offlineRepository.serialize(item)
                    )
                )
            } catch (_: Exception) { /* DB-Fehler ueberspringen, nicht fatal */ }
            // Bild-Cache fuer dieses Item fuellen — non-blocking, lautlos
            // bei Fehler. Damit hat das Item nach Backfill auch sein Poster/
            // Thumb lokal verfuegbar fuer Offline-Anzeige.
            try { cacheItemImages(item) } catch (_: Exception) {}
        }
    }

    /** Sammelt alle relevanten Bild-URLs fuer ein Item und legt sie via
     *  ImageCache auf Disk ab. Wird aufgerufen wenn ein Download startet
     *  oder Backfill ein Item nachzieht. */
    private suspend fun cacheItemImages(item: Item) {
        val base = try {
            settingsDataStore.settings.first().serverUrl.trimEnd('/')
        } catch (_: Exception) { return }
        if (base.isBlank()) return
        val urls = mutableListOf<String>()
        // Auto-Video-Thumbnail (ffmpeg-Extraktion) — fuer FolderCard + Fallbacks
        urls += "$base/api/thumb/${item.id}"
        // TMDB-Poster ueber Metadata-ID — fuer VideoCard mit Movie-/Show-Poster
        item.metadataId?.takeIf { it > 0 }?.let { metaId ->
            urls += "$base/api/poster/metadata/$metaId"
        }
        imageCache.cacheAll(urls)
    }

    fun getAllDownloads() = dao.getAllDownloads()

    suspend fun getDownload(itemId: Int) = dao.getDownload(itemId)

    /** Markiert einen Download als "gerade abgespielt" (epoch ms) — fuer den
     *  Offline-Sort "Zuletzt abgespielt". No-op wenn kein Download existiert. */
    suspend fun markPlayed(itemId: Int) {
        try { dao.setLastPlayed(itemId, System.currentTimeMillis()) } catch (_: Exception) {}
    }

    fun isDownloaded(itemId: Int): Flow<Boolean> = dao.isDownloaded(itemId)

    fun getDownloadedItemIds(): Flow<List<Int>> = dao.getDownloadedItemIds()

    // Offline-Filter-Helfer: alle Library-IDs mit min. einem Download.
    fun getLibraryIdsWithDownloads(): Flow<List<Int>> = dao.getLibraryIdsWithDownloads()

    // Alle Downloads einer Library — fuer Folder-/Item-Filter pro Lib.
    fun getDownloadsForLibrary(libraryId: Int): Flow<List<DownloadEntity>> =
        dao.getDownloadsForLibrary(libraryId)

    suspend fun deleteDownload(itemId: Int) {
        val download = dao.getDownload(itemId)
        download?.let {
            val path = it.localPath
            if (path.startsWith("content://")) {
                try {
                    DocumentFile.fromSingleUri(context, Uri.parse(path))?.delete()
                } catch (_: Exception) { /* ignorieren — DB-Eintrag wird trotzdem entfernt */ }
            } else {
                File(path).delete()
            }
            dao.deleteDownloadById(itemId)
        }
    }

    /** Loescht ALLE heruntergeladenen Dateien — sowohl die SAF-/Disk-Files
     *  als auch die DB-Eintraege. Returnt die Anzahl tatsaechlich entfernter
     *  Eintraege. Best-effort: scheitert eine einzelne Datei, geht's weiter. */
    suspend fun deleteAllDownloads(): Int {
        val all = dao.getAllDownloadsNow()
        for (d in all) {
            val path = d.localPath
            if (path.startsWith("content://")) {
                try {
                    DocumentFile.fromSingleUri(context, Uri.parse(path))?.delete()
                } catch (_: Exception) { /* ignorieren */ }
            } else {
                try { File(path).delete() } catch (_: Exception) {}
            }
            try { dao.deleteDownloadById(d.itemId) } catch (_: Exception) {}
        }
        return all.size
    }

    suspend fun downloadItem(
        item: Item,
        downloadDir: File,
        onProgress: suspend (DownloadResult.Progress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val response = apiClientProvider.api.downloadItem(item.id)
            if (!response.isSuccessful) {
                return@withContext DownloadResult.Error("HTTP ${response.code()}")
            }
            val body = response.body() ?: return@withContext DownloadResult.Error("Leere Antwort")

            val ext = item.title.substringAfterLast(".", "mkv").lowercase()
            val safeTitle = item.displayTitle.replace(Regex("[<>:\"/\\\\|?*]"), "_")
            val filename = "$safeTitle.${ext.ifBlank { "mkv" }}"
            val destFile = File(downloadDir, filename)

            val totalBytes = body.contentLength()
            var bytesDownloaded = 0L

            body.byteStream().use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } != -1) {
                        output.write(buffer, 0, bytes)
                        bytesDownloaded += bytes
                        onProgress(DownloadResult.Progress(bytesDownloaded, totalBytes))
                    }
                }
            }

            val entity = DownloadEntity(
                itemId = item.id,
                localPath = destFile.absolutePath,
                fileSize = destFile.length(),
                downloadedAt = System.currentTimeMillis(),
                title = item.displayTitle,
                libraryId = item.libraryId,
                relPath = item.relPath ?: "",
                itemJson = offlineRepository.serialize(item)
            )
            dao.insertDownload(entity)
            // Poster + Thumb fuer Offline mit-cachen (best-effort, lautlos)
            try { cacheItemImages(item) } catch (_: Exception) {}
            DownloadResult.Success(destFile.absolutePath)
        } catch (e: Exception) {
            DownloadResult.Error(e.message ?: "Download fehlgeschlagen")
        }
    }

    /** Download in einen vom User per SAF gewählten Ordner (content://-URI). */
    suspend fun downloadItemToTree(
        item: Item,
        treeUri: Uri,
        onProgress: suspend (DownloadResult.Progress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext DownloadResult.Error("Ordner nicht zugänglich")

            val response = apiClientProvider.api.downloadItem(item.id)
            if (!response.isSuccessful) {
                return@withContext DownloadResult.Error("HTTP ${response.code()}")
            }
            val body = response.body() ?: return@withContext DownloadResult.Error("Leere Antwort")

            val ext = item.title.substringAfterLast(".", "mkv").lowercase()
            val safeTitle = item.displayTitle.replace(Regex("[<>:\"/\\\\|?*]"), "_")
            val filename = "$safeTitle.${ext.ifBlank { "mkv" }}"

            // Falls eine Datei mit diesem Namen schon existiert: vorher löschen
            tree.findFile(filename)?.delete()
            val docFile = tree.createFile("video/*", filename)
                ?: return@withContext DownloadResult.Error("Konnte Datei nicht anlegen")

            val totalBytes = body.contentLength()
            var bytesDownloaded = 0L

            body.byteStream().use { input ->
                context.contentResolver.openOutputStream(docFile.uri)?.use { output ->
                    val buffer = ByteArray(8192)
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } != -1) {
                        output.write(buffer, 0, bytes)
                        bytesDownloaded += bytes
                        onProgress(DownloadResult.Progress(bytesDownloaded, totalBytes))
                    }
                } ?: return@withContext DownloadResult.Error("Stream nicht öffenbar")
            }

            val savedUri = docFile.uri.toString()
            val entity = DownloadEntity(
                itemId = item.id,
                localPath = savedUri,
                fileSize = docFile.length(),
                downloadedAt = System.currentTimeMillis(),
                title = item.displayTitle,
                libraryId = item.libraryId,
                relPath = item.relPath ?: "",
                itemJson = offlineRepository.serialize(item)
            )
            dao.insertDownload(entity)
            try { cacheItemImages(item) } catch (_: Exception) {}
            DownloadResult.Success(savedUri)
        } catch (e: Exception) {
            DownloadResult.Error(e.message ?: "Download fehlgeschlagen")
        }
    }

    fun getDefaultDownloadDir(): File {
        return context.getExternalFilesDir("downloads")
            ?: File(context.filesDir, "downloads")
    }

    /**
     * Startet einen Download im Application-Scope. Überlebt Navigation und ViewModel-Lifetime.
     * Schreibt Fortschritt in [activeDownloads] und Endergebnis in [lastResult].
     *
     * Bevorzugt [treeUri] (SAF-Ordner). Fällt auf [downloadDir] zurück, sonst auf den Default.
     */
    fun startInBackground(item: Item, downloadDir: File? = null, treeUri: Uri? = null): Job {
        if (_activeDownloads.value.containsKey(item.id)) return Job().apply { complete() }
        return scope.launch {
            _activeDownloads.update { it + (item.id to 0f) }
            val result = if (treeUri != null) {
                downloadItemToTree(item, treeUri) { progress ->
                    val pct = if (progress.totalBytes > 0)
                        progress.bytesDownloaded.toFloat() / progress.totalBytes
                    else Float.NaN
                    _activeDownloads.update { it + (item.id to pct) }
                }
            } else {
                val dir = (downloadDir ?: getDefaultDownloadDir()).also { it.mkdirs() }
                downloadItem(item, dir) { progress ->
                    val pct = if (progress.totalBytes > 0)
                        progress.bytesDownloaded.toFloat() / progress.totalBytes
                    else Float.NaN
                    _activeDownloads.update { it + (item.id to pct) }
                }
            }
            _activeDownloads.update { it - item.id }
            _lastResult.value = item.id to result
        }
    }

    /** Veraltet — bitte [startInBackground] verwenden. */
    suspend fun startBackgroundDownload(item: Item) {
        startInBackground(item)
    }
}
