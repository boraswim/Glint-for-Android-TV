package com.glint.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Settings : Screen
    data object Genres : Screen
    data class Grid(val kind: Kind) : Screen
    data class SettingsSection(val section: Int) : Screen
    data class Folder(val sourceId: String? = null, val share: String? = null, val path: String = "") : Screen
    data class MovieDetail(val id: String) : Screen
    data class ShowDetail(val key: String, val season: Int? = null) : Screen
    data class Collection(val id: Int) : Screen
    data class EditSource(val id: String?) : Screen
    data class Match(val id: String, val tv: Boolean) : Screen
}

/** What a screen needs to move around: push, pop, and remember which card was focused. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    /** Set when playing something half-watched and the settings say to ask. */
    var resumeAsk by mutableStateOf<Item?>(null)
    private val focus = mutableStateMapOf<Screen, String>()
    /** Locked screens opened with the PIN; they stay open while they're on the stack. */
    val unlocked = mutableStateListOf<Screen>()
    val current get() = stack.last()

    fun push(s: Screen) { stack += s }
    fun pop() { if (stack.size > 1) unlocked.remove(stack.removeAt(stack.lastIndex)) }
    fun tab(s: Screen) { stack.clear(); unlocked.clear(); stack += s }
    fun remember(screen: Screen, id: String) { focus[screen] = id }
    fun focused(screen: Screen) = focus[screen]

    /** A grid's filter choice, kept while its films are opened and closed (the grid leaves composition meanwhile). */
    val filters = mutableStateMapOf<Screen, Int>()

    fun open(item: Item) = when (item) {
        is Item.MovieItem -> push(Screen.MovieDetail(item.movie.id))
        is Item.ShowItem -> push(Screen.ShowDetail(item.show.key, item.season))
        is Item.EpisodeItem -> push(Screen.ShowDetail(item.show.key))
        is Item.CollectionItem -> push(Screen.Collection(item.collectionId))
    }
}

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lib = App.library
        if (lib.sources.value.isNotEmpty() && System.currentTimeMillis() - lib.data.value.scannedAt > 6 * 3600_000L) lib.scanAll(auto = true)
        setContent {
            val nav = remember {
                Nav().apply {
                    // Rebuilt for a language just picked: back to the Language page, not Home.
                    if (AppLanguage.reopenOnLanguagePage) {
                        AppLanguage.reopenOnLanguagePage = false
                        push(Screen.Settings); push(Screen.SettingsSection(LANGUAGE_SECTION))
                    }
                }
            }
            var picking by remember { mutableStateOf(lib.profiles.value.size > 1) }
            val sources by lib.sources.collectAsState()
            BackHandler {
                if (nav.stack.size > 1) nav.pop() else finish()
            }
            TvScale { Box(Modifier.fillMaxSize().background(Palette.Bottom)) {
                if (picking) ProfilePicker { picking = false }
                else if (sources.isEmpty() && nav.current !is Screen.EditSource) Welcome { nav.push(Screen.EditSource(null)) }
                else Crossfade(nav.current, label = "screen") { screen ->
                    when (screen) {
                        Screen.Home -> HomeScreen(nav)
                        Screen.Search -> PinGate(nav, screen) { SearchScreen(nav) }
                        Screen.Settings -> PinGate(nav, screen) { SettingsScreen(nav) }
                        Screen.Genres -> GenresScreen(nav)
                        is Screen.Grid -> if (screen.kind == Kind.Playlists) PlaylistsScreen(nav) else GridScreen(nav, screen.kind)
                        is Screen.SettingsSection -> SettingsSectionScreen(nav, screen.section)
                        is Screen.Folder -> FolderScreen(nav, screen)
                        is Screen.MovieDetail -> MovieDetailScreen(nav, screen.id)
                        is Screen.ShowDetail -> ShowDetailScreen(nav, screen.key, screen.season)
                        is Screen.Collection -> CollectionScreen(nav, screen.id)
                        is Screen.EditSource -> EditSourceScreen(nav, screen.id)
                        is Screen.Match -> MatchScreen(nav, screen)
                    }
                }
                ResumePrompt(nav)
            } }
        }
    }

    override fun onStop() {
        super.onStop()
        App.library.flush()
    }
}

@Composable
fun Welcome(onAdd: () -> Unit) {
    val r = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { r.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(80.dp), verticalArrangement = Arrangement.Center) {
        Txt(stringResource(R.string.app_name), Type.Hero.copy(fontSize = 56.sp, color = Palette.Accent))
        Gap(12.dp)
        Txt(stringResource(R.string.welcome_body), Type.Body.copy(fontSize = 18.sp), Modifier.widthIn(max = 640.dp))
        Gap(32.dp)
        PillButton(stringResource(R.string.add_source), Modifier.focusRequester(r), primary = true, onClick = onAdd)
    }
}

/**
 * A text field that behaves on a remote: moving onto it only highlights it; OK opens the
 * keyboard, and Done (or moving away) closes it again.
 */
@Composable
fun TextInput(
    value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
    password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, onDone: () -> Unit = {},
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    var editing by remember { mutableStateOf(false) }
    var wasEditing by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val box = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Up/Down while typing (the keyboard closed with Back leaves the field in typing mode): stop typing and go on.
    var moveAfter by remember { mutableStateOf<FocusDirection?>(null) }
    LaunchedEffect(editing) {
        if (editing) {
            wasEditing = true
            runCatching { field.requestFocus() }
            ime?.show()
        } else if (wasEditing) {
            runCatching { box.requestFocus() }
            moveAfter?.let { focusManager.moveFocus(it); moveAfter = null }
        }
    }
    Box(
        modifier.focusRequester(box).clip(RoundedCornerShape(10.dp))
            .background(if (focused || editing) Palette.SurfaceHigh else Palette.Surface)
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(source, indication = null) { editing = true }
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        if (!editing) {
            val shown = if (password) "•".repeat(value.length) else value
            Txt(shown.ifEmpty { hint }, Type.Button.copy(color = if (shown.isEmpty()) Palette.Faint else Palette.Text, fontWeight = FontWeight.Normal), maxLines = 1)
        } else {
            BasicTextField(
                value, onChange,
                // Close only after the field really had focus: Compose reports "unfocused" once as it appears.
                Modifier.fillMaxWidth().focusRequester(field).onFocusChanged {
                    if (it.isFocused) fieldFocused = true
                    else if (fieldFocused && editing) { fieldFocused = false; editing = false }
                }.onPreviewKeyEvent { e ->
                    val dir = when (e.key) { Key.DirectionDown -> FocusDirection.Down; Key.DirectionUp -> FocusDirection.Up; else -> null }
                    if (dir == null || e.type != KeyEventType.KeyDown) false
                    else { ime?.hide(); moveAfter = dir; editing = false; true }
                },
                textStyle = Type.Button.copy(color = Palette.Text),
                singleLine = true,
                cursorBrush = SolidColor(Palette.Accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { ime?.hide(); editing = false; onDone() }),
            )
        }
    }
}

fun play(nav: Nav, item: Item, fromStart: Boolean = false, decided: Boolean = false) {
    val ctx = App.instance
    val key = when (item) {
        is Item.MovieItem -> item.movie.id
        is Item.EpisodeItem -> item.episode.id
        else -> return nav.open(item)
    }
    val lib = App.library
    val half = lib.progressOf(key)?.inProgress == true
    when {
        decided || fromStart || !half -> PlayerActivity.start(ctx, key, fromStart)
        lib.settings.resumeMode == 0 -> nav.resumeAsk = item          // ask
        lib.settings.resumeMode == 2 -> PlayerActivity.start(ctx, key, fromStart = true)
        else -> PlayerActivity.start(ctx, key, fromStart = false)
    }
}

/** "Resume 00:42:10" or "Start over", for the resume setting "Ask". */
@Composable
fun ResumePrompt(nav: Nav) {
    val item = nav.resumeAsk ?: return
    val key = (item as? Item.MovieItem)?.movie?.id ?: (item as? Item.EpisodeItem)?.episode?.id ?: return
    val pos = App.library.progressOf(key)?.position ?: 0
    OptionSheet(item.title, listOf(
        stringResource(R.string.resume_long, longClock(pos)) to { play(nav, item, decided = true) },
        stringResource(R.string.start_over) to { play(nav, item, fromStart = true) },
    )) { nav.resumeAsk = null }
}

@Composable
fun Header(title: String, sub: String? = null) {
    Column(Modifier.padding(start = 56.dp, top = 40.dp, bottom = 20.dp)) {
        Txt(title, Type.Title)
        if (sub != null) { Gap(4.dp); Txt(sub, Type.Body) }
    }
}

@Composable
fun ListColumn(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 56.dp),
        contentPadding = PaddingValues(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun FocusFirst(): Modifier {
    val r = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { r.requestFocus() } }
    return Modifier.focusRequester(r)
}
