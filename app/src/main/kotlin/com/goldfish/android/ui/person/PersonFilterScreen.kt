package com.goldfish.android.ui.person

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.goldfish.android.ui.components.VideoCard

/** "🎭 <Name>" — alle Videos mit dieser Person ueber alle Bibliotheken.
 *  Erreicht von: Schauspieler-Kachel in der aufgegliederten Trefferanzeige
 *  (SearchScreen) und vom Cast-Strip im Detail-Screen.
 *
 *  Serien-Treffer erscheinen NICHT als einzelne Episoden-Kacheln, sondern als
 *  EINE Sammelkachel pro Serie mit Folgen-Anzahl (User-Wunsch 2026-09-20).
 *  Klick auf die Sammelkachel oeffnet eine einfache Sub-Liste NUR der
 *  Treffer-Episoden dieser Serie (rein clientseitig, siehe
 *  PersonFilterViewModel.openShowGroup). */
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

    val openGroup = state.openShowGroup

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (openGroup != null) openGroup.folder.ifBlank { "Serie" }
                        else "🎭 ${state.personName.ifBlank { name }}"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (openGroup != null) viewModel.closeShowGroup() else onBack()
                    }) {
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
                // Sub-View: nur die Episoden der aufgeklappten Serie, in denen
                // die Person vorkommt — keine neue Server-Anfrage, Daten liegen
                // schon in openGroup.episodes.
                openGroup != null -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(gap),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        items(openGroup.episodes) { item ->
                            VideoCard(
                                item = item,
                                imageUrl = viewModel.getImageUrl(item),
                                cardWidth = cardWidth,
                                onClick = { onOpenItem(item.id) }
                            )
                        }
                    }
                }
                state.movies.isEmpty() && state.showGroups.isEmpty() -> {
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
                        // Immer zwei getrennte, ueberschriftete Sektionen —
                        // Filme komplett vor Serien (User-Zitat 2026-09-20:
                        // "Es sollen immer alle Treffer von Filmen und Serien
                        // aufgezeigt werden, aber Kategorisiert. Also Erst
                        // Filme (mit Ueberschrift) und dann extra Bereich mit
                        // Ueberschrift Serien."), keine gemischte Liste.
                        if (state.movies.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SectionHeader("🎬 Filme")
                            }
                            items(state.movies) { item ->
                                VideoCard(
                                    item = item,
                                    imageUrl = viewModel.getImageUrl(item),
                                    cardWidth = cardWidth,
                                    onClick = { onOpenItem(item.id) }
                                )
                            }
                        }
                        if (state.showGroups.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SectionHeader("📺 Serien")
                            }
                            items(state.showGroups) { group ->
                                PersonShowGroupCard(
                                    group = group,
                                    imageUrl = viewModel.getShowGroupImageUrl(group),
                                    cardWidth = cardWidth,
                                    onClick = { viewModel.openShowGroup(group) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Sektions-Ueberschrift ("Filme"/"Serien") ueber der jeweiligen Grid-Sektion —
 *  User-Wunsch 2026-09-20: Filme und Serien immer als zwei klar getrennte,
 *  ueberschriftete Bereiche zeigen statt einer gemischten Liste. */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

/** Sammelkachel fuer EINE Serie im Person-Filter — Serienposter + Anzahl der
 *  Folgen als Badge/Untertitel. Pendant zum Browser renderPersonShowCard
 *  (cards.js), aber im Klick-Verhalten bewusst anders als die Browser-
 *  Variante renderSearchShowCard: hier oeffnet der Klick NICHT den vollen
 *  Serienordner, sondern die eigene Sub-Liste (User-Wunsch: "dann sollen
 *  alle Folgen dort erscheinen, wo er mitgespielt hat"). */
@Composable
private fun PersonShowGroupCard(
    group: PersonShowGroup,
    imageUrl: String,
    cardWidth: Dp,
    onClick: () -> Unit
) {
    val cardHeight = cardWidth * 1.5f
    Column(
        modifier = Modifier
            .width(cardWidth)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
                .clip(RoundedCornerShape(6.dp))
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = group.folder,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            // Folgen-Anzahl-Badge unten links — Pendant zum Browser
            // .folder-count-Badge in renderPersonShowCard.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xBB000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${group.count} Folge${if (group.count == 1) "" else "n"}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = group.folder.ifBlank { "Serie" },
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "📺 Serie",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
