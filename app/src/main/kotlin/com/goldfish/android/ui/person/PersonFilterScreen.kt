package com.goldfish.android.ui.person

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldfish.android.ui.components.VideoCard

/** "🎭 <Name>" — alle Videos mit dieser Person ueber alle Bibliotheken.
 *  Erreicht von: Schauspieler-Kachel in der aufgegliederten Trefferanzeige
 *  (SearchScreen) und vom Cast-Strip im Detail-Screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonFilterScreen(
    tmdbId: Long,
    name: String,
    onBack: () -> Unit,
    onOpenItem: (Int) -> Unit,
    viewModel: PersonFilterViewModel = hiltViewModel()
) {
    LaunchedEffect(tmdbId) { viewModel.load(tmdbId, name) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columns = if (screenWidthDp >= 840) (if (screenWidthDp >= 1200) 6 else 5) else 3
    val gap = 8.dp
    val cardWidth = ((screenWidthDp.dp - gap * (columns + 1)) / columns)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🎭 ${state.personName.ifBlank { name }}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurueck")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.errorMessage != null -> {
                    Text(
                        "Fehler: ${state.errorMessage}",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                state.items.isEmpty() -> {
                    Text(
                        "Keine Videos mit ${state.personName.ifBlank { name }} gefunden.",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(gap),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        items(state.items) { item ->
                            VideoCard(
                                item = item,
                                imageUrl = viewModel.getImageUrl(item),
                                cardWidth = cardWidth,
                                onClick = { onOpenItem(item.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}
