package com.goldfish.android.data.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import com.goldfish.android.data.repository.LocalLibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Status eines laufenden Scans. Per Library-Id getrackt, damit die UI auch
 * mehrere parallele Scans (unwahrscheinlich, aber sicher) anzeigen koennte.
 */
data class ScanProgress(
    val libraryId: Int,
    val running: Boolean = true,
    val totalFiles: Int = 0,        // 0 solange Walk noch nicht fertig ist
    val processedFiles: Int = 0,
    val currentFile: String = "",
    val errorMessage: String? = null,
    val phase: String = "scanning"  // "scanning" | "enriching" | "done"
)

/**
 * Scanner fuer lokale Bibliotheken. Walks die SAF-Tree-URI rekursiv ab,
 * filtert auf Video-Dateien, schreibt LocalItems in die DB. KEINE
 * Metadaten-Extraktion (kein MediaMetadataRetriever, kein TMDB) — das
 * kommt in Etappe 3+4.
 *
 * Behaelt user-state (watched/resumePosSec/favorite) beim Re-Scan via
 * findItemByUri-Match. Items deren URI in der Library nicht mehr existiert
 * werden NICHT geloescht (passive Tolerance — User kann manuell loeschen).
 */
@Singleton
class LocalScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: LocalLibraryRepository
) {
    // Pro Library-Id ein StateFlow. Wird von UI per libraryId observed.
    private val _progress = MutableStateFlow<Map<Int, ScanProgress>>(emptyMap())
    val progress: StateFlow<Map<Int, ScanProgress>> = _progress.asStateFlow()

    /**
     * Fuehrt einen Scan einer lokalen Library durch. Suspending — der
     * Caller wirft das in eine viewModelScope.launch / CoroutineScope.
     * UI observed `progress`-Map fuer Fortschritt.
     */
    suspend fun scan(library: LocalLibraryEntity) = withContext(Dispatchers.IO) {
        val libId = library.id
        try {
            setProgress(libId, ScanProgress(libId, running = true))
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(library.treeUri))
            if (tree == null || !tree.canRead()) {
                setProgress(libId, ScanProgress(libId, running = false,
                    errorMessage = "Ordner nicht zugaenglich. SAF-Berechtigung verloren?"))
                return@withContext
            }

            // Phase 1: gesamten Tree walken, Videos sammeln
            val collected = mutableListOf<Pair<DocumentFile, String>>()
            walk(tree, "", collected)
            setProgress(libId, ScanProgress(libId, running = true, totalFiles = collected.size))

            if (collected.isEmpty()) {
                setProgress(libId, ScanProgress(libId, running = false,
                    totalFiles = 0, processedFiles = 0))
                return@withContext
            }

            // Phase 2: pro File parsen + probend + LocalItem upserten,
            // User-State (watched/resume/favorite) bleibt beim Re-Scan
            // erhalten. Filename-Parser je nach Library-Kind:
            //   - tv     → parseEpisodeFile (erlaubt 3-/4-Ziffern-Codes)
            //   - movies → parseFile (strikt SxxExx)
            //   - private → parseFile (kein TMDB, aber Year-Strip macht Sinn)
            collected.forEachIndexed { idx, (file, relPath) ->
                val fname = file.name ?: ""
                setProgress(libId, ScanProgress(
                    libraryId = libId,
                    running = true,
                    totalFiles = collected.size,
                    processedFiles = idx,
                    currentFile = fname
                ))
                val uri = file.uri.toString()
                val existing = repository.findItemByUri(libId, uri)
                val parsed = if (library.kind == "tv") NameParser.parseEpisodeFile(fname)
                             else NameParser.parseFile(fname)
                // Probe nur fuer NEUE Items oder wenn mtime sich geaendert hat —
                // sonst kann ein Re-Scan auf grossen Libraries lange dauern.
                val needsProbe = existing == null ||
                    existing.modifiedTime != file.lastModified() ||
                    existing.width == 0 && existing.height == 0
                val probe = if (needsProbe) MediaProbe.probe(context, uri)
                            else MediaProbeResult(
                                width = existing!!.width,
                                height = existing.height,
                                durationSec = existing.durationSec,
                                videoCodec = existing.videoCodec,
                                audioCodec = existing.audioCodec,
                                releasedAtMs = existing.releasedAtMs
                            )
                // Frame-Thumbnail extrahieren wenn (a) noch keiner da ist oder
                // (b) die Datei laut Probe neu/geaendert ist. TMDB-Poster haben
                // Vorrang in der UI (LocalLibraryViewModel) — der Thumbnail ist
                // Fallback fuer Privat-Libs + un-angereicherte Items.
                val existingThumbValid = existing?.thumbnailPath?.let { File(it).exists() && File(it).length() > 0 } == true
                val thumbnailPath = if (existingThumbValid && !needsProbe) {
                    existing!!.thumbnailPath
                } else {
                    val atSec = if (probe.durationSec > 30) (probe.durationSec * 0.10).toLong()
                                else if (probe.durationSec > 5) 2L
                                else 0L
                    MediaProbe.extractFrame(context, uri, atSec)
                }
                val entity = LocalItemEntity(
                    id = existing?.id ?: 0,
                    libraryId = libId,
                    documentUri = uri,
                    relPath = relPath,
                    fileName = fname,
                    sizeBytes = file.length(),
                    modifiedTime = file.lastModified(),
                    parsedTitle = parsed.title.ifBlank { fname.substringBeforeLast('.', fname) },
                    parsedYear = parsed.year.takeIf { it > 0 },
                    parsedSeason = parsed.season.takeIf { parsed.isEpisode },
                    parsedEpisode = parsed.episode.takeIf { parsed.isEpisode },
                    // User-state aus existing erben
                    watched = existing?.watched ?: false,
                    resumePosSec = existing?.resumePosSec ?: 0.0,
                    favorite = existing?.favorite ?: false,
                    lastPlayedAt = existing?.lastPlayedAt ?: 0,
                    // TMDB-Anreicherung kommt in Etappe 4
                    metadataId = existing?.metadataId,
                    title = existing?.title,
                    year = existing?.year,
                    posterPath = existing?.posterPath,
                    overview = existing?.overview,
                    rating = existing?.rating,
                    // Probe-Ergebnis
                    width = probe.width,
                    height = probe.height,
                    durationSec = probe.durationSec,
                    videoCodec = probe.videoCodec,
                    audioCodec = probe.audioCodec,
                    thumbnailPath = thumbnailPath,
                    releasedAtMs = probe.releasedAtMs ?: existing?.releasedAtMs,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis()
                )
                repository.upsertItem(entity)
            }

            // Phase 3: Orphan-Cleanup — DB-Eintraege loeschen deren Files
            // nicht mehr im SAF-Tree existieren (User hat sie auf Disk
            // entfernt zwischen den Scans). Sonst stale Karten im Grid.
            val keepUris = collected.map { (file, _) -> file.uri.toString() }
            try { repository.deleteOrphans(libId, keepUris) } catch (_: Exception) {}

            setProgress(libId, ScanProgress(
                libraryId = libId,
                running = false,
                totalFiles = collected.size,
                processedFiles = collected.size
            ))
        } catch (e: Exception) {
            setProgress(libId, ScanProgress(
                libraryId = libId,
                running = false,
                errorMessage = e.message ?: "Unbekannter Fehler beim Scan"
            ))
        }
    }

    private fun walk(node: DocumentFile, currentPath: String, out: MutableList<Pair<DocumentFile, String>>) {
        if (node.isDirectory) {
            val name = node.name ?: ""
            // Sample-Ordner ueberspringen — gleiches Verhalten wie Server-Scanner.
            if (name.equals("Sample", ignoreCase = true) || name.equals("Samples", ignoreCase = true)) return
            for (child in node.listFiles()) {
                val childName = child.name ?: continue
                val nextPath = if (currentPath.isEmpty()) childName else "$currentPath/$childName"
                walk(child, nextPath, out)
            }
        } else if (node.isFile) {
            val name = node.name ?: return
            if (isVideoFile(name)) out.add(node to currentPath)
        }
    }

    private fun isVideoFile(name: String): Boolean {
        val lower = name.lowercase()
        return VIDEO_EXTENSIONS.any { lower.endsWith(".$it") }
    }

    /** Aktuellen Progress fuer eine Library nachschlagen (oder null). */
    fun progressFor(libraryId: Int): ScanProgress? = _progress.value[libraryId]

    /** Progress nach Abschluss aus der Map raus (UI ruft auf wenn der User
     *  "OK" auf dem Ergebnis-Toast/Dialog drueckt). */
    fun clearProgress(libraryId: Int) {
        _progress.update { it - libraryId }
    }

    fun setProgress(libId: Int, p: ScanProgress) {
        _progress.update { it + (libId to p) }
    }

    companion object {
        // Liste der Video-Extensions wie im Server-Scanner. Großzügig.
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mov", "mkv", "avi", "webm", "wmv", "flv", "ts", "mpg", "mpeg", "3gp", "vob"
        )
    }
}
