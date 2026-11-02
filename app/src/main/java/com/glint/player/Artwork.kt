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
    // The picture next to the file, offered like Kodi's local art.
    val local = (automatic as? Item.MovieItem)?.let { if (wide) it.localBackdrop else it.localPoster }
        ?: (automatic as? Item.ShowItem)?.let { if (wide) it.localBackdrop else it.localPoster }
    var images by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(tmdbId) {
        images = if (tmdbId == 0 || !lib.tmdb.enabled) emptyList()
        else withContext(Dispatchers.IO) { runCatching { lib.tmdb.images(tmdbId, screen.tv, wide) }.getOrDefault(emptyList()) }
    }
    fun pick(url: String?) { lib.setArtwork(screen.id, wide, url); nav.pop() }
    val chosenImage = chosenFile(chosen)
    var browsing by remember { mutableStateOf(false) }
    if (browsing) {
        val here = movie?.file ?: show?.episodes?.firstOrNull()?.file
        ArtBrowser(wide, here, movie?.title ?: show?.title, { pick(chosenFile(it)) }) { browsing = false }
        return
    }

    Column(Modifier.fillMaxSize().screenBackground()) {
        Header(stringResource(if (wide) R.string.change_backdrop else R.string.change_poster), movie?.title ?: show?.title)
        val list = images
        if (list == null) { Txt(stringResource(R.string.searching), Type.Body, Modifier.padding(start = 56.dp)); return@Column }
        if (list.isEmpty()) Txt(
            stringResource(if (tmdbId == 0 || !lib.tmdb.enabled) R.string.artwork_needs_match else R.string.artwork_none),
            Type.Body, Modifier.padding(start = 56.dp, bottom = 16.dp),
        )
        val focusAutomatic = chosen == null || (chosen !in list && chosenImage == null && (chosen != LOCAL_ART || local == null))
        // Automatic, the picture next to the file, Browse, then TMDB's.
        val before = if (local != null) 3 else 2
        LazyVerticalGrid(
            GridCells.Fixed(if (wide) 4 else 6),
            Modifier.fillMaxSize(),
            // Opens at the picture in use: one further down isn't composed otherwise and couldn't take focus.
            androidx.compose.foundation.lazy.grid.rememberLazyGridState(if (chosen == LOCAL_ART && local != null) 1 else if (chosenImage != null) before - 1 else if (chosen in list) list.indexOf(chosen) + before else 0),
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
            if (local != null) item {
                ArtChoice(local, wide, chosen == LOCAL_ART, stringResource(R.string.artwork_local), if (chosen == LOCAL_ART) FocusFirst() else Modifier) { pick(LOCAL_ART) }
            }
            item {
                ArtChoice(chosenImage, wide, chosenImage != null, stringResource(R.string.artwork_browse), if (chosenImage != null) FocusFirst() else Modifier) { browsing = true }
            }
            items(list, key = { it }) { url ->
                // A smaller copy for the grid: the full-size picture is what the cards and the page then use.
                val thumb = url.replace("/w1280/", "/w780/").replace("/w500/", "/w342/")
                ArtChoice(thumb, wide, url == chosen, null, if (url == chosen) FocusFirst() else Modifier) { pick(url) }
            }
        }
    }
}

/**
 * Picks a picture from any share or disk, starting in the folder of the film ([here]). Back goes up a folder, then to
 * the shares, then closes.
 */
@Composable
private fun ArtBrowser(wide: Boolean, here: VideoFile?, title: String?, onPick: (NasImage) -> Unit, onClose: () -> Unit) {
    val lib = App.library
    val sources by lib.sources.collectAsState()
    val usable = sources.filter { it.type != "upnp" }
    var source by remember { mutableStateOf(here?.source?.takeIf { id -> usable.any { it.id == id } }) }
    var share by remember { mutableStateOf(if (source != null) here?.share else null) }
    var path by remember { mutableStateOf(if (source != null) here?.path.orEmpty().substringBeforeLast('/', "") else "") }
    var shares by remember { mutableStateOf<List<String>?>(null) }
    var entries by remember { mutableStateOf<List<SmbEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val src = usable.firstOrNull { it.id == source }
    LaunchedEffect(source, share, path) {
        shares = null; entries = null; error = null
        if (src == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                if (share == null) {
                    val found = if (src.share.isNotEmpty()) listOf(src.share) else Fs.listShares(src)
                    if (found.size == 1) share = found[0] else shares = found
                } else entries = Fs.list(src, share!!, path)
                    .filter { it.isDir || Names.ext(it.name) in Names.IMAGE_EXT }
                    .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }
    androidx.activity.compose.BackHandler {
        when {
            path.isNotEmpty() -> path = path.substringBeforeLast('/', "")
            share != null && src != null && src.share.isEmpty() && src.type == "smb" -> share = null
            source != null -> { source = null; share = null }
            else -> onClose()
        }
    }
    Column(Modifier.fillMaxSize().screenBackground()) {
        Header(stringResource(R.string.artwork_browse), listOfNotNull(src?.name, share?.ifEmpty { null }, path.ifEmpty { null }).joinToString(" / ").ifEmpty { title })
        error?.let { Txt(it, Type.Body.copy(color = Palette.Accent), Modifier.padding(start = 56.dp, bottom = 12.dp)) }
        if (src != null && error == null && (share == null && shares == null || share != null && entries == null)) Txt(stringResource(R.string.loading), Type.Body, Modifier.padding(start = 56.dp))
        if (entries?.isEmpty() == true) Txt(stringResource(R.string.nothing_here), Type.Body, Modifier.padding(start = 56.dp))
        // Anew for each folder, so focus starts on its first tile.
        androidx.compose.runtime.key(source, share, path, shares != null, entries != null) {
            LazyVerticalGrid(
                GridCells.Fixed(if (wide) 4 else 6),
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 16.dp, bottom = 60.dp),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp),
            ) {
                when {
                    src == null -> items(usable, key = { it.id }) { s ->
                        ArtChoice(null, wide, false, "📁  ${s.name}", if (s == usable.first()) FocusFirst() else Modifier) { source = s.id; share = null; path = "" }
                    }
                    share == null -> items(shares.orEmpty(), key = { it }) { s ->
                        ArtChoice(null, wide, false, "📁  $s", if (s == shares?.first()) FocusFirst() else Modifier) { share = s; path = "" }
                    }
                    else -> items(entries.orEmpty(), key = { it.path }) { e ->
                        val focus = if (e == entries?.first()) FocusFirst() else Modifier
                        if (e.isDir) ArtChoice(null, wide, false, "📁  ${e.name}", focus) { path = e.path }
                        else {
                            val image = NasImage(src.id, share!!, e.path, e.modified + e.size)
                            ArtChoice(image, wide, false, e.name, focus) { onPick(image) }
                        }
                    }
                }
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
