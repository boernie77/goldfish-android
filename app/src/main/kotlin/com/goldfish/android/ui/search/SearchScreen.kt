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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.goldfish.android.data.local.LocalItemEntity
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.model.PersonSearchResult
import com.goldfish.android.ui.music.AddToPlaylistDialog
import com.goldfish.android.ui.theme.GoldfishOrange

/** Globale Suche ueber Server-Libs, Offline-Downloads und lokale Libs.
 *  Sucht parallel mit 250ms Debounce; Sections sind eigenstaendig sichtbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenServerItem: (itemId: Int) -> Unit,
    onOpenLocalItem: (itemId: Int) -> Unit,
    onOpenPerson: (tmdbId: Long, name: String) -> Unit = { _, _ -> },
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    var addToPlaylistItem by remember { mutableStateOf<Item?>(null) }
    val context = LocalContext.current

    // Fehler beim Nachladen der Fuzzy-Treffer sichtbar machen (Toast, wie
    // der Browser). Der Button selbst bleibt stehen (fuzzyExtraCount wird
    // bei einem Fehler NICHT auf 0 gesetzt), ein erneuter Klick versucht
    // es einfach nochmal (QM-Review FTS5-Fuzzy-Suche, 2026-09-19).
    LaunchedEffect(state.fuzzyError) {
        state.fuzzyError?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

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
                // Aufgegliederte Trefferanzeige (Server v1.4.22): Schauspieler
                // GANZ OBEN, wie im Browser (cards.js appendSearchResultCards) —
                // vor Filmen/Serien. Nur wenn ≥1 Treffer, Term ist im ViewModel
                // schon auf ≥3 Zeichen gegated.
                if (state.personResults.isNotEmpty()) {
                    item { SectionHeader("🎭 Schauspieler (${state.personResults.size})") }
                    item {
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)
                        ) {
                            items(state.personResults) { person ->
                                PersonResultCard(person) { onOpenPerson(person.tmdbId, person.name) }
                            }
                        }
                    }
                }
                if (state.serverResults.isNotEmpty()) {
                    item { SectionHeader("🌐 Server (${state.serverResults.size})") }
                    items(state.serverResults) { item ->
                        // Musik-Treffer: flache Titel-Zeile + direktes Abspielen
                        // ueber den Musik-Player (kein Album-Bundling, kein
                        // Detail-Screen — Tracks haben keinen). Gleiches
                        // Muster wie der zeitgleich gefixte Browser/iOS-Bug.
                        if (item.musicAlbumId != null) {
                            com.goldfish.android.ui.components.MusicTrackRow(
                                item = item,
                                onClick = { viewModel.playMusicSearchResult(item) },
                                onAddToPlaylist = { addToPlaylistItem = it }
                            )
                        } else {
                            ServerResultRow(item, state.baseUrl) { onOpenServerItem(item.id) }
                        }
                    }
                    if (state.fuzzyExtraCount > 0) {
                        item { FuzzyExtraButton(state.fuzzyExtraCount, state.isLoadingFuzzy) { viewModel.loadMoreFuzzyResults() } }
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
    addToPlaylistItem?.let { item ->
        AddToPlaylistDialog(
            itemId = item.id,
            onDismiss = { addToPlaylistItem = null },
            onDone = { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                addToPlaylistItem = null
            }
        )
    }
}

/** "🔍 N weitere Treffer"-Button — Pendant zum Browser-Button (views.js
 *  appendFuzzyExtraButton). Klick laedt searchMode=fuzzy nach und haengt
 *  neue Treffer ans Ende der Server-Sektion an. */
@Composable
private fun FuzzyExtraButton(count: Int, isLoading: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = !isLoading,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Text(if (isLoading) "🔍 Lädt …" else "🔍 $count weitere Treffer")
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

/** Schauspieler-Kachel der aufgegliederten Trefferanzeige (Server v1.4.22).
 *  Kreisfoto + Name + "Schauspieler"-Label, Tap oeffnet den Person-Filter —
 *  Pendant zu cards.js renderSearchPersonCard im Browser. Bildaufbau exakt
 *  wie DetailScreen.CastMemberCard (w185-Convention), aber hier direkt aus
 *  dem TMDB-profilePath-Fragment statt ueber den Server-Proxy-Endpoint, da
 *  /api/search/people den rohen TMDB-Pfad liefert (kein tmdbId-Profile-Proxy
 *  fuer Suchtreffer noetig). */
@Composable
private fun PersonResultCard(person: PersonSearchResult, onClick: () -> Unit) {
    val imageUrl = person.profilePath.takeIf { it.isNotBlank() }
        ?.let { "https://image.tmdb.org/t/p/w185$it" }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(84.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = person.name,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Text(
            text = "🎭 Schauspieler",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
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
