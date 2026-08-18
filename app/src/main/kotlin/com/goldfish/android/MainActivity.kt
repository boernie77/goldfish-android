package com.goldfish.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
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

    if (!state.isCheckingAuth) {
        // Server-URL nicht gesetzt → direkt zu Settings (User trägt URL ein,
        // dann zurück zum Login). Sonst je nach Login-Status Home oder Login.
        val start = when {
            state.needsServerUrl -> Screen.Settings.route
            state.isAuthenticated -> Screen.Home.route
            else -> Screen.Login.route
        }
        GoldfishNavHost(
            navController = navController,
            startDestination = start
        )
    }
}
