package com.glint.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Change poster" and "Change background": every picture TMDB has for a film or series, in a grid. The first card
 * is the automatic choice (TMDB's pick, or the NAS picture); picking another keeps it for this title from then on.
 */
@Composable
fun ArtworkScreen(nav: Nav, screen: Screen.Artwork) {
    val lib = App.library
    val data by lib.data.collectAsState()
    val movie = remember(data) { if (!screen.tv) lib.findMovie(screen.id) else null }
    val show = remember(data) { if (screen.tv) lib.findShow(screen.id) else null }
    val tmdbId = movie?.tmdbId ?: show?.tmdbId ?: 0
    val wide = screen.backdrop
    val chosen = lib.artOf(screen.id).let { if (wide) it?.backdrop else it?.poster }
    // What shows without a choice: the same rules as everywhere else, with the choice left out.
    val automatic = movie?.let { m -> Item.MovieItem(m.copy(chosenPoster = null, chosenBackdrop = null)) }
        ?: show?.let { s -> Item.ShowItem(s.copy(chosenPoster = null, chosenBackdrop = null)) }
    var images by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(tmdbId) {
        images = if (tmdbId == 0 || !lib.tmdb.enabled) emptyList()
        else withContext(Dispatchers.IO) { runCatching { lib.tmdb.images(tmdbId, screen.tv, wide) }.getOrDefault(emptyList()) }
    }
    fun pick(url: String?) { lib.setArtwork(screen.id, wide, url); nav.pop() }

    Column(Modifier.fillMaxSize().screenBackground()) {
        Header(stringResource(if (wide) R.string.change_backdrop else R.string.change_poster), movie?.title ?: show?.title)
        val list = images
        if (list == null) { Txt(stringResource(R.string.searching), Type.Body, Modifier.padding(start = 56.dp)); return@Column }
        if (list.isEmpty()) Txt(
            stringResource(if (tmdbId == 0 || !lib.tmdb.enabled) R.string.artwork_needs_match else R.string.artwork_none),
            Type.Body, Modifier.padding(start = 56.dp, bottom = 16.dp),
        )
        val focusAutomatic = chosen == null || chosen !in list
        LazyVerticalGrid(
            GridCells.Fixed(if (wide) 4 else 6),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 16.dp, bottom = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            item {
                ArtChoice(
                    automatic?.let { if (wide) it.backdrop else it.poster }, wide, chosen == null,
                    stringResource(R.string.artwork_automatic), if (focusAutomatic) FocusFirst() else Modifier,
                ) { pick(null) }
            }
            items(list, key = { it }) { url ->
                // A smaller copy for the grid: the full-size picture is what the cards and the page then use.
                val thumb = url.replace("/w1280/", "/w780/").replace("/w500/", "/w342/")
                ArtChoice(thumb, wide, url == chosen, null, if (url == chosen) FocusFirst() else Modifier) { pick(url) }
            }
        }
    }
}

/** One picture to pick: lifted when focused, ringed in the accent colour when it's the one in use. */
@Composable
private fun ArtChoice(model: Any?, wide: Boolean, current: Boolean, label: String?, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    FocusCard(modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f), shape, onClick = onClick) {
        Box(Modifier.fillMaxSize().background(Color(0xFF3A3D43)))
        if (model != null) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (label != null) Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000)).padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Txt(label, Type.Meta.copy(color = Color.White), maxLines = 1) }
        if (current) Box(Modifier.fillMaxSize().border(4.dp, Palette.Accent, shape))
    }
}
