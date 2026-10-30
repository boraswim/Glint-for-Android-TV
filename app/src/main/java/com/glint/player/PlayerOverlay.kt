package com.glint.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.border
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Tabs of the playback menu that drops down from the top. */
val PANEL_TABS = listOf(R.string.info, R.string.menu_video, R.string.audio, R.string.subtitles)

@Composable
fun PlayerOverlay(p: PlayerActivity) {
    val n = p.now
    Box(Modifier.fillMaxSize()) {
        // A restyle reopening the film: the picture stays covered until it's back where it was.
        if (p.reopenAt != null) Box(Modifier.fillMaxSize().background(Color.Black))
        // Loading / buffering
        if (p.error == null && (p.reopenAt != null || !p.playing && p.buffering < 100f) && p.upNext == null) {
            Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color(0xAA000000)).padding(horizontal = 28.dp, vertical = 16.dp)) {
                Txt(if (p.buffering > 0f && p.reopenAt == null) stringResource(R.string.buffering, p.buffering.toInt()) else stringResource(R.string.loading), Type.Heading)
            }
        }

        AnimatedVisibility(p.controls && p.error == null && !p.panel, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                // Top: a hint that the menu is one press away, and the time of day.
                Column(Modifier.align(Alignment.TopCenter).padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt(stringResource(R.string.down_for_info), Type.Meta.copy(color = Color.White))
                    Icon(Icons.Filled.KeyboardArrowDown, Color.White, Modifier.size(26.dp))
                }
                Box(Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 60.dp)) { Clock() }
                if (!p.playing && p.seekTarget == null && p.buffering >= 100f) {
                    Box(Modifier.align(Alignment.Center).size(96.dp).clip(CircleShape).background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Pause, Color.White, Modifier.size(56.dp))
                    }
                }
                // Bottom: big title on the left, track icons on the right, then the timeline.
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp).background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xCC000000))))
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 60.dp, vertical = 30.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            // Only episodes get a line above the title ("S1 · E2 - Name"); films show their title alone.
                            if (!n?.subtitle.isNullOrBlank() && App.library.findEpisode(n!!.key) != null) Txt(n.subtitle, Type.Meta.copy(color = Color(0xFFDDDDDD)), maxLines = 1)
                            Txt(n?.title.orEmpty(), Type.Hero.copy(fontSize = 34.sp), maxLines = 1)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            p.trackButtons().forEachIndexed { i, tab ->
                                val icon = when (tab) { 1 -> Icons.Filled.ViewList; 4 -> Icons.AutoMirrored.Filled.PlaylistPlay; 3 -> Icons.Filled.Subtitles; else -> Icons.AutoMirrored.Filled.VolumeUp }
                                // The button's own size (its focused size), so the pop-up stands above it rather than growing the row.
                                Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                                    TrackIcon(icon, focused = p.trackFocus == i)
                                    // Search results (5) open from the subtitle button, above it.
                                    if ((p.trackPopup == tab || (tab == 3 && p.trackPopup == 5)) && p.trackFocus == i) TrackPopup(p, Modifier.align(Alignment.BottomEnd))
                                }
                            }
                        }
                    }
                    Gap(14.dp)
                    SeekPreview(p)
                    Timeline(p)
                    Gap(8.dp)
                    // The elapsed time rides under the playhead, the time left sits at the right end.
                    Box(Modifier.fillMaxWidth()) {
                        val shown = p.seekTarget ?: p.position
                        val at = if (p.duration > 0) (shown.toFloat() / p.duration).coerceIn(0f, 1f) else 0f
                        Txt(clock(shown), Type.Meta.copy(color = Color.White), Modifier.align(BiasAlignment(at.coerceAtMost(0.85f) * 2 - 1, 0f)))
                        if (p.duration > 0) Txt("-" + clock(p.duration - shown), Type.Meta.copy(color = Color.White), Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }

        AnimatedVisibility(
            p.panel, Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
        ) { PlaybackMenu(p) }

        if (p.hud && !p.panel) PlaybackHud(p, Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 30.dp))

        p.upNext?.let { (show, ep) -> UpNextCard(p, show, ep, Modifier.align(Alignment.BottomEnd)) }

        // Skip intro / credits: OK on the remote presses it while the controls are hidden.
        p.skipKind?.let { kind ->
            if (!p.controls && !p.panel && p.upNext == null) Box(Modifier.align(Alignment.BottomEnd).padding(end = 60.dp, bottom = 60.dp)) {
                Box(Modifier.clip(RoundedCornerShape(30.dp)).background(Color.White).padding(horizontal = 30.dp, vertical = 14.dp)) {
                    Txt(stringResource(kind), Type.Button.copy(color = Color.Black))
                }
            }
        }

        p.error?.let { msg ->
            Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color(0xF2303236)).padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(msg, Type.Heading)
                Gap(6.dp)
                Txt(n?.file?.path ?: n?.title.orEmpty(), Type.Small, maxLines = 2)
                Gap(20.dp)
                PillButton(stringResource(R.string.back), FocusFirst(), primary = true) { p.finish() }
            }
        }
    }
}

@Composable
private fun TrackIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, focused: Boolean) {
    // Focused: white with a dark glyph and a little larger, like the rest of the app's buttons.
    val size = if (focused) 54.dp else 46.dp
    Box(Modifier.size(size).clip(CircleShape).background(if (focused) Color.White else Color(0x55FFFFFF)), contentAlignment = Alignment.Center) {
        Icon(icon, if (focused) Color.Black else Color.White, Modifier.size(if (focused) 28.dp else 24.dp))
    }
}

/**
 * The list over the titles, subtitle and audio buttons: a dark glass card standing on the button's right
 * edge, a tick by the track playing, the row under the remote in white. Long lists scroll with the focus.
 */
@Composable
private fun TrackPopup(p: PlayerActivity, modifier: Modifier) {
    val rows = p.popupRows()
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(p.popupFocus) {
        val seen = list.layoutInfo.visibleItemsInfo
        if (seen.isNotEmpty() && (p.popupFocus <= seen.first().index || p.popupFocus >= seen.last().index))
            list.animateScrollToItem((p.popupFocus - (seen.size - 2).coerceAtLeast(0)).coerceAtLeast(0).coerceAtMost(p.popupFocus))
    }
    Box(
        modifier.wrapContentSize(Alignment.BottomEnd, unbounded = true).offset(x = 14.dp, y = -68.dp)
            .shadow(24.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Color(0xF02A2B2E)),
    ) {
        // Subtitle search results carry release names and frame rates: a wider card.
        androidx.compose.foundation.lazy.LazyColumn(Modifier.width(if (p.trackPopup == 5) 720.dp else 360.dp).heightIn(max = 420.dp).padding(8.dp), state = list) {
            items(rows.size) { i ->
                val (label, on) = rows[i]
                val focused = i == p.popupFocus
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (focused) Color.White else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(30.dp)) { if (on) Txt("✓", Type.Body.copy(fontSize = 19.sp, color = if (focused) Color.Black else Color.White)) }
                    Txt(label, Type.Body.copy(fontSize = 17.sp, lineHeight = 22.sp, color = if (focused) Color.Black else Color.White), maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = System.currentTimeMillis() } }
    Txt(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), Type.Heading.copy(color = Color(0xFFDDDDDD)))
}

/**
 * While scrubbing, the picture at the new time sits on the bar right above the playhead
 * (a 16:9 frame with a thin light rim). Takes no room otherwise.
 */
@Composable
private fun SeekPreview(p: PlayerActivity) {
    val t = p.seekTarget ?: return
    val thumbs = p.thumbs ?: return
    val frame by thumbs.frame.collectAsState()
    val unavailable by thumbs.unavailable.collectAsState()
    if (unavailable) return
    BoxWithConstraints(Modifier.fillMaxWidth().height(0.dp)) {
        val w = 266.dp; val h = 150.dp
        val at = if (p.duration > 0) t.toFloat() / p.duration else 0f
        val x = (maxWidth * at - w / 2).coerceIn(0.dp, maxWidth - w)
        Box(
            Modifier.offset(x = x, y = -h - 6.dp).requiredSize(w, h).clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF111214)).border(2.dp, Color(0xCCFFFFFF), RoundedCornerShape(10.dp)),
        ) {
            frame?.let { (_, bmp) ->
                androidx.compose.foundation.Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
        }
    }
}

@Composable
private fun Timeline(p: PlayerActivity) {
    val d = p.duration.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x55FFFFFF)))
        Box(Modifier.fillMaxWidth(p.position.toFloat() / d).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
        for ((_, at) in p.chapters.drop(1)) {
            Box(Modifier.offset(x = w * (at.toFloat() / d)).width(2.dp).height(10.dp).background(Color(0xAA000000)))
        }
        p.seekTarget?.let { t ->
            Box(Modifier.offset(x = w * (t.toFloat() / d) - 8.dp).size(16.dp).clip(CircleShape).background(Palette.Accent))
        }
    }
}

/**
 * The playback menu, dropped from the top: four tabs over the picture (Info, Video, Audio,
 * Subtitles) and a grey glass card below them. Audio and Subtitles list the tracks on the left and
 * the options on the right; OK on an option swaps that column for its choices, Back returns.
 */
@Composable
private fun PlaybackMenu(p: PlayerActivity) {
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))) {
        Column(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 30.dp, start = 60.dp, end = 60.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PANEL_TABS.forEachIndexed { i, label ->
                    MenuTab(stringResource(label), selected = p.panelTab == i, modifier = if (i == p.panelTab) FocusFirst().focusRequester(p.menuTabFocus) else Modifier) { if (!p.holdTab) p.panelTab = i }
                }
            }
            Gap(14.dp)
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xE65E6164))
                    .padding(horizontal = 40.dp, vertical = 30.dp)
            ) {
                when (p.panelTab) {
                    0 -> InfoTab(p)
                    1 -> VideoTab(p)
                    2 -> AudioTab(p)
                    else -> SubtitlesTab(p)
                }
            }
        }
    }
}

@Composable
private fun MenuTab(label: String, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.padding(horizontal = 6.dp)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onSelect() }
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color.White else if (selected) Color(0x40FFFFFF) else Color.Transparent)
            .clickable(source, indication = null, onClick = onSelect)
            .padding(horizontal = 22.dp, vertical = 9.dp)
    ) {
        Txt(label, Type.Button.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (focused) Color(0xFF6E7174) else Color.White))
    }
}

/** Poster, title, plot and the facts line: "2 sa. 29 dk.  2024  HEVC (4K HDR)  TrueHD 7.1  Action, Adventure  16+". */
@Composable
private fun InfoTab(p: PlayerActivity) {
    val n = p.now
    val lib = App.library
    val movie = n?.let { lib.findMovie(it.key) }
    val ep = n?.let { lib.findEpisode(it.key) }
    val art = movie?.let { Item.MovieItem(it).poster } ?: ep?.let { (s, _) -> Item.ShowItem(s).poster }
    Row {
        if (art != null) {
            AsyncImage(art, null, Modifier.size(140.dp, 207.dp).clip(RoundedCornerShape(10.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Spacer(Modifier.width(28.dp))
        }
        Column(Modifier.weight(1f)) {
            Txt(n?.title.orEmpty(), Type.Title.copy(fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1)
            if (ep != null && !n?.subtitle.isNullOrBlank()) { Gap(2.dp); Txt(n!!.subtitle, Type.Meta.copy(color = Color.White), maxLines = 1) }
            if (!n?.overview.isNullOrBlank()) { Gap(8.dp); Txt(n!!.overview, Type.Body.copy(fontSize = 18.sp, lineHeight = 25.sp, color = Color.White), maxLines = 5) }
            Gap(12.dp)
            val file = n?.file
            val b = file?.let { badges(it) }.orEmpty()
            val name = file?.name.orEmpty().lowercase(java.util.Locale.ROOT)
            val codec = when { "hevc" in name || "x265" in name || "h265" in name || "h.265" in name -> "HEVC"; "avc" in name || "x264" in name || "h264" in name || "h.264" in name -> "H.264"; "av1" in name -> "AV1"; else -> "" }
            val pic = listOfNotNull(b.firstOrNull { it in setOf("4K", "1080p", "720p") }, b.firstOrNull { it in setOf("Dolby Vision", "HDR10+", "HDR") }).joinToString(" ")
            val audio = b.filter { it in setOf("TrueHD", "DTS-HD MA", "DTS", "Dolby Digital+", "Atmos", "DTS:X") }.joinToString(" ")
            val runtime = movie?.runtime ?: ep?.second?.runtime ?: 0
            val facts = listOfNotNull(
                runtime.takeIf { it > 0 }?.let { if (it >= 60) stringResource(R.string.duration_hm, it / 60, it % 60) else stringResource(R.string.duration_m, it) },
                (movie?.year ?: ep?.first?.year)?.ifBlank { null },
                listOf(codec, pic.ifBlank { null }?.let { "($it)" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null },
                audio.ifBlank { null },
                (movie?.genres ?: ep?.first?.genres)?.take(2)?.joinToString(", ")?.ifBlank { null },
                (movie?.certification ?: ep?.first?.certification)?.let(::certLabel)?.ifBlank { null },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                facts.forEach { Txt(it, Type.Heading.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White), maxLines = 1) }
            }
            // Episodes without intro chapters: mark the intro once, every episode of the series can skip it.
            if (ep != null) {
                Gap(14.dp)
                val m = p.markedIntro
                val state = when {
                    p.introStartMark != null -> stringResource(R.string.intro_start_marked, clock(p.introStartMark!!))
                    m != null -> stringResource(R.string.intro_marked, clock(m.first), clock(m.second))
                    else -> stringResource(R.string.intro_none)
                }
                ChoiceRow(stringResource(R.string.intro_series) + "  ·  " + state, buildList {
                    add(stringResource(R.string.intro_start_here) to (p.introStartMark != null))
                    add(stringResource(R.string.intro_end_here) to false)
                    if (m != null || p.introStartMark != null) add(stringResource(R.string.intro_clear) to false)
                }) { i ->
                    when (i) {
                        0 -> p.markIntroStart()
                        1 -> p.markIntroEnd()
                        else -> p.clearIntroMarks()
                    }
                }
            }
        }
    }
}

/** Choices as a row of pills; the current one is ticked. */
@Composable
private fun ChoiceRow(title: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Column {
        Txt(title, Type.Meta.copy(color = Color(0xCCFFFFFF)), Modifier.padding(bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)) {
            items(options.size) { i ->
                val (label, on) = options[i]
                PillButton((if (on) "✓  " else "") + label, primary = on) { onPick(i) }
            }
        }
    }
}

/** A menu option: its name, the value shown beside it, and its choices (label, current) with what picking does. */
private class MenuOption(
    val label: String, val value: String, val choices: List<Pair<String, Boolean>>,
    /** Shown in place of the choices when the option is opened; gets a function that closes it. */
    val editor: (@Composable (close: () -> Unit) -> Unit)? = null,
    /** False greys the row out: shown, but focus passes over it and it can't be opened. */
    val enabled: Boolean = true,
    val pick: (Int) -> Unit,
)

/** "SEÇENEKLER": name on the left, value on the right; OK lists the option's choices in its place. */
@Composable
private fun OptionsColumn(p: PlayerActivity, options: List<MenuOption>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf<Int?>(null) }
    var last by remember { mutableStateOf<Int?>(null) }      // the option to come back to when its list closes
    fun close() { p.holdTab = true; last = open; open = null }
    // Back closes the open list before it closes the menu.
    androidx.compose.runtime.DisposableEffect(open) {
        p.menuBack = if (open != null) ({ close(); true }) else null
        onDispose { p.menuBack = null }
    }
    Column(modifier) {
        val o = open?.let { options.getOrNull(it) }
        MenuHeading(if (o != null) o.label.uppercase() else stringResource(R.string.menu_options).uppercase())
        Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState())) {
            // Focus stays on the tabs until Down; an opened list takes it, on its current choice.
            if (o?.editor != null) o.editor.invoke { close() }
            else if (o == null) options.forEachIndexed { i, opt -> MenuRow(opt.label, opt.value, first = i == last && opt.enabled, up = if (i == 0) p.menuTabFocus else null, enabled = opt.enabled, onFocused = { p.holdTab = false }) { p.holdTab = true; open = i } }
            else o.choices.forEachIndexed { i, (label, on) -> MenuRow(label, checked = on, first = on || (i == 0 && o.choices.none { it.second }), up = if (i == 0) p.menuTabFocus else null, onFocused = { p.holdTab = false }) { o.pick(i); close() } }
        }
    }
}

/** "İZLER": the tracks, the playing one ticked. */
@Composable
private fun TracksColumn(rows: List<Pair<String, Boolean>>, modifier: Modifier = Modifier, up: androidx.compose.ui.focus.FocusRequester? = null, onPick: (Int) -> Unit) {
    Column(modifier) {
        MenuHeading(stringResource(R.string.menu_tracks).uppercase())
        Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { i, (label, on) -> MenuRow(label, checked = on, up = if (i == 0) up else null) { onPick(i) } }
        }
    }
}

@Composable
private fun MenuHeading(text: String) =
    Txt(text, Type.Small.copy(color = Color(0xE6FFFFFF), fontSize = 14.sp, fontWeight = FontWeight.Bold), Modifier.padding(start = 38.dp, bottom = 10.dp))

/** One line of a menu column: a tick (or room for one), the text, and an optional value at the right. */
@Composable
private fun MenuRow(text: String, value: String? = null, checked: Boolean = false, first: Boolean = false, up: androidx.compose.ui.focus.FocusRequester? = null, enabled: Boolean = true, onFocused: () -> Unit = {}, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (first) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().focusRequester(focus).then(if (up != null) Modifier.focusProperties { this.up = up } else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clip(RoundedCornerShape(10.dp)).background(if (focused) Color.White else Color.Transparent)
            .clickable(source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (focused) Color(0xFF3A3C3F) else if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
        Box(Modifier.width(28.dp)) { if (checked) Txt("✓", Type.Heading.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink)) }
        Txt(text, Type.Heading.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = ink), Modifier.weight(1f), maxLines = 1)
        if (value != null) Txt(value, Type.Heading.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium, color = ink.copy(alpha = ink.alpha * 0.85f)), Modifier.padding(start = 20.dp), maxLines = 1)
    }
}

/** "+1,25 sn": a delay in seconds, two decimals, in the viewer's number format. */
@Composable
private fun secondsLabel(ms: Long) = stringResource(R.string.delay_seconds, String.format(java.util.Locale.getDefault(), "%+.2f", ms / 1000.0).replace("+0,00", "0,00").replace("+0.00", "0.00"))

/**
 * Fine subtitle timing: the exact delay, steps of 0.1 and 1 second either way, a box to type a value
 * in seconds (e.g. -1,5), and reset. Changes apply at once; up to a minute each way.
 */
@Composable
private fun DelayEditor(p: PlayerActivity) {
    fun setTo(ms: Long) { val t = ms.coerceIn(-60_000, 60_000); if (t != p.subtitleDelay) p.shiftSubtitles(t - p.subtitleDelay) }
    val start = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }
    Column(Modifier.padding(start = 10.dp)) {
        Txt(secondsLabel(p.subtitleDelay), Type.Title.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White), Modifier.padding(start = 28.dp, bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(-1000L to "−1", -100L to "−0,1", 100L to "+0,1", 1000L to "+1").forEachIndexed { i, (step, label) ->
                StepButton(stringResource(R.string.delay_seconds, label), if (i == 2) Modifier.focusRequester(start) else Modifier, up = p.menuTabFocus) { setTo(p.subtitleDelay + step) }
            }
        }
        Gap(14.dp)
        var text by remember { mutableStateOf("") }
        TextInput(
            text, { text = it }, stringResource(R.string.delay_enter_hint), Modifier.width(420.dp),
            keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal,
        ) {
            text.trim().replace(',', '.').toDoubleOrNull()?.let { setTo(Math.round(it * 1000)) }
            text = ""
        }
        Gap(8.dp)
        MenuRow(stringResource(R.string.reset)) { setTo(0) }
    }
}

/** A small button of the delay editor; white when focused, like the menu rows. */
@Composable
private fun StepButton(text: String, modifier: Modifier = Modifier, up: androidx.compose.ui.focus.FocusRequester? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.then(if (up != null) Modifier.focusProperties { this.up = up } else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp)).background(if (focused) Color.White else Color(0x33FFFFFF))
            .clickable(source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) { Txt(text, Type.Heading.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Color(0xFF3A3C3F) else Color.White)) }
}

/** Delay choices in ms, and their labels ("0 ms", "+250 ms"). */
private val DELAYS = listOf(-5000L, -3000L, -2000L, -1000L, -500L, -250L, -100L, 0L, 100L, 250L, 500L, 1000L, 2000L, 3000L, 5000L)
private fun delayLabel(ms: Long) = (if (ms > 0) "+" else "") + "$ms ms"

@Composable
private fun VideoTab(p: PlayerActivity) {
    val zooms = listOf(R.string.zoom_fit, R.string.zoom_crop, R.string.zoom_stretch, R.string.zoom_16_9, R.string.zoom_4_3, R.string.zoom_original).map { stringResource(it) }
    val rates = listOf(1f, 0.75f, 1.25f, 1.5f, 2f)
    val rateLabel = { r: Float -> if (r == 1f) "Normal" else "${r}×" }
    val repeats = listOf(R.string.off, R.string.repeat_one, R.string.repeat_all).map { stringResource(it) }
    val onOff = listOf(stringResource(R.string.off), stringResource(R.string.on))
    val current = p.chapters.indexOfLast { it.second <= p.position }.coerceAtLeast(0)
    val chapterName = { i: Int -> p.chapters[i].first.ifBlank { null } ?: "Chapter ${i + 1}" }
    val options = buildList {
        add(MenuOption(stringResource(R.string.zoom_mode), zooms[p.scale], zooms.mapIndexed { i, z -> z to (i == p.scale) }) { p.chooseScale(it) })
        if (p.chapters.size > 1) add(MenuOption(stringResource(R.string.chapters), chapterName(current), p.chapters.indices.map { chapterName(it) + "   " + clock(p.chapters[it].second) to (it == current) }) { p.chooseChapter(it); p.closePanel() })
        add(MenuOption(stringResource(R.string.playback_speed), rateLabel(p.rate), rates.map { rateLabel(it) to (it == p.rate) }) { p.chooseRate(rates[it]) })
        add(MenuOption(stringResource(R.string.repeat), repeats[p.repeat], repeats.mapIndexed { i, r -> r to (i == p.repeat) }) { p.chooseRepeat(it) })
        add(MenuOption(stringResource(R.string.hud), onOff[if (p.hud) 1 else 0], onOff.mapIndexed { i, o -> o to ((i == 1) == p.hud) }) { p.hud = it == 1 })
    }
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(0.75f))
        OptionsColumn(p, options, Modifier.width(560.dp))
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun AudioTab(p: PlayerActivity) {
    val boosts = listOf(100, 150, 200)
    val boostLabel = { v: Int -> if (v == 100) App.instance.getString(R.string.off) else "%$v" }
    val options = buildList {
        add(MenuOption(stringResource(R.string.volume_boost), boostLabel(p.volume), boosts.map { boostLabel(it) to (it == p.volume) }) { p.chooseVolume(boosts[it]) })
        if (p.delaysSupported) add(MenuOption(stringResource(R.string.menu_delay), delayLabel(p.audioDelay), DELAYS.map { delayLabel(it) to (it == p.audioDelay) }) { p.shiftAudio(DELAYS[it] - p.audioDelay) })
    }
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(0.3f))
        TracksColumn(p.audioTracks.map { it.label to (it.id == p.audioTrack) }, Modifier.width(470.dp), up = p.menuTabFocus) { p.chooseAudio(p.audioTracks[it].id) }
        Spacer(Modifier.width(50.dp))
        OptionsColumn(p, options, Modifier.width(470.dp))
        Spacer(Modifier.weight(0.3f))
    }
}

@Composable
private fun SubtitlesTab(p: PlayerActivity) {
    val s = App.library.settings
    var tick by remember { mutableStateOf(0) }       // settings aren't observable; redraw after a change
    fun change(block: () -> Unit) { block(); tick++; p.restyleSubtitles() }
    if (tick < 0) return
    // Large sits between Medium and what Large was; Extra large is the old Large (160 was too big).
    val sizes = listOf(75 to R.string.size_small, 100 to R.string.size_medium, 115 to R.string.size_large, 130 to R.string.size_xlarge).map { it.first to stringResource(it.second) }
    val colors = listOf(0xFFFFFF to R.string.color_white, 0xFFE135 to R.string.color_yellow).map { it.first to stringResource(it.second) }
    val weights = listOf(false to R.string.weight_normal, true to R.string.weight_bold).map { it.first to stringResource(it.second) }
    val outlines = listOf(0 to R.string.none_choice, 2 to R.string.outline_thin, 4 to R.string.weight_normal, 6 to R.string.outline_thick).map { it.first to stringResource(it.second) }
    val backs = listOf(false to R.string.off, true to R.string.on).map { it.first to stringResource(it.second) }
    @Composable fun <T> option(label: Int, list: List<Pair<T, String>>, now: T, enabled: Boolean = true, set: (T) -> Unit) =
        MenuOption(stringResource(label), list.firstOrNull { it.first == now }?.second ?: list.first().second, list.map { it.second to (it.first == now) }, enabled = enabled) { i -> change { set(list[i].first) } }
    val options = buildList {
        add(option(R.string.subtitle_size, sizes, s.subtitleScale) { s.subtitleScale = it })
        // Picture subtitles (Blu-ray PGS) come drawn: only their size changes, so the lettering options grey out.
        val lettering = p.subtitleTrack == -1 || !p.imageSubtitles()
        add(option(R.string.subtitle_color, colors, s.subtitleColor, lettering) { s.subtitleColor = it })
        add(option(R.string.menu_weight, weights, s.subtitleBold, lettering) { s.subtitleBold = it })
        add(option(R.string.menu_outline, outlines, s.subtitleOutline, lettering) { s.subtitleOutline = it })
        add(option(R.string.menu_background, backs, s.subtitleBackground, lettering) { s.subtitleBackground = it })
        add(MenuOption(stringResource(R.string.menu_delay), secondsLabel(p.subtitleDelay), emptyList(), editor = { _ -> DelayEditor(p) }) {})
    }
    val rows = listOf(stringResource(R.string.none_choice) to (p.subtitleTrack == -1)) + p.subtitleTracks.map { it.label to (it.id == p.subtitleTrack) }
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(0.3f))
        TracksColumn(rows, Modifier.width(470.dp), up = p.menuTabFocus) { i -> p.chooseSubtitle(if (i == 0) -1 else p.subtitleTracks[i - 1].id) }
        Spacer(Modifier.width(50.dp))
        OptionsColumn(p, options, Modifier.width(470.dp))
        Spacer(Modifier.weight(0.3f))
    }
}

/** Playback statistics, top left while the HUD option is on. */
@Composable
fun PlaybackHud(p: PlayerActivity, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xB3000000)).padding(horizontal = 16.dp, vertical = 12.dp)) {
        p.techInfo.forEach { Txt(it, Type.Small.copy(color = Color.White, fontSize = 13.sp), maxLines = 1) }
        Txt("${clock(p.position)} / ${clock(p.duration)}", Type.Small.copy(color = Color(0xCCFFFFFF), fontSize = 13.sp))
    }
}

@Composable
private fun UpNextCard(p: PlayerActivity, show: Show, ep: Episode, modifier: Modifier) {
    Column(
        modifier.padding(48.dp).width(460.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xEE1F2124)).padding(24.dp)
    ) {
        Txt(stringResource(R.string.up_next_in, p.upNextSeconds), Type.Meta.copy(color = Palette.Accent, fontWeight = FontWeight.Bold))
        Gap(8.dp)
        Box(Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(12.dp))) {
            Poster(ep.still ?: show.backdrop, ep.code, Modifier.fillMaxSize())
        }
        Gap(10.dp)
        Txt(show.title, Type.Heading, maxLines = 1)
        Txt(episodeLine(ep), Type.Meta, maxLines = 1)
        Gap(16.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(stringResource(R.string.play_now), FocusFirst(), primary = true) { p.playNextNow() }
            PillButton(stringResource(R.string.cancel)) { p.cancelUpNext() }
        }
    }
}
