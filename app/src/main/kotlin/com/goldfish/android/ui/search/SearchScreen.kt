package com.goldfish.android.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.goldfish.android.data.local.LocalItemEntity
import com.goldfish.android.data.model.Item
import com.goldfish.android.ui.theme.GoldfishOrange

/** Globale Suche ueber Server-Libs, Offline-Downloads und lokale Libs.
 *  Sucht parallel mit 250ms Debounce; Sections sind eigenstaendig sichtbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenServerItem: (itemId: Int) -> Unit,
    onOpenLocalItem: (itemId: Int) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { viewModel.setQuery(it) },
                        placeholder = { Text("Titel oder Dateiname…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setQuery("") }) {
                                    Icon(Icons.Filled.Clear, "Loeschen")
                                }
                            }
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        keyboard?.hide()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurueck")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (state.isSearching) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = GoldfishOrange
                )
            }

            val total = state.serverResults.size + state.offlineResults.size + state.localResults.size

            if (state.query.isBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Search, null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Suche in Server-Libs, Downloads und lokalen Libraries.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                return@Scaffold
            }

            if (!state.isSearching && total == 0) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Keine Treffer.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Scaffold
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (state.serverResults.isNotEmpty()) {
                    item { SectionHeader("🌐 Server (${state.serverResults.size})") }
                    items(state.serverResults) { item ->
                        ServerResultRow(item, state.baseUrl) { onOpenServerItem(item.id) }
                    }
                }
                if (state.offlineResults.isNotEmpty()) {
                    item { SectionHeader("📥 Offline-Downloads (${state.offlineResults.size})") }
                    items(state.offlineResults) { item ->
                        ServerResultRow(item, state.baseUrl) { onOpenServerItem(item.id) }
                    }
                }
                if (state.localResults.isNotEmpty()) {
                    item { SectionHeader("📁 Lokale Libraries (${state.localResults.size})") }
                    items(state.localResults) { item ->
                        LocalResultRow(item) { onOpenLocalItem(item.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = GoldfishOrange,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
    )
}

@Composable
private fun ServerResultRow(item: Item, baseUrl: String, onClick: () -> Unit) {
    val title = item.displayTitle.ifBlank { item.relPath ?: "Unbenannt" }
    val sub = listOfNotNull(
        item.metadata?.year?.toString()?.takeIf { it != "0" && it.isNotBlank() },
        item.relPath?.takeIf { it.isNotBlank() }
    ).joinToString(" · ")
    val imgUrl = item.metadataId?.let { "${baseUrl.trimEnd('/')}/api/poster/metadata/$it" }
        ?: "${baseUrl.trimEnd('/')}/api/thumb/${item.id}"
    ResultRow(title = title, subtitle = sub, imageUrl = imgUrl, onClick = onClick)
}

@Composable
private fun LocalResultRow(item: LocalItemEntity, onClick: () -> Unit) {
    val title = item.title?.takeIf { it.isNotBlank() }
        ?: item.parsedTitle.takeIf { it.isNotBlank() }
        ?: item.fileName
    val sub = listOfNotNull(
        item.year?.toString()?.takeIf { it != "0" },
        item.fileName.takeIf { title != item.fileName }
    ).joinToString(" · ")
    val imgUrl = item.posterPath?.let { "https://image.tmdb.org/t/p/w92$it" }
        ?: item.thumbnailPath?.let { "file://$it" }
    ResultRow(title = title, subtitle = sub, imageUrl = imgUrl, onClick = onClick)
}

@Composable
private fun ResultRow(title: String, subtitle: String, imageUrl: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 46.dp, height = 69.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        } else {
            Box(modifier = Modifier.size(width = 46.dp, height = 69.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
