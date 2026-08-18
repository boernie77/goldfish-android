package com.goldfish.android.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Separate Datenbank fuer lokale (on-device) Bibliotheken. Bewusst getrennt
 * von der Goldfish-Server-Cache-DB ([AppDatabase]) — damit Schema-
 * Migrations unabhaengig sind und User-Daten klar zuordbar bleiben.
 *
 * Schema-Version 1 (initial):
 *  - local_libraries: User-definierte Bibliotheken (SAF-Tree-URIs)
 *  - local_items: gescannte Dateien einer lokalen Library
 */
@Entity(tableName = "local_libraries")
data class LocalLibraryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val kind: String, // "movies", "tv", "private"
    val treeUri: String, // SAF Tree-URI (persistent permission)
    val displayName: String, // sprechender Name für UI (z.B. "SD-Karte/Filme")
    val createdAt: Long,
    // Goldfish-Username des Erstellers — nur dieser User sieht die Lib.
    // NULL = "vor v4 angelegt" (legacy) → wird im LocalLibraryRepository
    // beim ersten Login eines Users automatisch ihm zugeordnet
    // (Migration kennt den User-Kontext nicht).
    val ownerUsername: String? = null
)

@Entity(tableName = "local_items")
data class LocalItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val libraryId: Int,
    val documentUri: String, // SAF Document-URI fuer's Abspielen
    val relPath: String, // relativ zum Library-Tree, '/' getrennt
    val fileName: String,
    val sizeBytes: Long = 0,
    val modifiedTime: Long = 0,
    // Aus Dateinamen geparst (Filename-Parser-Port)
    val parsedTitle: String = "",
    val parsedYear: Int? = null,
    val parsedSeason: Int? = null,
    val parsedEpisode: Int? = null,
    // TMDB-Anreicherung via Goldfish-Server (wenn online verfuegbar)
    val metadataId: Int? = null,
    val title: String? = null,
    val year: Int? = null,
    val posterPath: String? = null,
    val overview: String? = null,
    val rating: Double? = null,
    // Aus MediaMetadataRetriever (Video-Probe)
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Double = 0.0,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    // Per-User-Status (lokal)
    val watched: Boolean = false,
    val resumePosSec: Double = 0.0,
    val favorite: Boolean = false,
    // Extrahierter Frame als Vorschaubild (absoluter Pfad zur lokalen JPEG-
    // Datei). Wird beim Scan via MediaMetadataRetriever.getFrameAtTime gesetzt,
    // wenn das Item keinen TMDB-Poster hat — insbesondere fuer Privat-Libs.
    val thumbnailPath: String? = null,
    // Veroeffentlichungs-Datum aus dem Container-Metadaten gelesen (yt-dlp
    // schreibt z.B. den MKV-DATE-Tag im Format YYYYMMDD). Null wenn keiner
    // gefunden — UI verwendet dann mtime als Fallback fuer Datums-Sort.
    val releasedAtMs: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    // v5: lokaler "zuletzt abgespielt"-Timestamp (epoch ms). Wird beim Oeffnen
    // im lokalen Player gesetzt → treibt den Sort "Zuletzt gespielt". 0 = nie.
    val lastPlayedAt: Long = 0
) {
    /** Logischer Show-Identifier — gruppiert Episoden korrekt egal ob die
     *  Files in Show-Sub-Folders liegen oder flach im Lib-Root. Priorisiert
     *  TMDB-Titel (canonical bei Auto-Enrichment) > NameParser-Titel >
     *  Pfad-Segment > kompletter relPath. */
    fun showKey(): String {
        title?.takeIf { it.isNotBlank() }?.let { return it }
        parsedTitle.takeIf { it.isNotBlank() }?.let { return it }
        relPath.substringBefore('/').takeIf { it.isNotBlank() }?.let { return it }
        return relPath
    }
}

@Dao
interface LocalLibraryDao {
    @Query("SELECT * FROM local_libraries ORDER BY createdAt")
    fun observeAll(): Flow<List<LocalLibraryEntity>>

    /** Strict-Filter: NUR Libs des aktuellen Users. NULL-Libs erscheinen
     *  hier NICHT — sie werden vorher per Repository.claimUnownedFor
     *  dem allerersten Login-User assigned (one-shot via SharedPref). */
    @Query("SELECT * FROM local_libraries WHERE ownerUsername = :username ORDER BY createdAt")
    fun observeForUser(username: String): Flow<List<LocalLibraryEntity>>

    @Query("SELECT * FROM local_libraries WHERE ownerUsername IS NULL")
    suspend fun listUnowned(): List<LocalLibraryEntity>

    @Query("UPDATE local_libraries SET ownerUsername = :username WHERE ownerUsername IS NULL")
    suspend fun claimUnowned(username: String): Int

    @Query("UPDATE local_libraries SET ownerUsername = :newOwner WHERE id = :libraryId")
    suspend fun setOwner(libraryId: Int, newOwner: String?)

    /** Libs die NICHT dem User gehoeren (fuer "Andere Bibliotheken"-Section
     *  in Settings — sodass der User sie zumindest sehen + uebernehmen kann).
     *  Inkludiert NULL-Owners als "unbeansprucht". */
    @Query("SELECT * FROM local_libraries WHERE ownerUsername IS NULL OR ownerUsername != :username ORDER BY createdAt")
    fun observeNotForUser(username: String): Flow<List<LocalLibraryEntity>>

    @Query("SELECT * FROM local_libraries WHERE id = :id")
    suspend fun get(id: Int): LocalLibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(lib: LocalLibraryEntity): Long

    @Update
    suspend fun update(lib: LocalLibraryEntity)

    @Query("DELETE FROM local_libraries WHERE id = :id")
    suspend fun deleteById(id: Int)
}

@Dao
interface LocalItemDao {
    @Query("SELECT * FROM local_items WHERE libraryId = :libraryId ORDER BY parsedTitle COLLATE NOCASE")
    fun observeForLibrary(libraryId: Int): Flow<List<LocalItemEntity>>

    @Query("SELECT * FROM local_items WHERE libraryId = :libraryId")
    suspend fun listForLibrary(libraryId: Int): List<LocalItemEntity>

    @Query("SELECT * FROM local_items WHERE id = :id")
    suspend fun get(id: Int): LocalItemEntity?

    @Query("SELECT * FROM local_items WHERE libraryId = :libraryId AND documentUri = :uri LIMIT 1")
    suspend fun findByUri(libraryId: Int, uri: String): LocalItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LocalItemEntity): Long

    @Update
    suspend fun update(item: LocalItemEntity)

    // Gezielte Einzelspalten-Updates — vermeiden Read-Modify-Write-Clobber:
    // ein Ganzzeilen-update(item.copy(...)) mit veraltetem Snapshot wuerde
    // sonst parallel gesetzte Felder (z.B. lastPlayedAt) zuruecksetzen.
    @Query("UPDATE local_items SET lastPlayedAt = :ts WHERE id = :id")
    suspend fun setLastPlayed(id: Int, ts: Long)

    @Query("UPDATE local_items SET thumbnailPath = :path WHERE id = :id")
    suspend fun setThumbnailPath(id: Int, path: String?)

    @Query("DELETE FROM local_items WHERE libraryId = :libraryId")
    suspend fun deleteAllForLibrary(libraryId: Int)

    @Query("DELETE FROM local_items WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM local_items WHERE libraryId = :libraryId AND documentUri NOT IN (:keepUris)")
    suspend fun deleteOrphansForLibrary(libraryId: Int, keepUris: List<String>): Int

    @Query("SELECT COUNT(*) FROM local_items WHERE libraryId = :libraryId")
    suspend fun countForLibrary(libraryId: Int): Int

    /**
     * Liefert fileNames die in mindestens 2 unterschiedlichen Bibliotheken
     * (aus :libIds) vorkommen. Wird vom Vergleichs-Filter im LocalLibraryScreen
     * genutzt — Cards mit diesen Filenames bekommen einen roten Highlight-Rahmen.
     * Match-Kriterium ist NUR der Dateiname (keine Size/Hash) — passend zum
     * User-Erwartung „die Datei XY liegt doppelt rum".
     */
    @Query(
        """SELECT fileName FROM local_items
           WHERE libraryId IN (:libIds)
           GROUP BY fileName
           HAVING COUNT(DISTINCT libraryId) >= 2"""
    )
    suspend fun findDuplicateFileNames(libIds: List<Int>): List<String>
}

// v1 → v2: thumbnailPath fuer Frame-Vorschaubilder lokaler Items (vor allem
// Privat-Libs ohne TMDB-Anreicherung).
val LOCAL_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_items ADD COLUMN thumbnailPath TEXT")
    }
}

// v2 → v3: releasedAtMs fuer extrahierte Veroeffentlichungs-Datums aus
// Container-Metadaten (z.B. yt-dlp MKV-DATE-Tag). Bei Datums-Sort hat das
// Vorrang vor der File-mtime.
val LOCAL_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_items ADD COLUMN releasedAtMs INTEGER")
    }
}

// v3 → v4: ownerUsername-Spalte fuer User-Isolation. Existierende Libs
// bekommen NULL — werden beim ersten Login-User-Zugriff vom Repository
// automatisch dem dann eingeloggten User assignet (Migration kennt keinen
// User-Context).
val LOCAL_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_libraries ADD COLUMN ownerUsername TEXT")
    }
}

// v4 → v5: lastPlayedAt auf local_items fuer den Sort "Zuletzt gespielt".
val LOCAL_MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_items ADD COLUMN lastPlayedAt INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [LocalLibraryEntity::class, LocalItemEntity::class],
    version = 5,
    exportSchema = false
)
abstract class LocalAppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LocalLibraryDao
    abstract fun itemDao(): LocalItemDao
}
