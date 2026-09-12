package com.goldfish.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.goldfish.android.ui.music.MusicMiniPlayerBar
import com.goldfish.android.ui.nav.GoldfishNavHost
import com.goldfish.android.ui.nav.Screen
import com.goldfish.android.ui.theme.GoldfishTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GoldfishTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GoldfishMainContent()
                }
            }
        }
    }
}

@Composable
private fun GoldfishMainContent(
    viewModel: MainViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    // POST_NOTIFICATIONS ist ab API 33 eine "dangerous permission" — ohne sie
    // zeigt Media3s MediaSessionService keine Benachrichtigung/Lockscreen-
    // Controls, Wiedergabe selbst funktioniert aber trotzdem weiter
    // (degradiert graceful auf reine In-App-Steuerung). Bewusst NICHT beim
    // App-Start abgefragt, sondern erst wenn der User tatsaechlich einen
    // Musik-Track startet (siehe MusicPlayerController.playQueue-Aufrufer).
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* egal ob gewaehrt — Wiedergabe blockiert nie darauf */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (!state.isCheckingAuth) {
        // Server-URL nicht gesetzt → direkt zu Settings (User trägt URL ein,
        // dann zurück zum Login). Sonst je nach Login-Status Home oder Login.
        val start = when {
            state.needsServerUrl -> Screen.Settings.route
            state.isAuthenticated -> Screen.Home.route
            else -> Screen.Login.route
        }
        // Kein Bottom-Nav-Scaffold in dieser App — die Musik-Mini-Leiste sitzt
        // deshalb hier als fester Sibling unter dem NavHost (nicht ueberlagernd),
        // ueberlebt dadurch jede Navigation ohne pro-Screen-Wiring.
        Column(modifier = Modifier.fillMaxSize()) {
            GoldfishNavHost(
                navController = navController,
                startDestination = start,
                modifier = Modifier.weight(1f)
            )
            MusicMiniPlayerBar(onOpenNowPlaying = { navController.navigate(Screen.NowPlaying.route) })
        }
    }
}
