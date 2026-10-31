package com.glint.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Someone from a cast: photo, dates, life story, more photos, and what of theirs is in the library. */
@Composable
fun PersonScreen(nav: Nav, screen: Screen.PersonPage) {
    val lib = App.library
    val data by lib.data.collectAsState()
    var info by remember { mutableStateOf<PersonInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(screen) {
        info = if (!lib.tmdb.enabled) null
        else withContext(Dispatchers.IO) { runCatching { lib.tmdb.person(screen.id, screen.name, screen.photo) }.getOrNull() }
        loading = false
    }
    val p = info
    val photos = p?.photos.orEmpty()
    val inLibrary = remember(data, screen.name) {
        data.movies.any { m -> m.cast.any { it.name == screen.name } || m.director == screen.name } ||
            data.shows.any { s -> s.cast.any { it.name == screen.name } }
    }

    Column(Modifier.fillMaxSize().screenBackground().padding(top = 36.dp)) {
        Row(Modifier.padding(horizontal = 64.dp).height(400.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Box(Modifier.size(266.dp, 400.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF3A3D43)), contentAlignment = Alignment.Center) {
                Txt(screen.name.split(' ').mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""), Type.Hero)
                (photos.firstOrNull() ?: screen.photo)?.let { AsyncImage(it, screen.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.weight(1f)) {
                Txt(p?.name ?: screen.name, Type.Hero, maxLines = 1)
                if (p != null) {
                    val alive = p.deathday.isBlank()
                    val age = ageOf(p.birthday, p.deathday)?.let { stringResource(R.string.person_age, it) }
                    val born = listOfNotNull(
                        p.birthday.ifBlank { null }?.let { stringResource(R.string.person_born, date(it)) + if (alive && age != null) " ($age)" else "" },
                        p.place.ifBlank { null },
                    ).joinToString("  •  ")
                    if (born.isNotBlank()) Txt(born, Type.Meta, Modifier.padding(top = 8.dp), maxLines = 1)
                    if (!alive) Txt(stringResource(R.string.person_died, date(p.deathday)) + if (age != null) " ($age)" else "", Type.Meta, Modifier.padding(top = 4.dp), maxLines = 1)
                }
                Box(Modifier.padding(top = 14.dp).weight(1f).fillMaxWidth()) {
                    LifeStory(if (loading) stringResource(R.string.loading) else p?.biography?.ifBlank { null } ?: stringResource(R.string.person_no_story))
                }
                if (inLibrary) PillButton(stringResource(R.string.person_in_library), Modifier.padding(top = 14.dp)) { nav.push(Screen.Grid(Kind.Actor(screen.name))) }
            }
        }
        if (photos.size > 1) {
            Txt(stringResource(R.string.person_photos), Type.Section, Modifier.padding(start = 64.dp, top = 18.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 64.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                itemsIndexed(photos, key = { _, url -> url }) { i, url ->
                    FocusCard(Modifier.size(120.dp, 180.dp), onClick = { viewing = i }) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF3A3D43)))
                        AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
    }
    viewing?.let { start -> PhotoViewer(photos, start) { viewing = null } }
}

/** The text moves with Up and Down while there's more of it; past either end, focus moves on as usual. */
@Composable
private fun LifeStory(text: String) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 150.dp.toPx() }
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.fillMaxSize().then(FocusFirst())
            .onPreviewKeyEvent { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    e.key == Key.DirectionDown && scroll.canScrollForward -> { scope.launch { scroll.animateScrollBy(step) }; true }
                    e.key == Key.DirectionUp && scroll.canScrollBackward -> { scope.launch { scroll.animateScrollBy(-step) }; true }
                    else -> false
                }
            }
            .focusable(interactionSource = source)
            .clip(shape).background(Palette.Sheet.copy(alpha = if (focused) 1f else 0.5f))
            .border(2.dp, if (focused) Palette.Text else Color.Transparent, shape),
    ) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Txt(text, Type.Body.copy(color = Palette.Text, fontSize = 17.sp, lineHeight = 26.sp))
        }
        if (scroll.canScrollForward) Txt("▼", Type.Small, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 8.dp))
    }
}

/** One photo over the whole screen; Left and Right go through them, round from the last to the first. */
@Composable
private fun PhotoViewer(photos: List<String>, start: Int, onClose: () -> Unit) {
    var at by remember { mutableStateOf(start) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        TvScale {
            Box(
                Modifier.fillMaxSize().background(Color.Black).focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        when {
                            e.type != KeyEventType.KeyDown -> false
                            e.key == Key.DirectionRight -> { at = (at + 1) % photos.size; true }
                            e.key == Key.DirectionLeft -> { at = (at - 1 + photos.size) % photos.size; true }
                            else -> false
                        }
                    }
                    .focusable(),
            ) {
                // The small copy is already loaded, so something shows at once while the original arrives.
                AsyncImage(photos[at], null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                AsyncImage(fullScreen(photos[at]), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Txt("${at + 1} / ${photos.size}", Type.Meta.copy(color = Color.White), Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
            }
        }
    }
}

/** Full years from a TMDB birth date to the death date, or to today. */
private fun ageOf(birthday: String, deathday: String): Int? = runCatching {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    val from = Calendar.getInstance().apply { time = format.parse(birthday)!! }
    val to = Calendar.getInstance().apply { if (deathday.isNotBlank()) time = format.parse(deathday)!! }
    to.get(Calendar.YEAR) - from.get(Calendar.YEAR) - if (to.get(Calendar.DAY_OF_YEAR) < from.get(Calendar.DAY_OF_YEAR)) 1 else 0
}.getOrNull()
