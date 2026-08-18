package com.goldfish.android.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.goldfish.android.data.model.Item
import com.goldfish.android.ui.theme.GoldfishOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    itemId: Int,
    onNavigateToPlayer: (Int) -> Unit,
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    // Item bei jedem Lifecycle-Resume neu laden — wichtig, damit nach Rückkehr vom
    // Player die aktuelle Resume-Position frisch im State liegt (sonst zeigt der
    // Resume-Dialog beim erneuten Play nicht und der Player springt zur alten Stelle).
    LifecycleResumeEffect(itemId) {
        viewModel.load(itemId)
        onPauseOrDispose { }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.item?.displayTitle ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = GoldfishOrange)
                    }
                }
                state.errorMessage != null -> {
                    Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Fehler: ${state.errorMessage}",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                state.item != null -> {
                    val screenWidthDp = LocalConfiguration.current.screenWidthDp
                    val isTablet = screenWidthDp >= 600

                    var showResumeDialog by remember { mutableStateOf(false) }

                    val onPlayWithResumeCheck: () -> Unit = {
                        val item = state.item!!
                        val resumeSec = item.resumePosSec ?: 0.0
                        val nearEnd = item.durationSec > 0 && resumeSec / item.durationSec > 0.95
                        if (resumeSec > 30 && !nearEnd) {
                            showResumeDialog = true
                        } else {
                            onNavigateToPlayer(item.id)
                        }
                    }

                    if (showResumeDialog) {
                        val item = state.item!!
                        val resumeSec = (item.resumePosSec ?: 0.0).toInt()
                        val mm = resumeSec / 60
                        val ss = resumeSec % 60
                        val hh = mm / 60
                        val resumeLabel = if (hh > 0)
                            "%d:%02d:%02d".format(hh, mm % 60, ss)
                        else "%d:%02d".format(mm, ss)
                        AlertDialog(
                            onDismissRequest = { showResumeDialog = false },
                            title = { Text("Wiedergabe") },
                            text = { Text("An welcher Stelle soll gestartet werden?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showResumeDialog = false
                                    onNavigateToPlayer(item.id)
                                }) { Text("▶ Fortsetzen ab $resumeLabel", color = GoldfishOrange) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showResumeDialog = false
                                    viewModel.resetResumeAndPlay { onNavigateToPlayer(item.id) }
                                }) { Text("⟲ Von Anfang") }
                            }
                        )
                    }

                    if (isTablet) {
                        TabletDetailLayout(
                            item = state.item!!,
                            state = state,
                            posterUrl = viewModel.getPosterUrl(),
                            onPlay = onPlayWithResumeCheck,
                            onToggleWatched = viewModel::toggleWatched,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onStartDownload = viewModel::startDownload,
                            onDeleteDownload = viewModel::deleteDownload,
                            onSelectVariant = viewModel::selectVariant
                        )
                    } else {
                        PhoneDetailLayout(
                            item = state.item!!,
                            state = state,
                            posterUrl = viewModel.getPosterUrl(),
                            onPlay = onPlayWithResumeCheck,
                            onToggleWatched = viewModel::toggleWatched,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onStartDownload = viewModel::startDownload,
                            onDeleteDownload = viewModel::deleteDownload,
                            onSelectVariant = viewModel::selectVariant
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tablet layout: poster left (200×300dp), content right, no scrolling needed
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TabletDetailLayout(
    item: Item,
    state: DetailState,
    posterUrl: String,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onStartDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onSelectVariant: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Left: poster + file info
        Column(
            modifier = Modifier
                .width(200.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (posterUrl.isNotBlank()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = item.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(200.dp)
                        .height(300.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
            FileInfoBlock(item = item)
        }

        // Right: title, meta, actions
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TitleBlock(item = item)
            GenreChips(item = item)
            EpisodeInfo(item = item)
            OverviewText(item = item, maxLines = 5)
            if (state.variants.size > 1) {
                VariantPicker(
                    variants = state.variants,
                    currentId = item.id,
                    onSelect = onSelectVariant
                )
            }
            ActionButtons(
                item = item,
                state = state,
                onPlay = onPlay,
                onToggleWatched = onToggleWatched,
                onToggleFavorite = onToggleFavorite,
                onStartDownload = onStartDownload,
                onDeleteDownload = onDeleteDownload
            )
            if (state.cast.isNotEmpty()) {
                CastStrip(cast = state.cast, baseUrl = state.baseUrl)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Phone layout: small poster inline with title, then scrollable content
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PhoneDetailLayout(
    item: Item,
    state: DetailState,
    posterUrl: String,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onStartDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onSelectVariant: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Header row: small poster + title/meta
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (posterUrl.isNotBlank()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = item.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(100.dp)
                        .height(150.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TitleBlock(item = item)
                EpisodeInfo(item = item)
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GenreChips(item = item)
            OverviewText(item = item, maxLines = 4)
            if (state.variants.size > 1) {
                VariantPicker(
                    variants = state.variants,
                    currentId = item.id,
                    onSelect = onSelectVariant
                )
            }
            ActionButtons(
                item = item,
                state = state,
                onPlay = onPlay,
                onToggleWatched = onToggleWatched,
                onToggleFavorite = onToggleFavorite,
                onStartDownload = onStartDownload,
                onDeleteDownload = onDeleteDownload
            )
            if (state.cast.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                CastStrip(cast = state.cast, baseUrl = state.baseUrl)
            }
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            FileInfoBlock(item = item)
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-composables shared by both layouts
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TitleBlock(item: Item) {
    Text(
        text = item.displayTitle,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    )
    item.metadata?.let { meta ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            meta.year?.let {
                Text(
                    text = "$it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            meta.rating?.let {
                Text(
                    text = "★ ${"%.1f".format(it)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GoldfishOrange
                )
            }
            meta.runtime?.let {
                Text(
                    text = "${it} Min.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GenreChips(item: Item) {
    item.metadata?.genres?.let { genresJson ->
        val genres = parseGenres(genresJson)
        if (genres.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                genres.take(4).forEach { genre ->
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(genre, style = MaterialTheme.typography.labelSmall)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeInfo(item: Item) {
    item.metadata?.let { meta ->
        if (meta.season != null && meta.episode != null) {
            Text(
                text = "Staffel ${meta.season}, Folge ${meta.episode}",
                style = MaterialTheme.typography.bodyMedium,
                color = GoldfishOrange
            )
        }
    }
}

@Composable
private fun OverviewText(item: Item, maxLines: Int) {
    item.metadata?.overview?.let { overview ->
        if (overview.isNotBlank()) {
            var expanded by remember { mutableStateOf(false) }
            Column {
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else maxLines,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
                )
                if (!expanded) {
                    TextButton(
                        onClick = { expanded = true },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Mehr anzeigen", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(
    item: Item,
    state: DetailState,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onStartDownload: () -> Unit,
    onDeleteDownload: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Play button
        Button(
            onClick = onPlay,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GoldfishOrange,
                contentColor = Color.Black
            )
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (item.resumePosSec != null && item.resumePosSec > 30)
                    "Fortsetzen" else "Abspielen",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Watched toggle
            OutlinedButton(
                onClick = onToggleWatched,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (item.watched) GoldfishOrange
                                   else MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = if (item.watched) Icons.Filled.CheckCircle
                                  else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (item.watched) "Gesehen" else "Ungesehen",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            // Favorite toggle
            OutlinedButton(
                onClick = onToggleFavorite,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (item.favorite) Color(0xFFE91E63)
                                   else MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = if (item.favorite) Icons.Filled.Favorite
                                  else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Favorit", style = MaterialTheme.typography.labelMedium)
            }
        }

        // Download button / progress
        when {
            state.isDownloaded -> {
                OutlinedButton(
                    onClick = onDeleteDownload,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4CAF50))
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Offline verfügbar — Löschen")
                }
            }
            state.isDownloading -> {
                Column {
                    LinearProgressIndicator(
                        progress = { state.downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = GoldfishOrange
                    )
                    Text(
                        text = "Wird heruntergeladen… ${(state.downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            else -> {
                OutlinedButton(
                    onClick = onStartDownload,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Herunterladen (Offline)")
                }
            }
        }
    }
}

@Composable
private fun FileInfoBlock(item: Item) {
    Text(
        text = "Datei-Info",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(2.dp))
    if (item.width > 0 && item.height > 0) {
        Text(
            text = "Auflösung: ${item.width}×${item.height}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (item.sizeBytes > 0) {
        Text(
            text = "Größe: ${formatSize(item.sizeBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (item.durationSec > 0) {
        Text(
            text = "Laufzeit: ${formatDuration(item.durationSec)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    item.relPath?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun parseGenres(genresJson: String): List<String> {
    return try {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val listType = Types.newParameterizedType(List::class.java, String::class.java)
        val adapter = moshi.adapter<List<String>>(listType)
        adapter.fromJson(genresJson) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}

@Composable
private fun CastStrip(
    cast: List<com.goldfish.android.data.model.CastMember>,
    baseUrl: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "🎭 Schauspieler",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = GoldfishOrange
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            cast.take(20).forEach { member ->
                CastMemberCard(member = member, baseUrl = baseUrl)
            }
        }
    }
}

@Composable
private fun CastMemberCard(
    member: com.goldfish.android.data.model.CastMember,
    baseUrl: String
) {
    val imageUrl = if (member.tmdbId > 0) {
        "${baseUrl.trimEnd('/')}/api/person/${member.tmdbId}/profile"
    } else null

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = member.name,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (member.character.isNotBlank()) {
            Text(
                text = member.character,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576L -> "${"%.0f".format(bytes / 1_048_576.0)} MB"
        else -> "${bytes / 1024} KB"
    }
}

private fun formatDuration(seconds: Double): String {
    val totalSecs = seconds.toInt()
    val hours = totalSecs / 3600
    val minutes = (totalSecs % 3600) / 60
    val secs = totalSecs % 60
    return if (hours > 0) {
        "${hours}h ${minutes.toString().padStart(2, '0')}m"
    } else {
        "${minutes}m ${secs.toString().padStart(2, '0')}s"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Varianten-Auswahl: bei mehreren Files mit gleicher metadata_id (z.B. 1080p
// + 4K-Version desselben Films) waehlt der User die gewuenschte Datei aus.
// Pendant zum Browser-Detail-Dropdown in player.js (variantDropdown).
// ─────────────────────────────────────────────────────────────────────────────

private fun variantResLabel(width: Int, height: Int): String {
    val effective = maxOf(height, (width * 9.0 / 16.0).toInt())
    return when {
        effective >= 2160 -> "4K"
        effective >= 1440 -> "2K"
        effective >= 1080 -> "1080p"
        effective >= 720 -> "720p"
        effective >= 576 -> "576p"
        effective >= 480 -> "480p"
        effective > 0 -> "360p"
        else -> ""
    }
}

private fun variantSizeLabel(bytes: Long): String {
    if (bytes <= 0) return ""
    val gb = bytes / 1_073_741_824.0
    if (gb >= 1.0) return "%.1f GB".format(gb)
    val mb = bytes / 1_048_576.0
    return "%.0f MB".format(mb)
}

private fun variantLabel(v: Item): String {
    val fileName = (v.relPath ?: "").substringAfterLast('/').ifEmpty { v.title }
    val parts = mutableListOf<String>()
    parts += (v.container ?: "").uppercase().ifEmpty { "?" }
    val r = variantResLabel(v.width, v.height)
    if (r.isNotEmpty()) parts += r
    if (v.sizeBytes > 0) parts += variantSizeLabel(v.sizeBytes)
    if (v.bitrateKbps > 0) parts += "%.1f Mbps".format(v.bitrateKbps / 1000.0)
    val vc = v.videoCodec
    if (!vc.isNullOrBlank()) parts += vc.uppercase()
    val ac = v.audioCodec
    if (!ac.isNullOrBlank()) parts += ac.uppercase()
    if (v.watched) parts += "✓ gesehen"
    val tech = parts.joinToString(" · ")
    return if (fileName.isNotBlank()) "$fileName  —  $tech" else tech
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VariantPicker(
    variants: List<Item>,
    currentId: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = variants.firstOrNull { it.id == currentId } ?: variants.first()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = variantLabel(current),
            onValueChange = {},
            readOnly = true,
            label = { Text("Variante (${variants.size})") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            variants.forEach { v ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = variantLabel(v),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (v.id == currentId) GoldfishOrange
                                    else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        expanded = false
                        if (v.id != currentId) onSelect(v.id)
                    }
                )
            }
        }
    }
}
