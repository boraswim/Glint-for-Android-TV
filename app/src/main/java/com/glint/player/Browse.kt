package com.glint.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** What a library grid shows. */
sealed interface Kind {
    data object Movies : Kind
    data object Shows : Kind
    data object Others : Kind
    data object Collections : Kind
    data object Watchlist : Kind
    data object Playlists : Kind
    data class Playlist(val name: String) : Kind
    data class Genre(val name: String) : Kind
    data class Actor(val name: String) : Kind
}

// ---- Home ----

@Composable
fun HomeScreen(nav: Nav) {
    val lib = App.library
    val data by lib.data.collectAsState()
    val progress by lib.progress.collectAsState()
    val user by lib.user.collectAsState()
    val screen = Screen.Home

    val watching = remember(data, progress) {
        val cw = lib.continueWatching()
        cw + lib.upNext().filter { u -> cw.none { it.id == u.id } }
    }
    val picks = remember(data) { if (lib.settings.picksOfTheDay) lib.picksOfTheDay() else emptyList() }
    val watchlist = remember(data, user) { user.watchlist.mapNotNull { id -> lib.findMovie(id)?.let { Item.MovieItem(it) } ?: lib.findShow(id)?.let { Item.ShowItem(it) } } }
    val recentMovies = remember(data) { data.movies.filter { it.catalogued }.sortedByDescending { it.file.added }.take(20).map { Item.MovieItem(it) } }
    val recentShows = remember(data) { data.shows.sortedByDescending { s -> s.episodes.maxOf { it.file.added } }.take(20).map { Item.ShowItem(it) } }

    var sheet by remember { mutableStateOf<Item?>(null) }
    val firstTile = remember { FocusRequester() }
    val searchButton = remember { FocusRequester() }
    // Up from anywhere in the top row goes to Search, the top bar's first button; left to itself focus would
    // take whichever button is nearest above (Settings, from the right of the row). onKeyEvent comes after
    // the cards' own handling, so a Favorites tile being moved still keeps Up to itself.
    val toTopBar = Modifier.onKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) { runCatching { searchButton.requestFocus() }; true } else false
    }
    var anyFocus by remember { mutableStateOf(false) }
    // Where focus goes back to, decided once on arrival. Following it as focus moves made a film that is on
    // two rows (Watching and Recently Added) pull focus to its other copy as soon as one was focused. Dropped
    // once focus has landed: a row scrolled away and back is composed again and would grab focus anew.
    // Home always comes back at its top, Watching and Favorites in view: focus returns to where it was only
    // if that is in one of those two rows (a film just played is in Watching too), else to Watching's first card.
    var wanted by remember {
        val last = nav.focused(screen)?.takeIf { id -> watching.any { it.id == id } || id.startsWith("tile:") || id.startsWith("pin:") }
        mutableStateOf(last ?: watching.firstOrNull()?.id)
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Watching starts at the card focus goes back to: one further along isn't composed yet and couldn't take it.
    val watchingRow = androidx.compose.foundation.lazy.rememberLazyListState(watching.indexOfFirst { it.id == wanted }.coerceAtLeast(0))
    // Until focus has settled, a card or tile already on screen takes it without the list moving: the TV would
    // otherwise park a Favorites tile a third of the way down, pushing Watching off the top.
    var settling by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(400)
        // If nothing claimed focus (empty rows, slow load), start on the first Favorites tile, never the top bar.
        if (!anyFocus) runCatching { firstTile.requestFocus() }
        delay(400)
        settling = false
    }

    Box(Modifier.fillMaxSize()) {
        ScreenBackground()
        // Home's text (row titles, captions, tile names, the search pill) reads from the sofa at 1.5×;
        // sizes of cards and tiles stay as they are, and menus opened from here keep the normal size.
        androidx.compose.runtime.CompositionLocalProvider(LocalTextScale provides 1.5f, rememberSettleScroll { settling }) {
        Column(Modifier.fillMaxSize().onFocusChanged { anyFocus = it.hasFocus; if (it.hasFocus) wanted = null }) {
            HomeTopBar(nav, searchButton)
            if (data.movies.isEmpty() && data.shows.isEmpty()) EmptyLibrary()
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, contentPadding = PaddingValues(bottom = 60.dp)) {
                if (watching.isNotEmpty()) item {
                    Column(toTopBar) {
                        // The title sits as far above the cards as Favorites' title above its tiles (measured with
                        // no card selected); the 9dp taken from its gap goes above it, so the cards stay put.
                        Gap(9.dp)
                        Shelf(stringResource(R.string.shelf_watching), watchingRow, titleGap = 4.dp) {
                            items(watching, key = { it.id }) {
                                // The cards' title and year a size below Home's other text; corners rounded as
                                // about 5% of the card's width.
                                androidx.compose.runtime.CompositionLocalProvider(LocalTextScale provides 1.35f) {
                                    WideCard(it, width = 324.dp, wanted = wanted, captionOnFocus = true, corner = 16.dp, onLongClick = { i -> sheet = i }, onFocus = { i -> nav.remember(screen, i.id); wakeFor(i) }, onClick = { i -> play(nav, i) })
                                }
                            }
                        }
                    }
                }
                // The rows after Favorites sit 36dp higher, so the next row's title shows on the first screen;
                // Favorites itself only 22dp, leaving it room below the Watching captions.
                item {
                    Box((if (watching.isEmpty()) toTopBar else Modifier).layout { m, c ->
                        val p = m.measure(c)
                        layout(p.width, (p.height - 36.dp.roundToPx()).coerceAtLeast(0)) { p.place(0, -22.dp.roundToPx()) }
                    }) { FavoritesRow(nav, firstTile, wanted) }
                }
                for (p in user.pins.filter { it.row }) item(key = "pinrow:${p.key}") {
                    val list = remember(data, user) { lib.pinItems(p.key) }
                    if (list.isNotEmpty()) Shelf(p.label) {
                        items(list, key = { "pr" + p.key + it.id }) { PosterCard(it, wanted = null, onLongClick = { i -> sheet = i }, onFocus = { i -> nav.remember(screen, i.id) }, onClick = nav::open) }
                    }
                }
                if (picks.isNotEmpty()) item {
                    Shelf(stringResource(R.string.shelf_picks)) {
                        items(picks, key = { "p" + it.id }) { PosterCard(it, wanted = null, onLongClick = { i -> sheet = i }, onFocus = { i -> nav.remember(screen, i.id) }, onClick = nav::open) }
                    }
                }
                if (watchlist.isNotEmpty()) item {
                    Shelf(stringResource(R.string.shelf_watchlist)) {
                        items(watchlist, key = { "w" + it.id }) { PosterCard(it, wanted = null, onLongClick = { i -> sheet = i }, onFocus = { i -> nav.remember(screen, i.id) }, onClick = nav::open) }
                    }
                }
                if (recentMovies.isNotEmpty()) item {
                    Shelf(stringResource(R.string.shelf_recent_movies)) {
                        items(recentMovies, key = { "r" + it.id }) { PosterCard(it, wanted = null, onLongClick = { i -> sheet = i }, onFocus = { i -> nav.remember(screen, i.id) }, onClick = nav::open) }
                    }
                }
                if (recentShows.isNotEmpty()) item {
                    Shelf(stringResource(R.string.shelf_recent_shows)) {
                        items(recentShows, key = { "s" + it.id }) { PosterCard(it, wanted = null, onLongClick = { i -> sheet = i }, onFocus = { i -> nav.remember(screen, i.id) }, onClick = nav::open) }
                    }
                }
            }
        }
        }
        sheet?.let { ItemMenu(it, nav) { sheet = null } }
    }
}

@Composable
private fun HomeTopBar(nav: Nav, search: FocusRequester) {
    // Room below keeps the rows under Favorites below the bottom edge, rather than a sliver of the next title.
    Row(Modifier.fillMaxWidth().padding(start = 80.dp, end = 80.dp, top = 34.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton(stringResource(R.string.search), Modifier.focusRequester(search), icon = SearchGlyph, bare = true) { nav.push(Screen.Search) }
        Spacer(Modifier.weight(1f))
        // Bare glyphs. Refresh rescans the shares for new files (and turns while it does).
        val scan by App.library.scan.collectAsState()
        BareIcon(Icons.Outlined.Refresh, spinning = scan?.finished == false, size = 62.dp, glyph = 44.dp) { App.library.scanAll() }
        Spacer(Modifier.width(18.dp))
        BareIcon(Icons.Outlined.Settings, size = 62.dp, glyph = 38.dp) { nav.push(Screen.Settings) }
    }
}

/** The coloured shortcut tiles: built-in categories, minus hidden ones, plus pinned categories and folders. */
@Composable
private fun FavoritesRow(nav: Nav, first: FocusRequester, wanted: String?) {
    val lib = App.library
    val user by lib.user.collectAsState()
    val tiles = listOf(
        Triple(R.string.shelf_collections, TileGlyphs.Collections, listOf(Color(0xFFFF5E1A), Color(0xFFFF9F1C))) to { nav.push(Screen.Grid(Kind.Collections)) },
        Triple(R.string.tab_movies, TileGlyphs.Movies, listOf(Color(0xFF1E5BFF), Color(0xFF19C3F5))) to { nav.push(Screen.Grid(Kind.Movies)) },
        Triple(R.string.tab_shows, TileGlyphs.Shows, listOf(Color(0xFFFF2D6F), Color(0xFFC94BFF))) to { nav.push(Screen.Grid(Kind.Shows)) },
        Triple(R.string.shelf_other_videos, TileGlyphs.Others, listOf(Color(0xFF17A63A), Color(0xFF7DDB3E))) to { nav.push(Screen.Grid(Kind.Others)) },
        Triple(R.string.by_genre, TileGlyphs.Genres, listOf(Color(0xFF00897B), Color(0xFF26C6DA))) to { nav.push(Screen.Genres) },
        Triple(R.string.shelf_watchlist, TileGlyphs.Watchlist, listOf(Color(0xFFE6A500), Color(0xFFFFD43B))) to { nav.push(Screen.Grid(Kind.Watchlist)) },
        Triple(R.string.playlists, TileGlyphs.Playlists, listOf(Color(0xFF7B2FF7), Color(0xFFF107A3))) to { nav.push(Screen.Grid(Kind.Playlists)) },
        Triple(R.string.tab_folders, TileGlyphs.Folders, listOf(Color(0xFF5C6B80), Color(0xFF8A9BB3))) to { nav.push(Screen.Folder()) },
    )
    val shown = tiles.indices.filter { it !in user.hiddenTiles }
    val pinned = user.pins.filter { !it.row }
    class Entry(val id: String, val label: String, val icon: ImageVector, val colors: List<Color>, val art: Any?, val remove: () -> Unit, val open: () -> Unit)
    val entries = shown.map { i ->
        val (spec, action) = tiles[i]
        Entry("tile:$i", stringResource(spec.first), spec.second, spec.third, null, { lib.hideTile(i) }, action)
    } + pinned.map { p -> Entry("pin:${p.key}", p.label, pinIcon(p.key), pinColors(p.key), pinArt(p.key), { lib.unpin(p) }, { openPin(nav, p.key) }) }
    // The viewer's order first; tiles it doesn't name yet (new pins) keep their place after them.
    var order by remember(user.tileOrder) { mutableStateOf(user.tileOrder) }
    val sorted = entries.sortedBy { e -> order.indexOf(e.id).let { if (it < 0) Int.MAX_VALUE else it } }
    var menu by remember { mutableStateOf<Entry?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun finishMove() { lib.setTileOrder(sorted.map { it.id }); moving = null }
    fun shift(id: String, by: Int) {
        val ids = sorted.map { it.id }.toMutableList()
        val from = ids.indexOf(id); val to = from + by
        if (to !in ids.indices) return
        java.util.Collections.swap(ids, from, to)
        order = ids
        val seen = rowState.layoutInfo.visibleItemsInfo
        if (seen.isNotEmpty() && (to <= seen.first().index || to >= seen.last().index))
            scope.launch { rowState.animateScrollToItem((if (by > 0) to - 2 else to).coerceAtLeast(0)) }
    }
    BackHandler(enabled = moving != null) { finishMove() }
    // The menu that started the move took focus away; hand it back to the lifted tile so it gets the arrows.
    val liftFocus = remember { FocusRequester() }
    LaunchedEffect(moving) { if (moving != null) { kotlinx.coroutines.delay(80); runCatching { liftFocus.requestFocus() } } }
    Shelf(if (moving != null) stringResource(R.string.shelf_favorites) + "   ·   " + stringResource(R.string.move_tile_hint) else stringResource(R.string.shelf_favorites), rowState) {
        items(sorted.size, key = { sorted[it].id }) { n ->
            val e = sorted[n]
            val lifted = moving == e.id
            FavoriteTile(
                e.label, e.icon, e.colors, e.art,
                (if (n == 0) Modifier.focusRequester(first) else Modifier).then(if (lifted) Modifier.focusRequester(liftFocus) else Modifier)
                    .restoreFocus(e.id, wanted)
                    .onFocusChanged { if (it.isFocused) nav.remember(Screen.Home, e.id) }
                    .onPreviewKeyEvent { k ->
                        if (!lifted) return@onPreviewKeyEvent false
                        // While lifted, the arrows carry the tile and OK puts it down; nothing else gets the keys.
                        if (k.type == KeyEventType.KeyDown) when (k.key) {
                            Key.DirectionLeft -> shift(e.id, -1)
                            Key.DirectionRight -> shift(e.id, 1)
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> finishMove()
                            else -> {}
                        }
                        true
                    },
                lifted = lifted,
                onLongClick = { if (moving == null) menu = e },
                onClick = { if (moving == null) e.open() },
            )
        }
    }
    // In its own window: drawn here, inside the row, the sheet would be squeezed into the row's height.
    menu?.let { e ->
        androidx.compose.ui.window.Dialog({ menu = null }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            // A dialog brings the window's own density; the app's 1280dp canvas has to be set again.
            TvScale {
                OptionSheet(e.label, listOf(
                    stringResource(R.string.move_tile) to { moving = e.id },
                    stringResource(R.string.remove_from_home) to e.remove,
                )) { menu = null }
            }
        }
    }
}

/** Spins up the NAS disks behind a film or episode the viewer is about to play. */
private fun wakeFor(item: Item) = when (item) {
    is Item.MovieItem -> App.library.wake(item.movie.file)
    is Item.EpisodeItem -> App.library.wake(item.episode.file)
    else -> Unit
}

fun openPin(nav: Nav, key: String) {
    val arg = key.substringAfter(':')
    when (key.substringBefore(':')) {
        "genre" -> nav.push(Screen.Grid(Kind.Genre(arg)))
        "actor" -> nav.push(Screen.Grid(Kind.Actor(arg)))
        "playlist" -> nav.push(Screen.Grid(Kind.Playlist(arg)))
        "collection" -> arg.toIntOrNull()?.let { nav.push(Screen.Collection(it)) }
        "folder" -> arg.split('|', limit = 3).let { if (it.size == 3) nav.push(Screen.Folder(it[0], it[1], it[2])) }
    }
}

private fun pinIcon(key: String): ImageVector = when (key.substringBefore(':')) {
    "genre" -> Icons.Filled.TheaterComedy
    "actor" -> Icons.Filled.Person
    "playlist" -> Icons.AutoMirrored.Filled.PlaylistPlay
    "collection" -> Icons.Filled.VideoLibrary
    else -> Icons.Filled.Folder
}

private fun pinColors(key: String): List<Color> {
    val palette = listOf(
        listOf(Color(0xFF0F9B8E), Color(0xFF55D6BE)), listOf(Color(0xFFB31217), Color(0xFFE52D27)), listOf(Color(0xFF373B44), Color(0xFF4286F4)),
        listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0)), listOf(Color(0xFFF7971E), Color(0xFFFFD200)), listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
    )
    return palette[(key.hashCode() and 0x7fffffff) % palette.size]
}

/** A pinned folder can carry its own tile picture: "favorite-atv.jpg" (or .png) inside it. */
private fun pinArt(key: String): Any? {
    if (!key.startsWith("folder:")) return null
    val (src, share, path) = key.removePrefix("folder:").split('|', limit = 3).let { if (it.size == 3) Triple(it[0], it[1], it[2]) else return null }
    return NasImage(src, share, Smb.join(path, "favorite-atv.jpg"))
}

/** Long-press menu for a category: put it on Home as a tile or as a row. */
@Composable
fun PinSheet(key: String, label: String, onDismiss: () -> Unit) {
    val lib = App.library
    OptionSheet(label, listOf(
        stringResource(R.string.pin_as_favorite) to { lib.pin(Pin(key, label, row = false)) },
        stringResource(R.string.pin_as_list) to { lib.pin(Pin(key, label, row = true)) },
    ), onDismiss)
}

@Composable
private fun FavoriteTile(label: String, icon: ImageVector, colors: List<Color>, art: Any?, modifier: Modifier, lifted: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (lifted) 1.16f else if (focused) 1.1f else 1f, label = "tile")
    // Corners about 6% of the tile's width.
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.width(225.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier.scale(scale).shadow(if (lifted) 30.dp else if (focused) 18.dp else 0.dp, shape)
                .width(225.dp).height(126.dp).clip(shape)
                .background(Brush.linearGradient(colors))
                .combinedClickable(source, indication = null, onLongClick = onLongClick, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // Glass finish: light falls from the top, a soft glow sits behind the glyph, a hairline rims the edge.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.2f), 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.22f))))
            Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.2f), Color.Transparent), radius = 340f)))
            Box(Modifier.matchParentSize().border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.06f))), shape))
            TileIcon(icon, colors)
            if (art != null) AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (lifted) Box(Modifier.matchParentSize().border(4.dp, Color.White, shape))
        }
        Txt(label, Type.Meta.copy(color = if (focused) Palette.Text else Palette.Dim, fontSize = 13.5.sp), Modifier.padding(top = 14.dp), maxLines = 1)
    }
}

@Composable
fun EmptyLibrary() {
    val scan by App.library.scan.collectAsState()
    Column(Modifier.padding(horizontal = 80.dp, vertical = 24.dp)) {
        val s = scan
        Txt(
            when {
                s == null -> stringResource(R.string.library_empty)
                s.finished -> s.error ?: s.message
                else -> s.message
            },
            Type.Heading,
        )
        if (s != null && !s.finished) { Gap(6.dp); Txt(stringResource(R.string.library_filling), Type.Body) }
    }
}

// ---- Overlays ----

/** A centred list of choices over a dimmed screen; Back closes it. */
@Composable
fun OptionSheet(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    // A sheet opened by holding OK still gets that press's repeats and its release; without this
    // the first option would fire when the button is let go. Only a fresh press counts.
    var fresh by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000)).onPreviewKeyEvent { e ->
            val ok = e.key == Key.Enter || e.key == Key.DirectionCenter || e.key == Key.NumPadEnter
            when {
                !ok -> false
                e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> { fresh = true; false }
                else -> !fresh
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        // Opened from the Home rows, the sheet would otherwise take their enlarged text.
        androidx.compose.runtime.CompositionLocalProvider(LocalTextScale provides 1f) {
            Column(Modifier.width(520.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xF2303236)).padding(26.dp)) {
                Txt(title, Type.Heading, Modifier.fillMaxWidth().padding(bottom = 16.dp), maxLines = 2, align = TextAlign.Center)
                options.forEachIndexed { i, (label, action) ->
                    ListRow(label, modifier = if (i == 0) FocusFirst() else Modifier) { action(); onDismiss() }
                    Gap(8.dp)
                }
            }
        }
    }
}

/** Long-press menu for a film, series or episode. */
@Composable
fun ItemMenu(item: Item, nav: Nav, onDismiss: () -> Unit) {
    val lib = App.library
    val user by lib.user.collectAsState()
    val progress by lib.progress.collectAsState()
    val ids = when (item) {
        is Item.MovieItem -> listOf(item.movie.id)
        is Item.ShowItem -> item.show.episodes.map { it.id }
        is Item.EpisodeItem -> listOf(item.episode.id)
        is Item.CollectionItem -> item.all.flatMap(::playableKeys)
    }
    val watched = ids.isNotEmpty() && ids.all { progress[it]?.watched == true }
    val listId = when (item) { is Item.EpisodeItem -> item.show.key; else -> item.id }
    var adding by remember { mutableStateOf(false) }
    if (adding) { AddToListSheet(R.string.add_to_collection, listId, item.title, onDismiss); return }
    val options = buildList {
        if (item is Item.MovieItem || item is Item.EpisodeItem) add(stringResource(R.string.play) to { play(nav, item) })
        add(stringResource(R.string.open_details) to { nav.open(item) })
        add(stringResource(if (watched) R.string.mark_unwatched else R.string.mark_watched) to { lib.setWatched(ids, !watched) })
        // Out of Watching: its place is forgotten, as "mark unwatched" does (an upcoming episode has none to forget).
        if ((item is Item.MovieItem || item is Item.EpisodeItem) && progress[ids.first()]?.started == true)
            add(stringResource(R.string.remove_from_watching) to { lib.setWatched(ids, false) })
        if (item !is Item.CollectionItem) add(stringResource(if (listId in user.watchlist) R.string.watchlist_remove else R.string.watchlist_add) to { lib.toggleWatchlist(listId) })
        if (item is Item.MovieItem || item is Item.ShowItem) add(stringResource(R.string.add_to_collection) to { adding = true })
    }
    OptionSheet(item.title, options, onDismiss)
}

/** Choices of a grid's filter button; [GridScreen] applies them by index. */
private val FILTERS = listOf(R.string.filter_all, R.string.filter_unwatched, R.string.filter_watched, R.string.filter_in_progress, 0, 0, 0)

@Composable
private fun filterLabel(i: Int) = when (i) {
    4 -> "4K"
    5 -> "Dolby Vision"
    6 -> "HDR"
    else -> stringResource(FILTERS[i])
}

@Composable
fun SortSheet(onDismiss: () -> Unit) {
    val s = App.library.settings
    val labels = listOf(R.string.sort_title, R.string.sort_added, R.string.sort_year, R.string.sort_rating, R.string.sort_duration, R.string.sort_file_name)
    OptionSheet(stringResource(R.string.sort_order), labels.mapIndexed { i, r -> ((if (s.sortOrder == i) "✓  " else "") + stringResource(r)) to { s.sortOrder = i } }, onDismiss)
}

// ---- Grids ----

@Composable
fun GridScreen(nav: Nav, kind: Kind) {
    val lib = App.library
    val s = lib.settings
    val data by lib.data.collectAsState()
    val progress by lib.progress.collectAsState()
    val user by lib.user.collectAsState()
    val screen = Screen.Grid(kind)
    var sortTick by remember { mutableStateOf(0) }
    // Kept by Nav, so opening a film and coming back doesn't reset it.
    val filter = nav.filters[screen] ?: 0
    var filtering by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Item?>(null) }

    val items = remember(data, progress, user, filter, sortTick, sorting) {
        // The filter menu's choices, in FILTERS order: all, unwatched, watched, in progress, 4K, Dolby Vision, HDR.
        fun keep(id: String, fileName: String): Boolean {
            val p = progress[id]
            val b by lazy { badges(fileName) }
            return when (filter) {
                1 -> p?.watched != true
                2 -> p?.watched == true
                3 -> p?.inProgress == true
                4 -> "4K" in b
                5 -> "Dolby Vision" in b
                6 -> "HDR" in b || "HDR10+" in b
                else -> true
            }
        }
        fun keepShow(sh: Show) = when (filter) {
            1 -> sh.episodes.any { progress[it.id]?.watched != true }
            2 -> sh.episodes.all { progress[it.id]?.watched == true }
            else -> sh.episodes.any { keep(it.id, it.file.name) }
        }
        fun sortMovies(list: List<Movie>) = when (s.sortOrder) {
            1 -> list.sortedByDescending { it.file.added }
            2 -> list.sortedByDescending { it.releaseDate.ifEmpty { it.year } }
            3 -> list.sortedByDescending { it.rating }
            4 -> list.sortedByDescending { it.runtime }
            5 -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.file.name })
            else -> list.sortedBy { sortTitle(it.title) }
        }
        fun sortShows(list: List<Show>) = when (s.sortOrder) {
            1 -> list.sortedByDescending { sh -> sh.episodes.maxOf { it.file.added } }
            2 -> list.sortedByDescending { it.year }
            3 -> list.sortedByDescending { it.rating }
            5 -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { sh -> sh.episodes.minOfOrNull { it.file.path } ?: sh.title })
            else -> list.sortedBy { sortTitle(it.title) }
        }
        // Smart groups: several copies of one film (cuts, resolutions) show as one poster.
        val films = data.movies.filter { it.catalogued && keep(it.id, it.file.name) }
            .distinctBy { if (it.tmdbId != 0) "t${it.tmdbId}" else it.id }
        when (kind) {
            Kind.Movies -> {
                val sorted = sortMovies(films)
                if (!s.groupMovieCollections) sorted.map { Item.MovieItem(it) }
                else {
                    // Films of a collection collapse into one "N items" poster, placed where its first film would be.
                    val cols = lib.collections().associateBy { it.collectionId }
                    val seen = HashSet<Int>()
                    sorted.mapNotNull { m ->
                        val c = cols[m.collectionId]
                        when {
                            c == null -> Item.MovieItem(m)
                            seen.add(c.collectionId) -> c
                            else -> null
                        }
                    }
                }
            }
            Kind.Shows -> sortShows(data.shows.filter(::keepShow)).map { Item.ShowItem(it) }
            Kind.Others -> data.movies.filter { !it.catalogued && keep(it.id, it.file.name) }.sortedByDescending { it.file.modified }.map { Item.MovieItem(it) }
            Kind.Collections -> lib.collections()
            Kind.Watchlist -> user.watchlist.mapNotNull { id -> lib.findMovie(id)?.let { Item.MovieItem(it) } ?: lib.findShow(id)?.let { Item.ShowItem(it) } }
            Kind.Playlists -> emptyList()
            is Kind.Playlist -> user.playlists[kind.name].orEmpty().mapNotNull { id -> lib.findMovie(id)?.let { Item.MovieItem(it) } ?: lib.findEpisode(id)?.let { (sh, e) -> Item.EpisodeItem(sh, e) } }
            is Kind.Genre -> sortMovies(films.filter { kind.name in it.genres }).map { Item.MovieItem(it) } +
                sortShows(data.shows.filter { kind.name in it.genres && keepShow(it) }).map { Item.ShowItem(it) }
            is Kind.Actor -> sortMovies(films.filter { m -> m.cast.any { it.name == kind.name } || m.director == kind.name }).map { Item.MovieItem(it) } +
                data.shows.filter { sh -> sh.cast.any { it.name == kind.name } }.map { Item.ShowItem(it) }
        }
    }
    val title = when (kind) {
        Kind.Movies -> stringResource(R.string.all_movies)
        Kind.Shows -> stringResource(R.string.all_shows)
        Kind.Others -> stringResource(R.string.shelf_other_videos)
        Kind.Collections -> stringResource(R.string.shelf_collections)
        Kind.Watchlist -> stringResource(R.string.shelf_watchlist)
        Kind.Playlists -> stringResource(R.string.playlists)
        is Kind.Playlist -> kind.name
        is Kind.Genre -> kind.name
        is Kind.Actor -> kind.name
    }
    Box(Modifier.fillMaxSize()) {
        ScreenBackground()
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth()) {
                val shown = if (filter == 0) title else "$title  ·  ${filterLabel(filter)}"
                ScreenTitle("$shown  ·  ${items.size}")
                Row(Modifier.align(Alignment.CenterEnd).padding(end = 80.dp, top = 22.dp)) {
                    IconCircle(Icons.AutoMirrored.Filled.Sort, size = 46.dp) { sorting = true }
                    Spacer(Modifier.width(14.dp))
                    IconCircle(Icons.Filled.FilterList, size = 46.dp, active = filter != 0) { filtering = true }
                }
            }
            if (items.isEmpty()) Txt(stringResource(R.string.nothing_here), Type.Body, Modifier.fillMaxWidth().padding(top = 40.dp), align = TextAlign.Center)
            val wide = kind == Kind.Others
            val wanted = nav.focused(screen) ?: items.firstOrNull()?.id
            // Coming back from a title: open scrolled to it (a row above it showing), so its card exists to take focus again.
            val at = remember { items.indexOfFirst { it.id == wanted }.coerceAtLeast(0) }
            val cols = if (wide) 4 else 6
            if (s.listView && !wide) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 160.dp), androidx.compose.foundation.lazy.rememberLazyListState((at - 1).coerceAtLeast(0)), contentPadding = PaddingValues(top = 14.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(items, key = { it.id }) { item ->
                        PosterRow(item, Modifier.restoreFocus(item.id, wanted), onLongClick = { sheet = item }) { nav.remember(screen, item.id); nav.open(item) }
                    }
                }
            } else LazyVerticalGrid(
                GridCells.Fixed(cols),
                Modifier.fillMaxSize(),
                androidx.compose.foundation.lazy.grid.rememberLazyGridState((at / cols - 1).coerceAtLeast(0) * cols),
                // Six 177dp posters across the 1280dp screen; 34dp gaps fit the focused one's 10% zoom.
                contentPadding = if (wide) PaddingValues(start = 80.dp, end = 80.dp, top = 18.dp, bottom = 60.dp) else PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 60.dp),
                horizontalArrangement = Arrangement.spacedBy(if (wide) 26.dp else 34.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    if (wide) WideCard(item, width = 255.dp, wanted = wanted, label = (item as? Item.MovieItem)?.movie?.file?.name, onLongClick = { sheet = it }, onFocus = { nav.remember(screen, it.id) }, onClick = { play(nav, it) })
                    else PosterCard(item, width = 177.dp, wanted = wanted, onLongClick = { sheet = it }, onFocus = { nav.remember(screen, it.id) }, onClick = nav::open)
                }
            }
        }
        sheet?.let { ItemMenu(it, nav) { sheet = null } }
        if (sorting) SortSheet { sorting = false; sortTick++ }
        if (filtering) OptionSheet(
            stringResource(R.string.filter_title),
            FILTERS.indices.map { i -> ((if (filter == i) "✓  " else "") + filterLabel(i)) to { nav.filters[screen] = i } },
        ) { filtering = false }
    }
}

/** The files an item stands for, in viewing order. */
fun playableKeys(item: Item): List<String> = when (item) {
    is Item.MovieItem -> listOf(item.movie.id)
    is Item.EpisodeItem -> listOf(item.episode.id)
    is Item.ShowItem -> item.show.sortedEpisodes().map { it.id }
    is Item.CollectionItem -> item.all.flatMap(::playableKeys)
}

/** All playlists, each opening its list. */
@Composable
fun PlaylistsScreen(nav: Nav) {
    val user by App.library.user.collectAsState()
    var deleting by remember { mutableStateOf<String?>(null) }
    var pinning by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().screenBackground()) {
        ScreenTitle(stringResource(R.string.playlists))
        if (user.playlists.isEmpty()) Txt(stringResource(R.string.playlists_empty), Type.Body, Modifier.fillMaxWidth().padding(top = 30.dp), align = TextAlign.Center)
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 240.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
            items(user.playlists.keys.sorted(), key = { it }) { name ->
                ListRow(name, stringResource(R.string.n_items, user.playlists[name].orEmpty().size), if (name == user.playlists.keys.sorted().first()) FocusFirst() else Modifier, chevron = true, onLongClick = { pinning = name }) {
                    nav.push(Screen.Grid(Kind.Playlist(name)))
                }
            }
        }
    }
    deleting?.let { name -> OptionSheet(name, listOf(stringResource(R.string.delete_playlist) to { App.library.deletePlaylist(name) })) { deleting = null } }
    pinning?.let { name -> PinSheet("playlist:$name", name) { pinning = null } }
}

/** One row of the list view: small poster, title, year and runtime. */
@Composable
private fun PosterRow(item: Item, modifier: Modifier, onLongClick: () -> Unit, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (focused) Color.White else Palette.Surface)
            .combinedClickable(source, indication = null, onLongClick = onLongClick, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(item.poster, item.title, Modifier.width(54.dp).height(80.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Txt(item.title, Type.Heading.copy(color = if (focused) Color.Black else Palette.Text), maxLines = 1)
            val meta = when (item) {
                is Item.MovieItem -> listOf(item.movie.year, item.movie.runtime.takeIf { it > 0 }?.let(::minutes).orEmpty(), item.movie.genres.take(2).joinToString(", "))
                is Item.ShowItem -> if (item.season != null) listOf(subtitle(item)) else listOf(item.show.year, item.show.genres.take(2).joinToString(", "))
                else -> listOf(subtitle(item))
            }
            Txt(meta.filter { it.isNotBlank() }.joinToString("  ·  "), Type.Meta.copy(color = if (focused) Color(0xFF444444) else Palette.Dim), maxLines = 1)
        }
    }
}

/** "The Matrix" sorts under M. */
fun sortTitle(t: String) = t.lowercase().removePrefix("the ").removePrefix("a ").removePrefix("an ").trim()

// ---- By genre ----

@Composable
fun GenresScreen(nav: Nav) {
    val lib = App.library
    val data by lib.data.collectAsState()
    val genres = remember(data) {
        (data.movies.filter { it.catalogued }.flatMap { m -> m.genres.map { it to (m.backdrop ?: m.poster) to m.rating } } +
            data.shows.flatMap { sh -> sh.genres.map { it to (sh.backdrop ?: sh.poster) to sh.rating } })
            .groupBy({ it.first.first }, { it.first.second to it.second })
            .map { (g, list) -> g to list.sortedByDescending { it.second }.mapNotNull { it.first }.take(2) }
            .sortedBy { it.first }
    }
    var focused by remember { mutableStateOf(genres.firstOrNull()?.first) }
    var pinning by remember { mutableStateOf<String?>(null) }
    val art = genres.firstOrNull { it.first == focused }?.second.orEmpty()
    Box(Modifier.fillMaxSize()) {
        ScreenBackground()
        Row(Modifier.fillMaxSize()) {
            // Two tall images of the focused genre's best-rated titles.
            Crossfade(art, Modifier.weight(0.62f).fillMaxHeight(), label = "genreArt") { pics ->
                Row(Modifier.fillMaxSize()) {
                    pics.forEach { AsyncImage(it, null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop) }
                }
            }
            Column(Modifier.weight(0.38f).fillMaxHeight().background(Color(0x66000000)).padding(horizontal = 40.dp)) {
                ScreenTitle(stringResource(R.string.by_genre))
                LazyColumn(contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(genres, key = { it.first }) { (g, _) ->
                        val wanted = nav.focused(Screen.Genres) ?: genres.first().first
                        GenreRow(g, Modifier.restoreFocus(g, wanted).onFocusChanged { if (it.isFocused) { focused = g; nav.remember(Screen.Genres, g) } }, onLongClick = { pinning = g }) {
                            nav.push(Screen.Grid(Kind.Genre(g)))
                        }
                    }
                }
            }
        }
        pinning?.let { g -> PinSheet("genre:$g", g) { pinning = null } }
    }
}

@Composable
private fun GenreRow(name: String, modifier: Modifier, onLongClick: () -> Unit, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "genre")
    Box(
        modifier.scale(scale).fillMaxWidth().shadow(if (focused) 12.dp else 0.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp)).background(if (focused) Color.White else Color.Transparent)
            .combinedClickable(source, indication = null, onLongClick = onLongClick, onClick = onClick).padding(horizontal = 24.dp, vertical = 11.dp)
    ) { Txt(name, Type.Heading.copy(color = if (focused) Color.Black else Palette.Dim, fontSize = 21.sp), maxLines = 1) }
}

// ---- Collection ----

@Composable
fun CollectionScreen(nav: Nav, id: Int) {
    val lib = App.library
    val data by lib.data.collectAsState()
    val user by lib.user.collectAsState()   // own collections live in the viewer's data
    val col = remember(data, user) { lib.collections().firstOrNull { it.collectionId == id } }
    // Its last item taken out: the collection is gone, so leave its page.
    if (col == null) { LaunchedEffect(Unit) { nav.pop() }; return }
    val screen = Screen.Collection(id)
    var sheet by remember { mutableStateOf<Item?>(null) }
    Box(Modifier.fillMaxSize()) {
        ScreenBackground()
        Backdrop(col.backdrop)
        Column(Modifier.fillMaxSize().padding(top = 70.dp)) {
            Txt(col.name, Type.Hero, Modifier.padding(start = 80.dp, end = 80.dp), maxLines = 2)
            Gap(6.dp)
            val years = col.movies.map { it.year } + col.others.map { it.show.year }
            Txt(stringResource(R.string.n_items, col.size) + years.filter { it.isNotBlank() }.let { if (it.isEmpty()) "" else "  ·  ${it.min()}–${it.max()}" }, Type.Meta, Modifier.padding(start = 80.dp))
            Gap(30.dp)
            Shelf("") {
                items(col.all, key = { it.id }) {
                    PosterCard(it, wanted = nav.focused(screen) ?: col.all.first().id, onFocus = { i -> nav.remember(screen, i.id) }, onLongClick = { i -> sheet = i }, onClick = nav::open)
                }
            }
        }
        // Own collections (negative ids): hold OK on an item to take it out again.
        sheet?.let { i ->
            OptionSheet(i.title, buildList {
                add(stringResource(R.string.open_details) to { nav.open(i) })
                if (id < 0) add(stringResource(R.string.remove_from_collection) to { lib.removeFromCollection(col.name, i.id) })
            }) { sheet = null }
        }
    }
}

// ---- Search ----

@Composable
fun SearchScreen(nav: Nav) {
    val lib = App.library
    val data by lib.data.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(data, query) {
        val q = Names.normalise(query)
        if (q.length < 2) emptyList() else {
            val words = q.split(' ')
            fun hit(vararg fields: String) = words.all { w -> fields.any { Names.normalise(it).contains(w) } }
            data.movies.filter { m -> hit(m.title, m.year, m.director, m.genres.joinToString(" "), m.cast.take(8).joinToString(" ") { it.name }, m.collectionName, m.file.name) }.map { Item.MovieItem(it) } +
                data.shows.filter { sh -> hit(sh.title, sh.year, sh.genres.joinToString(" "), sh.cast.take(8).joinToString(" ") { it.name }) }.map { Item.ShowItem(it) }
        }
    }
    val r = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { r.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        ScreenBackground()
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                TextInput(query, { query = it }, stringResource(R.string.search_hint), Modifier.focusRequester(r).width(620.dp))
            }
            if (query.length >= 2 && results.isEmpty()) Txt(stringResource(R.string.search_none), Type.Body, Modifier.fillMaxWidth().padding(top = 30.dp), align = TextAlign.Center)
            LazyVerticalGrid(
                GridCells.Fixed(6),
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 60.dp),
                horizontalArrangement = Arrangement.spacedBy(34.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp),
            ) {
                items(results, key = { it.id }) { item -> PosterCard(item, width = 177.dp, wanted = nav.focused(Screen.Search), onFocus = { nav.remember(Screen.Search, it.id) }, onClick = nav::open) }
            }
        }
    }
}
