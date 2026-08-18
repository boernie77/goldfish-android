package com.goldfish.android.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Collections
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.goldfish.android.data.model.MediaCollection
import com.goldfish.android.data.model.MediaCollectionPart
import com.goldfish.android.ui.theme.GoldfishOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    onNavigateToItem: (Int) -> Unit,
    onBack: () -> Unit,
    viewModel: CollectionsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columns = when {
        screenWidthDp >= 840 -> 5
        else -> 3
    }
    val gap = 8.dp
    val cardWidth = ((screenWidthDp.dp - gap * (columns + 1)) / columns)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.selectedCollection?.name ?: "Sammlungen",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.selectedCollection != null) viewModel.closeCollection()
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
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
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Fehler: ${state.errorMessage}", color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = viewModel::loadCollections) { Text("Erneut versuchen") }
                        }
                    }
                }
                state.selectedCollection != null -> {
                    // Parts view
                    if (state.isLoadingParts) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = GoldfishOrange)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            contentPadding = PaddingValues(gap),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                            verticalArrangement = Arrangement.spacedBy(gap),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.collectionParts) { part ->
                                CollectionPartCard(
                                    part = part,
                                    cardWidth = cardWidth.value.dp,
                                    onClick = {
                                        if (part.owned && part.itemId != null) {
                                            onNavigateToItem(part.itemId)
                                        }
                                    }
                                )
                            }
                            if (state.collectionParts.isEmpty()) {
                                item(span = { GridItemSpan(columns) }) {
                                    Box(
                                        Modifier.fillMaxWidth().padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("Keine Teile gefunden", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    // Collections grid
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(gap),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(gap),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.collections) { collection ->
                            CollectionCard(
                                collection = collection,
                                posterUrl = viewModel.getCollectionPosterUrl(collection),
                                cardWidth = cardWidth.value.dp,
                                onClick = { viewModel.openCollection(collection) }
                            )
                        }
                        if (state.collections.isEmpty()) {
                            item(span = { GridItemSpan(columns) }) {
                                Box(
                                    Modifier.fillMaxWidth().padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Keine Sammlungen gefunden", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionCard(
    collection: MediaCollection,
    posterUrl: String,
    cardWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val cardHeight = cardWidth * 1.5f
    Column(
        modifier = Modifier
            .width(cardWidth)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
                .clip(RoundedCornerShape(6.dp))
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = collection.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            // Movie count badge
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                val countText = if (collection.partCount > 0)
                    "${collection.movieCount}/${collection.partCount} Filme"
                else
                    "${collection.movieCount} Filme"
                Text(text = countText, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = collection.name,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CollectionPartCard(
    part: MediaCollectionPart,
    cardWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val cardHeight = cardWidth * 1.5f
    val alpha = if (!part.owned || part.hidden) 0.5f else 1.0f

    Column(
        modifier = Modifier
            .width(cardWidth)
            .clickable(enabled = part.owned && part.itemId != null) { onClick() }
    ) {
        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
                .clip(RoundedCornerShape(6.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Collections,
                    contentDescription = null,
                    tint = if (part.owned) GoldfishOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
            }
            if (!part.owned) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xBB000000))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text("Fehlt", color = Color(0xFFFF5252), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
            part.year?.let { year ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xBB000000))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(text = "$year", color = Color.White, fontSize = 8.sp)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = part.title ?: "Unbekannt",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            color = if (part.owned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
