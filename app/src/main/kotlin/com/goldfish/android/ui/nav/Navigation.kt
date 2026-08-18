package com.goldfish.android.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.LocalLibraryRepository
import com.goldfish.android.ui.collections.CollectionsScreen
import com.goldfish.android.ui.detail.DetailScreen
import com.goldfish.android.ui.home.HomeScreen
import com.goldfish.android.ui.library.LibraryScreen
import com.goldfish.android.ui.locallib.LocalLibraryScreen
import com.goldfish.android.ui.locallib.LocalPlayerScreen
import com.goldfish.android.ui.locallib.LocalVlcPlayerScreen
import com.goldfish.android.ui.login.LoginScreen
import com.goldfish.android.ui.player.PlayerScreen
import com.goldfish.android.ui.playlists.PlaylistsScreen
import com.goldfish.android.ui.search.SearchScreen
import com.goldfish.android.ui.settings.SettingsScreen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ItemRepositoryEntryPoint {
    fun itemRepository(): ItemRepository
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocalLibraryRepositoryEntryPoint {
    fun localLibraryRepository(): LocalLibraryRepository
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocalRandomHistoryEntryPoint {
    fun localRandomHistory(): com.goldfish.android.data.repository.LocalRandomHistory
}

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Home : Screen("home")
    object Library : Screen("library/{libraryId}") {
        fun createRoute(libraryId: Int) = "library/$libraryId"
    }
    object LibraryFolder : Screen("library/{libraryId}/folder/{folder}?drilldown={drilldown}") {
        fun createRoute(libraryId: Int, folder: String, drilldown: Boolean = false): String {
            val base = "library/$libraryId/folder/${java.net.URLEncoder.encode(folder, "UTF-8")}"
            return if (drilldown) "$base?drilldown=true" else base
        }
    }
    object Detail : Screen("detail/{itemId}") {
        fun createRoute(itemId: Int) = "detail/$itemId"
    }
    object Player : Screen("player/{itemId}") {
        fun createRoute(itemId: Int) = "player/$itemId"
    }
    // Player aus zufälliger Wiedergabe — zeigt ⏭-Button für nächstes Random-Video
    object PlayerRandom : Screen("player-random/{libraryId}/{itemId}") {
        fun createRoute(libraryId: Int, itemId: Int) = "player-random/$libraryId/$itemId"
    }
    // Zusammengelegte Bibliothek (2 Server-Libs als eine; IDs komma-getrennt)
    object MergedLibrary : Screen("merged-library/{libIds}") {
        fun createRoute(ids: List<Int>) = "merged-library/${ids.joinToString(",")}"
    }
    object MergedLocalLibrary : Screen("merged-local-library/{libIds}") {
        fun createRoute(ids: List<Int>) = "merged-local-library/${ids.joinToString(",")}"
    }
    object PlayerRandomMerged : Screen("player-random-merged/{libIds}/{itemId}") {
        fun createRoute(ids: List<Int>, itemId: Int) =
            "player-random-merged/${ids.joinToString(",")}/$itemId"
    }
    object Settings : Screen("settings")
    object Collections : Screen("collections")
    object Playlists : Screen("playlists")
    object Search : Screen("search")
    // Lokale (on-device) Bibliotheken
    object LocalLibrary : Screen("local-library/{libraryId}") {
        fun createRoute(libraryId: Int) = "local-library/$libraryId"
    }
    object LocalLibraryFolder : Screen("local-library/{libraryId}/folder/{folder}") {
        fun createRoute(libraryId: Int, folder: String) =
            "local-library/$libraryId/folder/${java.net.URLEncoder.encode(folder, "UTF-8")}"
    }
    object LocalPlayer : Screen("local-player/{itemId}") {
        fun createRoute(itemId: Int) = "local-player/$itemId"
    }
    object LocalVlcPlayer : Screen("local-vlc-player/{itemId}") {
        fun createRoute(itemId: Int) = "local-vlc-player/$itemId"
    }
    // Zufallswiedergabe in lokaler Library/Folder mit ⏭-Button
    object LocalPlayerRandom : Screen("local-player-random/{libraryId}/{itemId}?folder={folder}") {
        fun createRoute(libraryId: Int, itemId: Int, folder: String? = null): String {
            val base = "local-player-random/$libraryId/$itemId"
            return if (folder.isNullOrBlank()) base
                   else "$base?folder=${java.net.URLEncoder.encode(folder, "UTF-8")}"
        }
    }
}

@Composable
fun GoldfishNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToItem = { itemId ->
                    navController.navigate(Screen.Detail.createRoute(itemId))
                },
                onNavigateToLibrary = { libraryId ->
                    navController.navigate(Screen.Library.createRoute(libraryId))
                },
                onNavigateToLocalLibrary = { libraryId ->
                    navController.navigate(Screen.LocalLibrary.createRoute(libraryId))
                },
                onNavigateToMergedLibrary = { ids ->
                    navController.navigate(Screen.MergedLibrary.createRoute(ids))
                },
                onNavigateToMergedLocalLibrary = { ids ->
                    navController.navigate(Screen.MergedLocalLibrary.createRoute(ids))
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateToCollections = {
                    navController.navigate(Screen.Collections.route)
                },
                onNavigateToPlaylists = {
                    navController.navigate(Screen.Playlists.route)
                },
                onNavigateToSearch = {
                    navController.navigate(Screen.Search.route)
                }
            )
        }

        composable(
            Screen.Library.route,
            arguments = listOf(navArgument("libraryId") { type = NavType.IntType })
        ) { backStackEntry ->
            val libraryId = backStackEntry.arguments?.getInt("libraryId") ?: return@composable
            LibraryScreen(
                libraryId = libraryId,
                folder = null,
                onNavigateToItem = { itemId ->
                    navController.navigate(Screen.Detail.createRoute(itemId))
                },
                onNavigateToPlayer = { itemId ->
                    // Random-Route: zeigt ⏭-Button für nächstes zufälliges Video
                    navController.navigate(Screen.PlayerRandom.createRoute(libraryId, itemId))
                },
                onNavigateToFolder = { libId, folder, drilldown ->
                    navController.navigate(Screen.LibraryFolder.createRoute(libId, folder, drilldown))
                },
                onNavigateHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Screen.LibraryFolder.route,
            arguments = listOf(
                navArgument("libraryId") { type = NavType.IntType },
                navArgument("folder") { type = NavType.StringType },
                navArgument("drilldown") { type = NavType.StringType; defaultValue = "false"; nullable = true }
            )
        ) { backStackEntry ->
            val libraryId = backStackEntry.arguments?.getInt("libraryId") ?: return@composable
            val folder = backStackEntry.arguments?.getString("folder")?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: return@composable
            val drilldown = backStackEntry.arguments?.getString("drilldown") == "true"
            LibraryScreen(
                libraryId = libraryId,
                folder = folder,
                drilldownActive = drilldown,
                onNavigateToItem = { itemId ->
                    navController.navigate(Screen.Detail.createRoute(itemId))
                },
                onNavigateToPlayer = { itemId ->
                    navController.navigate(Screen.PlayerRandom.createRoute(libraryId, itemId))
                },
                onNavigateToFolder = { libId, subFolder, sub_drilldown ->
                    navController.navigate(Screen.LibraryFolder.createRoute(libId, subFolder, sub_drilldown))
                },
                onNavigateHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Screen.Detail.route,
            arguments = listOf(navArgument("itemId") { type = NavType.IntType })
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getInt("itemId") ?: return@composable
            DetailScreen(
                itemId = itemId,
                onNavigateToPlayer = { id ->
                    navController.navigate(Screen.Player.createRoute(id))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Screen.Player.route,
            arguments = listOf(navArgument("itemId") { type = NavType.IntType })
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getInt("itemId") ?: return@composable
            PlayerScreen(
                itemId = itemId,
                onBack = { navController.popBackStack() }
            )
        }

        // Random-Player-Route mit ⏭ Next-Button
        composable(
            Screen.PlayerRandom.route,
            arguments = listOf(
                navArgument("libraryId") { type = NavType.IntType },
                navArgument("itemId") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val libraryId = backStackEntry.arguments?.getInt("libraryId") ?: return@composable
            val itemId = backStackEntry.arguments?.getInt("itemId") ?: return@composable
            val randomRepo = remember {
                // Get ItemRepository to fetch next random
                try {
                    EntryPointAccessors.fromApplication<ItemRepositoryEntryPoint>(
                        navController.context
                    ).itemRepository()
                } catch (e: Exception) { null }
            }
            val scope = rememberCoroutineScope()
            PlayerScreen(
                itemId = itemId,
                onBack = { navController.popBackStack() },
                onNextRandom = {
                    scope.launch {
                        val next = randomRepo?.getRandomItem(libraryId = libraryId)
                        if (next != null) {
                            navController.navigate(Screen.PlayerRandom.createRoute(libraryId, next.id)) {
                                popUpTo(Screen.PlayerRandom.route) { inclusive = true }
                            }
                        }
                    }
                }
            )
        }

        // Zusammengelegte Bibliothek — IDs als komma-getrennte Liste im Route-String
        composable(
            Screen.MergedLibrary.route,
            arguments = listOf(navArgument("libIds") { type = NavType.StringType })
        ) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("libIds") ?: return@composable
            val ids = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (ids.isEmpty()) return@composable
            LibraryScreen(
                libraryId = ids.first(),   // LibraryViewModel benötigt eine primäre ID für Metadaten
                mergedLibraryIds = ids,
                folder = null,
                onNavigateToItem = { itemId ->
                    navController.navigate(Screen.Detail.createRoute(itemId))
                },
                onNavigateToPlayer = { itemId ->
                    navController.navigate(Screen.Player.createRoute(itemId))
                },
                onNavigateToFolder = { libId, folder, drilldown ->
                    navController.navigate(Screen.LibraryFolder.createRoute(libId, folder, drilldown))
                },
                onNavigateHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                },
                onBack = { navController.popBackStack() },
                onPlayRandom = { _, itemId ->
                    navController.navigate(Screen.PlayerRandomMerged.createRoute(ids, itemId))
                }
            )
        }

        composable(
            Screen.PlayerRandomMerged.route,
            arguments = listOf(
                navArgument("libIds") { type = NavType.StringType },
                navArgument("itemId") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("libIds") ?: return@composable
            val ids = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
            val itemId = backStackEntry.arguments?.getInt("itemId") ?: return@composable
            val randomRepo = remember {
                try {
                    EntryPointAccessors.fromApplication<ItemRepositoryEntryPoint>(
                        navController.context
                    ).itemRepository()
                } catch (e: Exception) { null }
            }
            val scope = rememberCoroutineScope()
            PlayerScreen(
                itemId = itemId,
                onBack = { navController.popBackStack() },
                onNextRandom = {
                    scope.launch {
                        val next = randomRepo?.getRandomItem(libraryIds = ids)
                        if (next != null) {
                            navController.navigate(Screen.PlayerRandomMerged.createRoute(ids, next.id)) {
                                popUpTo(Screen.PlayerRandomMerged.route) { inclusive = true }
                            }
                        }
                    }
                }
            )
        }

        // Zusammengelegte lokale (SAF) Bibliotheken
        composable(
            Screen.MergedLocalLibrary.route,
            arguments = listOf(navArgument("libIds") { type = NavType.StringType })
        ) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("libIds") ?: return@composable
            val ids = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (ids.isEmpty()) return@composable
            LocalLibraryScreen(
                libraryId = ids.first(),
                mergedLibraryIds = ids,
                folder = null,
                onPlayItem = { itemId -> navController.navigate(Screen.LocalPlayer.createRoute(itemId)) },
                onPlayRandom = { libId, itemId, folder ->
                    navController.navigate(Screen.LocalPlayerRandom.createRoute(libId, itemId, folder))
                },
                onNavigateToFolder = { libId, folder ->
                    navController.navigate(Screen.LocalLibraryFolder.createRoute(libId, folder))
                },
                onNavigateHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                // popBackStack nur wenn was im Stack ist — sonst (Settings als
                // Start-Destination wegen leerer Server-URL) zum Login navigieren
                // damit der User weiterkommt nachdem er die URL eingetragen hat.
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.Settings.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(Screen.Collections.route) {
            CollectionsScreen(
                onNavigateToItem = { itemId ->
                    navController.navigate(Screen.Detail.createRoute(itemId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Playlists.route) {
            PlaylistsScreen(
                onNavigateToItem = { itemId ->
                    navController.navigate(Screen.Detail.createRoute(itemId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenServerItem = { itemId ->
                    navController.navigate(Screen.Detail.createRoute(itemId))
                },
                onOpenLocalItem = { itemId ->
                    navController.navigate(Screen.LocalPlayer.createRoute(itemId))
                }
            )
        }

        // Lokale (on-device via SAF) Bibliothek — Root-Ansicht ohne folder
        composable(
            Screen.LocalLibrary.route,
            arguments = listOf(navArgument("libraryId") { type = NavType.IntType })
        ) { backStackEntry ->
            val libraryId = backStackEntry.arguments?.getInt("libraryId") ?: return@composable
            LocalLibraryScreen(
                libraryId = libraryId,
                folder = null,
                onBack = { navController.popBackStack() },
                onNavigateToFolder = { libId, folder ->
                    navController.navigate(Screen.LocalLibraryFolder.createRoute(libId, folder))
                },
                onPlayItem = { itemId ->
                    navController.navigate(Screen.LocalPlayer.createRoute(itemId))
                },
                onPlayRandom = { libId, itemId, f ->
                    // Frische Zufalls-Session: History zuruecksetzen und mit
                    // dem Startitem initialisieren.
                    runCatching {
                        EntryPointAccessors.fromApplication<LocalRandomHistoryEntryPoint>(
                            navController.context
                        ).localRandomHistory()
                    }.getOrNull()?.let { h ->
                        h.reset()
                        h.push(itemId)
                    }
                    navController.navigate(Screen.LocalPlayerRandom.createRoute(libId, itemId, f))
                }
            )
        }

        // Lokale Bibliothek — Sub-Ordner
        composable(
            Screen.LocalLibraryFolder.route,
            arguments = listOf(
                navArgument("libraryId") { type = NavType.IntType },
                navArgument("folder") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val libraryId = backStackEntry.arguments?.getInt("libraryId") ?: return@composable
            val folder = backStackEntry.arguments?.getString("folder")?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: return@composable
            LocalLibraryScreen(
                libraryId = libraryId,
                folder = folder,
                onBack = { navController.popBackStack() },
                onNavigateToFolder = { libId, subFolder ->
                    navController.navigate(Screen.LocalLibraryFolder.createRoute(libId, subFolder))
                },
                onPlayItem = { itemId ->
                    navController.navigate(Screen.LocalPlayer.createRoute(itemId))
                },
                onPlayRandom = { libId, itemId, f ->
                    runCatching {
                        EntryPointAccessors.fromApplication<LocalRandomHistoryEntryPoint>(
                            navController.context
                        ).localRandomHistory()
                    }.getOrNull()?.let { h ->
                        h.reset()
                        h.push(itemId)
                    }
                    navController.navigate(Screen.LocalPlayerRandom.createRoute(libId, itemId, f))
                }
            )
        }

        // Player fuer lokale SAF-URI-Videos — ExoPlayer direkt aus content://
        composable(
            Screen.LocalPlayer.route,
            arguments = listOf(navArgument("itemId") { type = NavType.IntType })
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getInt("itemId") ?: return@composable
            LocalPlayerScreen(
                itemId = itemId,
                onBack = { navController.popBackStack() },
                onOpenInVlc = {
                    navController.navigate(Screen.LocalVlcPlayer.createRoute(itemId)) {
                        popUpTo(Screen.LocalPlayer.route) { inclusive = true }
                    }
                }
            )
        }

        // Interner VLC-Player als Fallback fuer Files die ExoPlayer
        // nicht oeffnen kann.
        composable(
            Screen.LocalVlcPlayer.route,
            arguments = listOf(navArgument("itemId") { type = NavType.IntType })
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getInt("itemId") ?: return@composable
            LocalVlcPlayerScreen(
                itemId = itemId,
                onBack = { navController.popBackStack() }
            )
        }

        // Lokaler Player im Zufalls-Modus — wie LocalPlayer, aber mit
        // ⏭-Button der das naechste zufaellige Video aus der Library/dem
        // Folder navigiert.
        composable(
            Screen.LocalPlayerRandom.route,
            arguments = listOf(
                navArgument("libraryId") { type = NavType.IntType },
                navArgument("itemId") { type = NavType.IntType },
                navArgument("folder") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val libraryId = backStackEntry.arguments?.getInt("libraryId") ?: return@composable
            val itemId = backStackEntry.arguments?.getInt("itemId") ?: return@composable
            val folder = backStackEntry.arguments?.getString("folder")?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            }
            val localRepo = remember {
                try {
                    EntryPointAccessors.fromApplication<LocalLibraryRepositoryEntryPoint>(
                        navController.context
                    ).localLibraryRepository()
                } catch (e: Exception) { null }
            }
            val history = remember {
                try {
                    EntryPointAccessors.fromApplication<LocalRandomHistoryEntryPoint>(
                        navController.context
                    ).localRandomHistory()
                } catch (e: Exception) { null }
            }
            val scope = rememberCoroutineScope()
            // ⏮ ist nur sichtbar/aktiv wenn die History mindestens ein
            // Vorgaenger-Item hat. Beim allerersten Random-Item ist
            // hasPrevious() == false → onPreviousRandom bleibt null und der
            // Player rendert den ⏮-Button grau.
            val onPrev: (() -> Unit)? = if (history?.hasPrevious() == true) {
                {
                    val prevId = history.goBack()
                    if (prevId != null) {
                        navController.navigate(Screen.LocalPlayerRandom.createRoute(libraryId, prevId, folder)) {
                            popUpTo(Screen.LocalPlayerRandom.route) { inclusive = true }
                        }
                    }
                }
            } else null
            LocalPlayerScreen(
                itemId = itemId,
                onBack = { navController.popBackStack() },
                onNextRandom = {
                    scope.launch {
                        // Erst aus der History "forward" — wenn der User vorher
                        // zurueckgesprungen ist, kann er via ⏭ wieder vor in
                        // bereits gesehene Items. Sonst frisches Repo-Random.
                        val nextId = history?.goForwardFromHistory()
                            ?: localRepo?.getRandomItem(libraryId, folder)?.id?.also {
                                history?.push(it)
                            }
                        if (nextId != null) {
                            navController.navigate(Screen.LocalPlayerRandom.createRoute(libraryId, nextId, folder)) {
                                popUpTo(Screen.LocalPlayerRandom.route) { inclusive = true }
                            }
                        }
                    }
                },
                onPreviousRandom = onPrev
            )
        }
    }
}
