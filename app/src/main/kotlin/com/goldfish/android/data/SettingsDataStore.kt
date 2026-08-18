package com.goldfish.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    // Default leer — User trägt seine eigene Goldfish-URL beim ersten Start
    // im Settings-Screen ein. Persistierte SharedPrefs werden NICHT durch
    // den Default überschrieben (s. Flow-Lese-Logik unten).
    val serverUrl: String = "",
    val downloadDir: String = "",          // Legacy: absoluter File-Pfad (App-internal)
    val downloadTreeUri: String = "",      // SAF Tree-URI (vom Ordner-Picker)
    val downloadTreeDisplayName: String = "",  // sprechender Name fürs UI
    val cacheSizeBytes: Long = 200L * 1024 * 1024,
    // Offline-Filter: wenn true, zeigt Home-/Library-/Folder-Listen nur
    // Inhalte, fuer die ein lokaler Download existiert. Persistent, gilt
    // app-weit. Toggle als Chip im HomeScreen.
    val offlineOnly: Boolean = false,
    // IDs der lokalen Bibliotheken, die im Vergleichs-Filter beruecksichtigt
    // werden sollen (User-Auswahl in Settings → "Lokale Bibliotheken"). Leere
    // Menge = Filter ist deaktiviert (alle Markierungen wie ohne Vergleich).
    val compareLocalLibraryIds: Set<Int> = emptySet(),
    // Zwei Server-Bibliotheken, die der User virtuell zusammenlegen möchte.
    // Leere Menge = Feature deaktiviert. Genau 2 IDs = aktiv.
    val mergedServerLibraryIds: Set<Int> = emptySet(),
    // Zwei lokale (SAF) Bibliotheken zusammenlegen.
    val mergedLocalLibraryIds: Set<Int> = emptySet()
)

enum class CacheSize(val bytes: Long, val label: String) {
    LOW(50L * 1024 * 1024, "Klein (50 MB)"),
    MEDIUM(200L * 1024 * 1024, "Mittel (200 MB)"),
    HIGH(500L * 1024 * 1024, "Groß (500 MB)"),
    MAX(2048L * 1024 * 1024, "Maximum (2 GB)")
}

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val KEY_SERVER_URL = stringPreferencesKey("server_url")
    private val KEY_DOWNLOAD_DIR = stringPreferencesKey("download_dir")
    private val KEY_DOWNLOAD_TREE_URI = stringPreferencesKey("download_tree_uri")
    private val KEY_DOWNLOAD_TREE_NAME = stringPreferencesKey("download_tree_name")
    private val KEY_CACHE_SIZE = longPreferencesKey("cache_size_bytes")
    private val KEY_OFFLINE_ONLY = booleanPreferencesKey("offline_only")
    private val KEY_COMPARE_LIB_IDS = stringSetPreferencesKey("compare_local_lib_ids")
    private val KEY_MERGED_SERVER_LIB_IDS = stringSetPreferencesKey("merged_server_lib_ids")
    private val KEY_MERGED_LOCAL_LIB_IDS  = stringSetPreferencesKey("merged_local_lib_ids")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            serverUrl = prefs[KEY_SERVER_URL] ?: "",
            downloadDir = prefs[KEY_DOWNLOAD_DIR] ?: "",
            downloadTreeUri = prefs[KEY_DOWNLOAD_TREE_URI] ?: "",
            downloadTreeDisplayName = prefs[KEY_DOWNLOAD_TREE_NAME] ?: "",
            cacheSizeBytes = prefs[KEY_CACHE_SIZE] ?: 200L * 1024 * 1024,
            offlineOnly = prefs[KEY_OFFLINE_ONLY] ?: false,
            compareLocalLibraryIds = (prefs[KEY_COMPARE_LIB_IDS] ?: emptySet())
                .mapNotNull { it.toIntOrNull() }
                .toSet(),
            mergedServerLibraryIds = (prefs[KEY_MERGED_SERVER_LIB_IDS] ?: emptySet())
                .mapNotNull { it.toIntOrNull() }
                .toSet(),
            mergedLocalLibraryIds = (prefs[KEY_MERGED_LOCAL_LIB_IDS] ?: emptySet())
                .mapNotNull { it.toIntOrNull() }
                .toSet()
        )
    }

    suspend fun saveCompareLocalLibraryIds(ids: Set<Int>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_COMPARE_LIB_IDS] = ids.map { it.toString() }.toSet()
        }
    }

    suspend fun saveMergedServerLibraryIds(ids: Set<Int>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MERGED_SERVER_LIB_IDS] = ids.map { it.toString() }.toSet()
        }
    }

    suspend fun saveMergedLocalLibraryIds(ids: Set<Int>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MERGED_LOCAL_LIB_IDS] = ids.map { it.toString() }.toSet()
        }
    }

    suspend fun saveOfflineOnly(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OFFLINE_ONLY] = value
        }
    }

    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = url.trimEnd('/')
        }
    }

    suspend fun saveDownloadDir(dir: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DOWNLOAD_DIR] = dir
        }
    }

    suspend fun saveDownloadTree(uri: String, displayName: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DOWNLOAD_TREE_URI] = uri
            prefs[KEY_DOWNLOAD_TREE_NAME] = displayName
        }
    }

    suspend fun saveCacheSize(bytes: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CACHE_SIZE] = bytes
        }
    }
}
