package com.glint.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PROFILE_COLORS = listOf(
    listOf(Color(0xFFFF8A1F), Color(0xFFE0457B)), listOf(Color(0xFF1E5BFF), Color(0xFF19C3F5)),
    listOf(Color(0xFF17A63A), Color(0xFF7DDB3E)), listOf(Color(0xFF7B2FF7), Color(0xFFF107A3)),
    listOf(Color(0xFFE6A500), Color(0xFFFFD43B)), listOf(Color(0xFF5C6B80), Color(0xFF8A9BB3)),
)

@Composable
fun Avatar(p: Profile, size: androidx.compose.ui.unit.Dp) {
    // The main profile wears the theme colour (Settings → General); the others the colour picked for them.
    val main = p.id == "main"
    val colors = if (main) listOf(Palette.Accent, androidx.compose.ui.graphics.lerp(Palette.Accent, Color.White, 0.35f))
        else PROFILE_COLORS[p.color % PROFILE_COLORS.size]
    Box(Modifier.size(size)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
            Txt(p.name.take(1).uppercase(), Type.Hero.copy(fontSize = (size.value * 0.42f).sp, color = if (main) Palette.OnAccent else Palette.Text))
        }
        if (p.restricted) Box(Modifier.align(Alignment.BottomEnd).size(size * 0.28f).clip(CircleShape).background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, Color.White, Modifier.size(size * 0.16f))
        }
    }
}

/** "Who's watching?": shown at start when there is more than one profile, and from Settings. */
@Composable
fun ProfilePicker(onPicked: () -> Unit) {
    val lib = App.library
    val profiles by lib.profiles.collectAsState()
    var asking by remember { mutableStateOf<Profile?>(null) }
    Column(Modifier.fillMaxSize().screenBackground(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(0.3f))
        Txt(stringResource(R.string.who_is_watching), Type.Hero)
        Box(Modifier.weight(0.15f))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(40.dp), contentPadding = PaddingValues(horizontal = 80.dp)) {
            items(profiles, key = { it.id }) { p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FocusCard(
                        (if (p.id == lib.profile.value.id) FocusFirst() else Modifier).size(170.dp),
                        shape = RoundedCornerShape(85.dp),
                        onClick = {
                            // Leaving a locked profile for an open one needs the locked one's PIN.
                            val current = lib.profile.value
                            if (current.restricted && current.pin.isNotEmpty() && p.id != current.id && !p.restricted) asking = p
                            else { lib.switchProfile(p.id); onPicked() }
                        },
                    ) { Avatar(p, 170.dp) }
                    Txt(p.name, Type.Heading, Modifier.padding(top = 16.dp))
                }
            }
        }
        Box(Modifier.weight(0.4f))
    }
    asking?.let { target ->
        PinPrompt(lib.profile.value.pin, onDismiss = { asking = null }) { lib.switchProfile(target.id); asking = null; onPicked() }
    }
}

/** Asks for a 4-digit PIN; calls [onOk] when it matches [pin]. */
@Composable
fun PinPrompt(pin: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(480.dp).clip(RoundedCornerShape(22.dp)).background(Palette.Sheet).padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(stringResource(R.string.enter_pin), Type.Heading)
            Gap(14.dp)
            TextInput(entry, { entry = it.filter(Char::isDigit).take(8) }, "••••", FocusFirst().fillMaxWidth(), password = true, keyboard = KeyboardType.NumberPassword) {
                if (entry == pin) onOk() else { wrong = true; entry = "" }
            }
            if (wrong) { Gap(8.dp); Txt(stringResource(R.string.wrong_pin), Type.Body.copy(color = Palette.Accent)) }
            Gap(14.dp)
            PillButton(stringResource(R.string.ok), Modifier.fillMaxWidth(), primary = true) { if (entry == pin) onOk() else { wrong = true; entry = "" } }
        }
    }
}

/** Search and Settings behind the PIN, when the current profile locks them. */
@Composable
fun PinGate(nav: Nav, screen: Screen, content: @Composable () -> Unit) {
    val lib = App.library
    val p by lib.profile.collectAsState()
    val locked = p.restricted && p.pin.isNotEmpty() && when (screen) {
        Screen.Search -> p.lockSearch
        Screen.Settings -> p.lockSettings
        else -> false
    }
    if (!locked || screen in nav.unlocked || lib.stillUnlocked()) { content(); return }
    Box(Modifier.fillMaxSize().screenBackground())
    PinPrompt(p.pin, onDismiss = { nav.pop() }) { lib.markUnlocked(); nav.unlocked += screen }
}

/** Settings → Profiles: switch, add, rename, lock with an age limit and PIN. */
@Composable
fun ProfilesSettings(nav: Nav) {
    val lib = App.library
    val profiles by lib.profiles.collectAsState()
    val current by lib.profile.collectAsState()
    var editing by remember { mutableStateOf<Profile?>(null) }
    var unlock by remember { mutableStateOf<Profile?>(null) }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 240.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 60.dp)) {
        items(profiles, key = { it.id }) { p ->
            ListRow(
                p.name + if (p.id == current.id) "  ✓" else "",
                if (p.restricted) stringResource(R.string.age_limit, p.maxAge) else "",
                if (p == profiles.first()) FocusFirst() else Modifier, chevron = true,
            ) {
                // A locked profile's settings are behind its own PIN.
                if (current.restricted && current.pin.isNotEmpty()) unlock = p else editing = p
            }
        }
        item { ListRow(stringResource(R.string.add_profile), icon = Icons.Filled.Add) { editing = Profile("", App.instance.getString(R.string.new_profile_name), color = profiles.size) } }
    }
    unlock?.let { p -> PinPrompt(current.pin, onDismiss = { unlock = null }) { editing = p; unlock = null } }
    editing?.let { p -> ProfileEditor(p) { editing = null } }
    }
}

@Composable
private fun ProfileEditor(start: Profile, onDone: () -> Unit) {
    val lib = App.library
    var p by remember { mutableStateOf(start) }
    BackHandler { if (p.name.isNotBlank()) lib.saveProfile(p); onDone() }
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
        // Scrolls: with restrictions on, the options are taller than a 1080p screen.
        Column(
            Modifier.width(640.dp).heightIn(max = 640.dp).clip(RoundedCornerShape(22.dp)).background(Palette.Sheet)
                .verticalScroll(rememberScrollState()).padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(p, 110.dp)
            Gap(16.dp)
            TextInput(p.name, { p = p.copy(name = it.take(20)) }, stringResource(R.string.field_name), FocusFirst().fillMaxWidth())
            Gap(10.dp)
            // The main profile follows the theme colour, so only the others pick their own.
            if (p.id != "main") {
                ListRow(stringResource(R.string.profile_color)) { p = p.copy(color = (p.color + 1) % PROFILE_COLORS.size) }
                Gap(8.dp)
            }
            OnOffRow(stringResource(R.string.restrictions), p.restricted, sub = stringResource(R.string.restrictions_sub)) { v -> p = p.copy(restricted = v) }
            if (p.restricted) {
                Gap(8.dp)
                ChoiceRow(stringResource(R.string.allowed_ratings), listOf(7, 10, 13, 16, 18, 0).map { it to stringResource(R.string.age_limit, it) }, p.maxAge) { v -> p = p.copy(maxAge = v) }
                Gap(8.dp)
                ChoiceRow(stringResource(R.string.allowed_tv_ratings), listOf(-1, 7, 10, 13, 16, 18, 0).map { it to if (it < 0) stringResource(R.string.same_as_movies) else stringResource(R.string.age_limit, it) }, p.maxTvAge) { v -> p = p.copy(maxTvAge = v) }
                Gap(8.dp)
                TextInput(p.pin, { p = p.copy(pin = it.filter(Char::isDigit).take(8)) }, stringResource(R.string.set_pin), Modifier.fillMaxWidth(), password = true, keyboard = KeyboardType.NumberPassword)
                Gap(8.dp)
                OnOffRow(stringResource(R.string.lock_search), p.lockSearch) { v -> p = p.copy(lockSearch = v) }
                Gap(8.dp)
                OnOffRow(stringResource(R.string.lock_settings), p.lockSettings) { v -> p = p.copy(lockSettings = v) }
                Gap(8.dp)
                ListRow(stringResource(R.string.ask_again), stringResource(listOf(R.string.ask_always, R.string.ask_15min, R.string.ask_1hour)[p.askAgain.coerceIn(0, 2)])) { p = p.copy(askAgain = (p.askAgain + 1) % 3) }
            }
            Gap(16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(stringResource(R.string.save), primary = true) { if (p.name.isNotBlank()) { lib.saveProfile(p) }; onDone() }
                if (p.id.isNotEmpty() && p.id != "main") PillButton(stringResource(R.string.remove_source)) { lib.removeProfile(p.id); onDone() }
                if (p.id.isNotEmpty() && p.id != lib.profile.value.id) PillButton(stringResource(R.string.switch_profile)) { lib.switchProfile(p.id); onDone() }
            }
        }
    }
}
