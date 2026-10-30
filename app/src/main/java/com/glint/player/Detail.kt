package com.glint.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/** "2007-07-26" → "26 Tem 2007" in the viewer's language. */
private fun date(iso: String): String = runCatching {
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso)!!)
}.getOrDefault(iso)

/** Resolution and audio from the file name, as the meta line shows them: "4K", "TrueHD Atmos 7.1". */
private fun techLine(file: VideoFile): List<String> {
    val b = badges(file)
    val res = b.firstOrNull { it in setOf("4K", "1080p", "720p") }
    val audio = b.filter { it in setOf("TrueHD", "DTS-HD MA", "DTS", "Dolby Digital+", "Atmos", "DTS:X", "7.1", "5.1", "2.0") }.joinToString(" ")
    return listOfNotNull(res, audio.ifBlank { null })
}

private fun discLabel(disc: String) = if (disc == "bluray") "Blu-ray" else "DVD"

/**
 * Plays the trailer straight away in YouTube for Android TV's bare player, which closes back to us;
 * started from our own activity so it stacks on top of it rather than on YouTube's home screen.
 * Without the TV app, any YouTube link handler gets the plain watch URL.
 */
private fun openTrailer(ctx: Context, key: String) {
    val url = Uri.parse("https://www.youtube.com/watch?v=$key")
    val tvApp = Intent(Intent.ACTION_VIEW, url).setPackage("com.google.android.youtube.tv").putExtra("finish_on_ended", true)
    runCatching { ctx.startActivity(tvApp) }.onFailure {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
private fun rememberTrailer(tmdbId: Int, tv: Boolean): String? {
    var key by remember(tmdbId) { mutableStateOf<String?>(null) }
    LaunchedEffect(tmdbId) {
        if (tmdbId != 0 && App.library.tmdb.enabled) key = withContext(Dispatchers.IO) { runCatching { App.library.tmdb.trailer(tmdbId, tv) }.getOrNull() }
    }
    return key
}

/** Logo (or title), an optional episode line, meta line, genres, ratings and plot. */
@Composable
private fun DetailHeader(
    title: String, logo: String?, line: String?, meta: List<String>, certification: String,
    genres: List<String>, tmdbRating: Double, myStars: Int, overview: String, chips: List<String> = emptyList(),
    tmdbId: Int = 0, tv: Boolean = false,
) = Box(Modifier.fillMaxWidth()) {
    Column { DetailHeaderText(title, logo, line, meta, certification, genres, myStars, overview, chips) }
    if (App.library.settings.showRatings) ScoreBadge(tmdbId, tv, tmdbRating, Modifier.align(Alignment.TopEnd))
}

@Composable
private fun DetailHeaderText(
    title: String, logo: String?, line: String?, meta: List<String>, certification: String,
    genres: List<String>, myStars: Int, overview: String, chips: List<String>,
) {
    val s = App.library.settings
    if (logo != null && s.showLogos) AsyncImage(logo, title, Modifier.height(96.dp).widthIn(max = 460.dp), alignment = Alignment.CenterStart)
    else Txt(title, Type.Hero, Modifier.widthIn(max = 800.dp), maxLines = 2)
    if (!line.isNullOrBlank()) { Gap(10.dp); Txt(line, Type.Title.copy(fontSize = 22.sp), maxLines = 1) }
    Gap(10.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        meta.filter { it.isNotBlank() }.forEachIndexed { i, m ->
            if (i == 2 && certification.isNotBlank()) Chip(certLabel(certification))
            Txt(m, Type.Heading.copy(color = Palette.Dim, fontWeight = FontWeight.Medium, fontSize = 17.sp), maxLines = 1)
        }
        if (meta.count { it.isNotBlank() } < 3 && certification.isNotBlank()) Chip(certLabel(certification))
        // HDR, remux, dub and bitrate sit on the same line, after the audio format.
        chips.forEach { Chip(it) }
    }
    if (genres.isNotEmpty()) { Gap(6.dp); Txt(genres.take(3).joinToString(", "), Type.Heading.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium)) }
    if (s.showRatings && myStars > 0) {
        Gap(8.dp)
        Txt("★".repeat(myStars) + "☆".repeat(5 - myStars), Type.Heading.copy(color = Palette.Accent, fontSize = 18.sp))
    }
    if (overview.isNotBlank()) { Gap(8.dp); Txt(overview, Type.Body.copy(fontSize = 16.sp, lineHeight = 23.sp, color = Color(0xFFDADCE0)), Modifier.widthIn(max = 660.dp), maxLines = 3) }
}

/**
 * IMDb, TMDB and Rotten Tomatoes scores side by side in one glassy capsule, for the top right corner over the
 * fanart. IMDb and Rotten Tomatoes come from [Omdb] (the viewer's own key) and appear once they're looked up.
 */
@Composable
private fun ScoreBadge(tmdbId: Int, tv: Boolean, tmdbRating: Double, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var omdb by remember(tmdbId, tv) { mutableStateOf<Omdb.Ratings?>(null) }
    LaunchedEffect(tmdbId, tv) { omdb = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Omdb.forTitle(ctx, tmdbId, tv) } }
    // IMDb out of 10, TMDB as a percentage, each after a small logo chip; then Rotten Tomatoes' critics score
    // (tomato). Emoji have no chip colours.
    val scores = listOfNotNull(
        omdb?.imdb?.takeIf { it.score > 0 }?.let { Triple("IMDb", Color(0xFFF5C518) to Color.Black, String.format(Locale.getDefault(), "%.1f", it.score)) },
        tmdbRating.takeIf { it > 0 }?.let { Triple("TMDB", Color(0xFF0D253F) to Color(0xFF01D277), "${Math.round(it * 10)}%") },
        omdb?.rottenTomatoes?.let { Triple("\uD83C\uDF45", Color.Transparent to Color.Unspecified, "$it%") },
    )
    if (scores.isEmpty()) return
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.height(43.dp).clip(shape).background(Color(0x99000000)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        scores.forEachIndexed { i, (name, colors, value) ->
            if (i > 0) Spacer(Modifier.width(20.dp))
            if (colors.first == Color.Transparent) Txt(name, Type.Heading.copy(fontSize = 19.sp))
            else Box(Modifier.clip(RoundedCornerShape(4.dp)).background(colors.first).padding(horizontal = 5.dp, vertical = 2.dp)) {
                Txt(name, Type.Small.copy(color = colors.second, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold))
            }
            Spacer(Modifier.width(8.dp))
            Txt(value, Type.Heading.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
        }
    }
}

/** Watched, watchlist, rating and more, as round buttons. */
@Composable
private fun ActionIcons(watched: Boolean, onWatched: () -> Unit, listed: Boolean, onList: () -> Unit, stars: Int, onRate: () -> Unit, onCollect: () -> Unit, onMore: () -> Unit, hero: Boolean = false) {
    if (hero) {
        // The film page's row: 80×56 rounded tiles over the fanart, 20 apart.
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            HeroTile(if (watched) Icons.Filled.Visibility else Icons.Outlined.Visibility, watched, onWatched)
            HeroTile(if (listed) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, listed, onList)
            HeroTile(if (stars > 0) Icons.Filled.Star else Icons.Filled.StarBorder, stars > 0, onRate)
            HeroTile(Icons.Outlined.LibraryAdd, false, onCollect)
            HeroTile(Icons.Filled.MoreHoriz, false, onMore)
        }
        return
    }
    // The row: watched, watchlist, rate, add to collection, more — as capsules.
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        IconCircle(if (watched) Icons.Filled.Visibility else Icons.Outlined.Visibility, active = watched, size = 50.dp, width = 82.dp, onClick = onWatched)
        IconCircle(if (listed) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, active = listed, size = 50.dp, width = 82.dp, onClick = onList)
        IconCircle(if (stars > 0) Icons.Filled.Star else Icons.Filled.StarBorder, active = stars > 0, size = 50.dp, width = 82.dp, onClick = onRate)
        IconCircle(Icons.Outlined.LibraryAdd, size = 50.dp, width = 82.dp, onClick = onCollect)
        IconCircle(Icons.Filled.MoreHoriz, size = 50.dp, width = 82.dp, onClick = onMore)
    }
}

/** Pick a playlist (or collection) to add to, or name a new one. */
@Composable
fun AddToListSheet(what: Int, id: String, title: String, onDismiss: () -> Unit) {
    val lib = App.library
    val user by lib.user.collectAsState()
    val playlist = what == R.string.add_to_playlist
    val existing = (if (playlist) user.playlists.keys else user.collections.keys).sorted()
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    fun add(n: String) { if (playlist) lib.addToPlaylist(n, id) else lib.addToCollection(n, id) }
    if (!naming) {
        OptionSheet(stringResource(what), existing.map { n -> n to { add(n) } } + (stringResource(if (playlist) R.string.new_playlist else R.string.new_collection) to { naming = true; Unit }), onDismiss = { if (!naming) onDismiss() })
    } else {
        androidx.activity.compose.BackHandler { onDismiss() }
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(560.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xF2303236)).padding(26.dp)) {
                Txt(stringResource(if (playlist) R.string.new_playlist else R.string.new_collection), Type.Heading, Modifier.fillMaxWidth().padding(bottom = 16.dp), align = TextAlign.Center)
                TextInput(name, { name = it }, title, FocusFirst().fillMaxWidth(), onDone = { if (name.isNotBlank()) { add(name.trim()); onDismiss() } })
                Gap(14.dp)
                PillButton(stringResource(R.string.save), Modifier.fillMaxWidth(), primary = true) { if (name.isNotBlank()) { add(name.trim()); onDismiss() } }
            }
        }
    }
}

@Composable
private fun RatingSheet(id: String, title: String, onDismiss: () -> Unit) {
    val lib = App.library
    val options = (5 downTo 1).map { n -> ("★".repeat(n) + "☆".repeat(5 - n)) to { lib.rate(id, n) } } +
        (stringResource(R.string.rating_clear) to { lib.rate(id, 0) })
    OptionSheet(title, options, onDismiss)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CastRow(nav: Nav, cast: List<Person>, director: String = "") {
    val people = listOfNotNull(director.ifBlank { null }?.let { Person(it, stringResource(R.string.director)) }) + cast
    if (people.isEmpty()) return
    // Focusing a photo scrolls the whole row into view, names and roles included, not just the photo.
    val whole = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    // "Cast & Crew": rounded-square photos, name in two lines at most, role below.
    Box(Modifier.bringIntoViewRequester(whole)) { Shelf(stringResource(R.string.cast_crew)) {
        items(people) { p ->
            Column(Modifier.width(152.dp)) {
                FocusCard(Modifier.size(152.dp, 162.dp), shape = RoundedCornerShape(22.dp), onFocus = { scope.launch { whole.bringIntoView() } }, onLongClick = { App.library.pin(Pin("actor:${p.name}", p.name, row = true)) }, onClick = { nav.push(Screen.Grid(Kind.Actor(p.name))) }) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF3A3D43)), contentAlignment = Alignment.Center) {
                        Txt(p.name.split(' ').mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""), Type.Title)
                        if (p.photo != null) AsyncImage(p.photo, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Gap(14.dp)
                Txt(p.name, Type.Heading.copy(color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp), maxLines = 2)
                Gap(3.dp)
                Txt(p.role, Type.Small.copy(fontSize = 13.sp, lineHeight = 17.sp), maxLines = 2)
            }
        }
    } }
}

/**
 * The film page top, one screen high: the fanart full bleed and bright (only the bottom darkens),
 * the title logo top left, scores top right; bottom left the source, title, action tiles and play
 * buttons; bottom right the facts line and the plot. Positions are in the 1280dp layout.
 */
@Composable
private fun MovieHero(
    movie: Movie, version: Movie, backdrop: Any?, logo: String?, p: Progress?, trailer: String?,
    onPlay: () -> Unit, onTrailer: () -> Unit, onFocusIn: () -> Unit = {}, onPlot: () -> Unit = {},
    plotFocus: androidx.compose.ui.focus.FocusRequester? = null, actions: @Composable () -> Unit,
) {
    val s = App.library.settings
    // Titles catalogued before logos were stored get theirs from TMDB here.
    var shownLogo by remember(movie.id) { mutableStateOf(logo) }
    LaunchedEffect(movie.id) {
        if (shownLogo == null && movie.tmdbId != 0 && App.library.tmdb.enabled)
            shownLogo = withContext(Dispatchers.IO) { App.library.tmdb.logoOf(movie.tmdbId, tv = false) }
    }
    Box(Modifier.fillMaxWidth().height(720.dp).onFocusChanged { if (it.hasFocus) onFocusIn() }) {
        if (backdrop != null) AsyncImage(fullScreen(backdrop), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 0.72f to Color.Black.copy(alpha = 0.6f), 1f to Palette.Bottom)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.3f), 0.45f to Color.Transparent)))
        if (shownLogo != null && s.showLogos) AsyncImage(
            shownLogo, movie.title, Modifier.padding(start = 76.dp, top = 33.dp).size(330.dp, 105.dp),
            contentScale = ContentScale.Fit, alignment = Alignment.TopStart,
        )
        if (s.showRatings) ScoreBadge(movie.tmdbId, false, movie.rating, Modifier.align(Alignment.TopEnd).padding(top = 46.dp, end = 60.dp))

        Column(Modifier.padding(start = 92.dp, top = 470.dp).width(480.dp)) {
            val src = App.library.source(version.file.source)
            val where = src?.let { stringResource(R.string.on_source, it.name, it.type.uppercase(Locale.ROOT)) }
            Txt(listOfNotNull(where, version.file.edition.ifBlank { null }).joinToString("  ·  "), Type.Small.copy(color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp), maxLines = 1)
            Gap(2.dp)
            Txt(movie.title, TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
            Gap(10.dp)
            actions()
            Gap(12.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val resume = p?.inProgress == true
                HeroButton(
                    if (resume) stringResource(R.string.resume_long, longClock(p!!.position)) else stringResource(R.string.play),
                    FocusFirst(), primary = true, fill = if (resume) p!!.fraction else 0f, onClick = onPlay,
                )
                if (trailer != null) HeroButton(stringResource(R.string.trailer), onClick = onTrailer)
            }
        }

        Column(Modifier.padding(start = 605.dp, top = 512.dp, end = 60.dp)) {
            HeroFacts(movie, version)
            Gap(10.dp)
            // The plot can be selected (a glass box lights up behind it); OK shows all of it.
            if (movie.overview.isNotBlank()) {
                val source = remember { MutableInteractionSource() }
                val focused by source.collectIsFocusedAsState()
                Box(
                    Modifier.offset(x = (-12).dp).then(if (plotFocus != null) Modifier.focusRequester(plotFocus) else Modifier)
                        .clip(RoundedCornerShape(12.dp)).background(if (focused) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable(source, indication = null, onClick = onPlot).padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Txt(movie.overview, Type.Body.copy(fontSize = 17.sp, lineHeight = 24.sp, color = Color.White), maxLines = 4) }
            }
        }
    }
}

/**
 * The whole plot on its own screen: the fanart blurred behind (drawn from a tiny copy, as Android 11
 * has no blur), the title and the full text in the middle. OK or Back goes back to the page.
 */
@Composable
private fun FullPlot(title: String, overview: String, backdrop: Any?, onClose: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        if (backdrop != null) AsyncImage(
            // A copy of its own, 48 px wide (not the full image from the cache), stretched over the screen.
            coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(backdrop).size(48, 27)
                .precision(coil.size.Precision.EXACT).memoryCacheKey("plot-blur:$backdrop").memoryCachePolicy(coil.request.CachePolicy.WRITE_ONLY).build(),
            null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
        Column(Modifier.width(720.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(title, TextStyle(color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold), maxLines = 2, align = TextAlign.Center)
            Gap(24.dp)
            Txt(overview, TextStyle(color = Color.White, fontSize = 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

/** "★ 7.6   2 sa. 29 dk.   2024  [16+]  4K  HDR  TrueHD 7.1  Action, Adventure" */
@Composable
private fun HeroFacts(movie: Movie, version: Movie) {
    // Sized so the whole line fits the column (about 600dp) even for a 4K HDR TrueHD film with three genres.
    val style = Type.Heading.copy(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val b = badges(version.file)
    // Genres last, as many as fit whole (none, if not even one does): one running off the edge is left out, not cut.
    var genres by remember(movie.id) { mutableStateOf(3) }
    val cert = certLabel(movie.certification)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        if (movie.rating > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, Color.White, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp))
            Txt(String.format(Locale.getDefault(), "%.1f", movie.rating), style)
        }
        if (movie.runtime > 0) Txt(
            if (movie.runtime >= 60) stringResource(R.string.duration_hm, movie.runtime / 60, movie.runtime % 60) else stringResource(R.string.duration_m, movie.runtime),
            style, maxLines = 1,
        )
        if (movie.year.isNotBlank()) Txt(movie.year, style)
        if (cert.isNotBlank()) Box(Modifier.border(1.5.dp, Color.White, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
            Txt(cert, style.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
        b.firstOrNull { it in setOf("4K", "1080p", "720p") }?.let { Txt(it, style) }
        b.firstOrNull { it in setOf("Dolby Vision", "HDR10+", "HDR") }?.let { Txt(it, style.copy(fontWeight = FontWeight.Bold)) }
        techLine(version.file).drop(1).firstOrNull()?.let { Txt(it, style, maxLines = 1) }
        if (movie.genres.isNotEmpty() && genres > 0) androidx.compose.foundation.text.BasicText(
            movie.genres.take(genres).joinToString(", "), Modifier.weight(1f, fill = false), style = style, maxLines = 1, softWrap = false,
            onTextLayout = { if (it.hasVisualOverflow && genres > 0) genres-- },
        )
    }
}

/** The film-page button: 250×60, 10dp corners; Play is orange (lighter over the watched part), others dim glass. */
@Composable
private fun HeroButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, fill: Float = 0f, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "hero")
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.scale(scale).shadow(if (focused) 14.dp else 0.dp, shape).size(250.dp, 60.dp).clip(shape)
            .background(when { primary -> Palette.Accent; focused -> Color.White; else -> HeroGlass })
            .clickable(source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (primary && fill > 0f) Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(fill.coerceIn(0f, 1f)).background(Color.White.copy(alpha = 0.22f)))
        if (primary && focused) Box(Modifier.matchParentSize().border(3.dp, Color.White, shape))
        Txt(text, Type.Button.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (primary) Palette.OnAccent else if (focused) Color.Black else Color.White), maxLines = 1)
    }
}

/** Dim glass behind the film page's tiles and secondary buttons: the fanart shows through, darkened. */
private val HeroGlass = Color.White.copy(alpha = 0.10f)

/** Action tile on the film page: 80×56, 10dp corners, dim glass, 24dp glyph; white when focused. */
@Composable
private fun HeroTile(icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "tile")
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.scale(scale).shadow(if (focused) 10.dp else 0.dp, shape).size(80.dp, 56.dp).clip(shape)
            .background(if (focused) Color.White else HeroGlass)
            .clickable(source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, when { focused -> Color.Black; active -> Palette.Accent; else -> Color.White }, Modifier.size(24.dp))
    }
}

@Composable
fun MovieDetailScreen(nav: Nav, id: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lib = App.library
    val data by lib.data.collectAsState()
    val progress by lib.progress.collectAsState()
    val user by lib.user.collectAsState()
    val movie = remember(data) { lib.findMovie(id) } ?: return
    val p = progress[id]
    val collection = remember(data) { lib.collections().firstOrNull { it.collectionId == movie.collectionId && movie.collectionId != 0 } }
    val item = Item.MovieItem(movie)
    val trailer = rememberTrailer(movie.tmdbId, tv = false)
    var rating by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val stars = user.ratings[id] ?: 0
    val resume = p?.inProgress == true
    val versions = remember(data) { lib.versions(movie) }
    var version by remember(id) { mutableStateOf(movie) }
    LaunchedEffect(version.id) { lib.wake(version.file) }
    var adding by remember { mutableStateOf<Int?>(null) }      // R.string.add_to_playlist / add_to_collection
    var plot by remember { mutableStateOf(false) }
    val plotFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    Box(Modifier.fillMaxSize()) {
        ScreenBackground()
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        MinimalScroll { Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            MovieHero(
                movie, version, item.backdrop, (item.logo as? String) ?: movie.logo, p, trailer,
                onPlay = { play(nav, Item.MovieItem(version)) }, onTrailer = { trailer?.let { openTrailer(ctx, it) } },
                // Back in the top section: show all of it again (logo, fanart, scores), not only the button.
                onFocusIn = { scope.launch { scroll.animateScrollTo(0) } },
                onPlot = { plot = true }, plotFocus = plotFocus,
            ) {
                ActionIcons(
                    watched = p?.watched == true, onWatched = { lib.setWatched(listOf(id), p?.watched != true) },
                    listed = id in user.watchlist, onList = { lib.toggleWatchlist(id) },
                    stars = stars, onRate = { rating = true }, onCollect = { adding = R.string.add_to_collection }, onMore = { more = true },
                    hero = true,
                )
            }
            Column(Modifier.padding(start = 92.dp, end = 60.dp)) {
                val speed = lib.settings.measuredMbps
                if (speed > 0 && movie.mbps > speed * 0.8) { Gap(8.dp); Txt(stringResource(R.string.too_fast_warning, movie.mbps.toInt(), speed.toInt()), Type.Small.copy(color = Palette.Accent)) }
                // Smart group: pick which copy plays.
                if (versions.size > 1) {
                    Gap(14.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(stringResource(R.string.version), Type.Meta)
                        versions.forEach { v ->
                            val label = listOf(v.file.edition, if (v.file.disc.isNotEmpty()) discLabel(v.file.disc) else techLine(v.file).firstOrNull().orEmpty())
                                .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { v.file.name.take(24) }
                            PillButton(label, primary = v.id == version.id) { version = v }
                        }
                    }
                }
            }
            Gap(20.dp)
            if (movie.file.extras.isNotEmpty()) Shelf(stringResource(R.string.extras)) {
                items(movie.file.extras, key = { it.path }) { x ->
                    Column(Modifier.width(300.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        FocusCard(Modifier.fillMaxWidth().height(169.dp), onClick = { PlayerActivity.start(App.instance, "${movie.file.source}|${movie.file.share}|${x.path}") }) {
                            Poster(item.backdrop, x.title, Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) { Txt(x.title, Type.Meta.copy(color = Color.White), Modifier.padding(10.dp), maxLines = 2, align = TextAlign.Center) }
                        }
                        Gap(10.dp)
                        Txt(x.kind.replaceFirstChar { it.uppercase() }, Type.Small, maxLines = 1)
                    }
                }
            }
            CastRow(nav, movie.cast, director = movie.director)
            if (collection != null) {
                Shelf(collection.name) {
                    items(collection.movies, key = { it.id }) { m ->
                        PosterCard(Item.MovieItem(m), onFocus = {}, onClick = { if (m.id != id) nav.push(Screen.MovieDetail(m.id)) })
                    }
                }
            }
            Txt(
                listOfNotNull(movie.director.ifBlank { null }?.let { stringResource(R.string.directed_by, it) }, movie.file.path, bytes(movie.file.size)).joinToString("  ·  "),
                Type.Small, Modifier.padding(start = 80.dp, top = 28.dp, bottom = 50.dp, end = 80.dp), maxLines = 2,
            )
        } }
        if (plot) FullPlot(movie.title, movie.overview, item.backdrop) { plot = false; scope.launch { kotlinx.coroutines.delay(50); runCatching { plotFocus.requestFocus() } } }
        if (rating) RatingSheet(id, movie.title) { rating = false }
        if (more) OptionSheet(movie.title, buildList {
            if (resume) add(stringResource(R.string.start_over) to { play(nav, item, fromStart = true) })
            add(stringResource(R.string.add_to_playlist) to { adding = R.string.add_to_playlist })
            add(stringResource(R.string.add_to_collection) to { adding = R.string.add_to_collection })
            add(stringResource(R.string.change_poster) to { nav.push(Screen.Artwork(id, tv = false, backdrop = false)) })
            add(stringResource(R.string.change_backdrop) to { nav.push(Screen.Artwork(id, tv = false, backdrop = true)) })
            add(stringResource(R.string.edit_metadata) to { nav.push(Screen.Match(id, tv = false)) })
            if (collection != null) add(collection.name to { nav.push(Screen.Collection(collection.collectionId)) })
        }) { more = false }
        adding?.let { what -> AddToListSheet(what, id, movie.title) { adding = null } }
    }
}

@Composable
fun ShowDetailScreen(nav: Nav, key: String, openSeason: Int? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lib = App.library
    val data by lib.data.collectAsState()
    val progress by lib.progress.collectAsState()
    val user by lib.user.collectAsState()
    val show = remember(data) { lib.findShow(key) } ?: return
    val episodes = remember(show) { show.sortedEpisodes() }
    // The episode to offer: one in progress, else the first unwatched after the last watched one.
    val next = remember(episodes, progress) {
        episodes.firstOrNull { progress[it.id]?.inProgress == true }
            ?: episodes.indexOfLast { progress[it.id]?.watched == true }.let { i -> episodes.drop(i + 1).firstOrNull() }
            ?: episodes.first()
    }
    var season by rememberSaveable(key) { mutableStateOf(openSeason?.takeIf { it in show.seasons } ?: next.season) }
    var focused by remember { mutableStateOf<Episode?>(null) }
    val allWatched = episodes.all { progress[it.id]?.watched == true }
    val screen = Screen.ShowDetail(key, openSeason)
    val trailer = rememberTrailer(show.tmdbId, tv = true)
    var rating by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<String?>(null) }   // series key or "season:key#n" being added to a collection
    val stars = user.ratings[key] ?: 0
    val np = progress[next.id]
    val shown = focused?.takeIf { it.season == season } ?: next
    val spoiler = lib.settings.hideSpoilers && progress[shown.id]?.watched != true

    Box(Modifier.fillMaxSize()) {
        ScreenBackground()
        // A background chosen for the series stays put; otherwise the episode in view shows its own still.
        Backdrop(if (spoiler || show.chosenBackdrop != null) Item.ShowItem(show).backdrop else shown.still ?: Item.ShowItem(show).backdrop)
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        MinimalScroll { Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            // Back in the top section: show all of it again (logo, title, plot), not only the buttons. Started a
            // moment after focus lands, or the scroll that brings the focused button into view cancels it.
            Column(Modifier.padding(start = 80.dp, top = 40.dp, end = 80.dp).onFocusChanged {
                if (it.hasFocus) scope.launch { kotlinx.coroutines.delay(120); scroll.animateScrollTo(0) }
            }) {
                DetailHeader(
                    show.title, show.logo, episodeLine(shown),
                    listOf(
                        shown.runtime.takeIf { it > 0 }?.let(::minutes).orEmpty(),
                        if (shown.airDate.isNotBlank()) date(shown.airDate) else show.year,
                    ) + techLine(shown.file),
                    show.certification, show.genres, show.rating, stars,
                    if (spoiler) "" else shown.overview.ifBlank { show.overview },
                    tmdbId = show.tmdbId, tv = true,
                )
                Gap(16.dp)
                ActionIcons(
                    watched = allWatched, onWatched = { lib.setWatched(episodes.map { it.id }, !allWatched) },
                    listed = key in user.watchlist, onList = { lib.toggleWatchlist(key) },
                    stars = stars, onRate = { rating = true }, onCollect = { adding = key }, onMore = { more = true },
                )
                Gap(16.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    val resume = np?.inProgress == true
                    PillButton(
                        if (resume) stringResource(R.string.resume_long, longClock(np!!.position)) else stringResource(R.string.play_episode, "S${next.season} • E${next.number}"),
                        (if (nav.focused(screen) == null) FocusFirst() else Modifier).width(280.dp), primary = true, fill = if (resume) np!!.fraction else 0f,
                    ) { play(nav, Item.EpisodeItem(show, next)) }
                    if (trailer != null) PillButton(stringResource(R.string.trailer), Modifier.width(260.dp)) { openTrailer(ctx, trailer) }
                }
            }
            Gap(26.dp)
            // Season pills
            LazyRow(contentPadding = PaddingValues(horizontal = 80.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(show.seasons) { n ->
                    val name = if (n == 0) stringResource(R.string.specials) else stringResource(R.string.season_n, n)
                    // Hold OK on a season to put it in a collection.
                    PillButton(name, primary = n == season, onLongClick = { adding = "season:$key#$n" }) { season = n }
                }
            }
            val inSeason = episodes.filter { it.season == season }
            LazyRow(contentPadding = PaddingValues(horizontal = 80.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                items(inSeason, key = { it.id }) { e ->
                    val item = Item.EpisodeItem(show, e)
                    val hide = lib.settings.hideSpoilers && progress[e.id]?.watched != true
                    val card = if (hide) item.copy(episode = e.copy(still = null)) else item
                    WideCard(
                        card, width = 300.dp, wanted = if (lib.settings.autoSelectNext) next.id else nav.focused(screen),
                        label = "E${e.number}" + if (e.title.isNotBlank()) " - ${e.title}" else "",
                        onLongClick = { lib.setWatched(listOf(e.id), progress[e.id]?.watched != true) },
                        onFocus = { focused = e; nav.remember(screen, e.id) },
                    ) { play(nav, item) }
                }
            }
            Txt(stringResource(R.string.hold_to_mark), Type.Small, Modifier.padding(start = 80.dp))
            CastRow(nav, show.cast)
            Gap(50.dp)
        } }
        if (rating) RatingSheet(key, show.title) { rating = false }
        if (more) OptionSheet(show.title, listOf(
            stringResource(R.string.add_to_collection) to { adding = key },
            stringResource(R.string.add_season_to_collection, if (season == 0) stringResource(R.string.specials) else stringResource(R.string.season_n, season)) to { adding = "season:$key#$season" },
            stringResource(R.string.change_poster) to { nav.push(Screen.Artwork(key, tv = true, backdrop = false)) },
            stringResource(R.string.change_backdrop) to { nav.push(Screen.Artwork(key, tv = true, backdrop = true)) },
            stringResource(R.string.edit_metadata) to { nav.push(Screen.Match(key, tv = true)) },
            stringResource(if (allWatched) R.string.mark_all_unwatched else R.string.mark_all_watched) to { lib.setWatched(episodes.map { it.id }, !allWatched) },
        )) { more = false }
        adding?.let { what -> AddToListSheet(R.string.add_to_collection, what, show.title) { adding = null } }
    }
}

/** "Fix match": search TMDB by hand and pin the result. */
@Composable
fun MatchScreen(nav: Nav, screen: Screen.Match) {
    val lib = App.library
    val movie = if (!screen.tv) lib.findMovie(screen.id) else null
    val show = if (screen.tv) lib.findShow(screen.id) else null
    var query by rememberSaveable { mutableStateOf(movie?.title ?: show?.title ?: "") }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun search() {
        searching = true
        scope.launch {
            results = withContext(Dispatchers.IO) { runCatching { lib.tmdb.search(query, screen.tv) }.getOrDefault(emptyList()) }
            searching = false
        }
    }
    LaunchedEffect(Unit) { if (lib.tmdb.enabled) search() }
    fun pick(id: Int) {
        movie?.let { lib.rematchMovie(it, id) }
        show?.let { lib.rematchShow(it, id) }
        nav.pop()
    }
    Column(Modifier.fillMaxSize().screenBackground()) {
        Header(stringResource(R.string.fix_match), (movie?.file?.name ?: show?.episodes?.firstOrNull()?.file?.path))
        Row(Modifier.padding(horizontal = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInput(query, { query = it }, stringResource(R.string.search_hint), Modifier.width(520.dp), onDone = ::search)
            Box(Modifier.width(14.dp))
            PillButton(stringResource(R.string.search), primary = true, onClick = ::search)
        }
        Gap(16.dp)
        if (searching) Txt(stringResource(R.string.searching), Type.Body, Modifier.padding(start = 56.dp))
        ListColumn {
            items(results, key = { it.id }) { h ->
                ListRow(listOf(h.title, h.year).filter { it.isNotBlank() }.joinToString("  ·  "), sub = h.overview.take(160), modifier = if (h == results.first()) FocusFirst() else Modifier) { pick(h.id) }
            }
            item { ListRow(stringResource(R.string.no_match), sub = stringResource(R.string.no_match_sub)) { pick(-1) } }
        }
    }
}
