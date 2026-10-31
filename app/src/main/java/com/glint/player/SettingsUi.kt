package com.glint.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings sections, in menu order. */
private val SECTIONS = listOf(
    R.string.profiles, R.string.sources, R.string.library, R.string.general, R.string.metadata_artwork,
    R.string.collections_groups, R.string.playback, R.string.audio, R.string.subtitles, R.string.language, R.string.about_title,
)

/** Index of Settings → Language in [SECTIONS]. */
val LANGUAGE_SECTION = SECTIONS.indexOf(R.string.language)

/** Glint's privacy policy, as published for Google Play (the same text as PRIVACY.md in the repo). */
const val PRIVACY_URL = "https://boraswim.github.io/glint/privacy/"

private val LANGS = listOf("en", "tr", "de", "fr", "es", "it", "pt", "nl", "sv", "pl", "ru", "ar", "ja", "ko", "zh")
private val TMDB_REGION = mapOf("en" to "US", "pt" to "BR", "sv" to "SE", "ar" to "SA", "ja" to "JP", "ko" to "KR", "zh" to "CN")

/** "Türkçe", "Deutsch": each language in its own words. */
private fun langName(code: String): String {
    val l = java.util.Locale(code.substringBefore('-'))
    return l.getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) }
}

/** Settings home: profile and watch stats on the left, the section menu on the right. */
@Composable
fun SettingsScreen(nav: Nav) {
    val lib = App.library
    val progress by lib.progress.collectAsState()
    val stats = remember(progress) { lib.stats() }
    val profile by lib.profile.collectAsState()
    Box(Modifier.fillMaxSize().screenBackground()) {
    ScreenTitle(stringResource(R.string.tab_settings))
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(profile, 200.dp)
                    Gap(18.dp)
                    Txt(profile.name, Type.Title)
                    Gap(14.dp)
                    Txt(stringResource(R.string.stats_episodes, stats.episodes), Type.Heading.copy(color = Palette.Dim, fontSize = 18.sp))
                    Gap(6.dp)
                    Txt(stringResource(R.string.stats_shows, stats.shows), Type.Heading.copy(color = Palette.Dim, fontSize = 18.sp))
                    Gap(6.dp)
                    Txt(stringResource(R.string.stats_movies, stats.movies), Type.Heading.copy(color = Palette.Dim, fontSize = 18.sp))
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight().padding(end = 80.dp),
            contentPadding = PaddingValues(top = 120.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(SECTIONS.size) { i ->
                val wanted = nav.focused(Screen.Settings) ?: "0"
                ListRow(stringResource(SECTIONS[i]), chevron = true, modifier = Modifier.restoreFocus("$i", wanted)) {
                    nav.remember(Screen.Settings, "$i")
                    nav.push(if (SECTIONS[i] == R.string.sources) Screen.Folder() else Screen.SettingsSection(i))
                }
            }
            // Last, apart from the sections, in red: everything Glint keeps on this TV, after a confirmation that
            // starts on "Cancel".
            item {
                var asking by remember { mutableStateOf(false) }
                val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
                Gap(14.dp)
                ListRow(stringResource(R.string.reset_all), sub = stringResource(R.string.reset_all_sub), danger = true) { asking = true }
                // In its own window, over the whole screen; drawn in the list it would sit inside this row.
                if (asking) androidx.compose.ui.window.Dialog({ asking = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                    TvScale {
                        OptionSheet(stringResource(R.string.reset_all_confirm), listOf(
                            stringResource(R.string.cancel) to {},
                            stringResource(R.string.reset_all_yes) to { activity?.let { App.instance.startFresh(it) } },
                        )) { asking = false }
                    }
                }
            }
        }
    }
    }
}

/** A setting's row with the value in use on the right; OK opens the choices to pick from, that one ticked. */
@Composable
internal fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, current: T, modifier: Modifier = Modifier, sub: String? = null, onPick: (T) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val at = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
    ListRow(label, options[at].second, modifier, sub = sub) { picking = true }
    // In its own window, over the whole screen; drawn in the list it would sit inside this row.
    if (picking) androidx.compose.ui.window.Dialog({ picking = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        TvScale {
            OptionSheet(label, options.mapIndexed { i, (value, name) ->
                ((if (i == at) "\u2713  " else "") + name) to { if (value != current) onPick(value) }
            }, message = sub, start = at) { picking = false }
        }
    }
}

@Composable
internal fun OnOffRow(label: String, on: Boolean, modifier: Modifier = Modifier, sub: String? = null, onPick: (Boolean) -> Unit) =
    ChoiceRow(label, listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), on, modifier, sub, onPick)

@Composable
fun SettingsSectionScreen(nav: Nav, section: Int) {
    val lib = App.library
    val s = lib.settings
    val sources by lib.sources.collectAsState()
    val scan by lib.scan.collectAsState()
    var tick by remember { mutableIntStateOf(0) }   // settings aren't Compose state; bump to redraw
    var testing by remember { mutableStateOf(false) }
    var speedError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun change(block: () -> Unit) { block(); tick++ }

    Column(Modifier.fillMaxSize().screenBackground()) {
        ScreenTitle(stringResource(SECTIONS[section]))
        if (section == 0) { ProfilesSettings(nav); return@Column }
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 240.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (section) {
                0 -> {}
                1 -> {
                    items(sources, key = { it.id }) { src ->
                        ListRow(src.name, src.host + if (src.share.isNotEmpty()) "/" + src.share else "", if (src == sources.first()) FocusFirst() else Modifier, chevron = true) { nav.push(Screen.EditSource(src.id)) }
                    }
                    item { ListRow(stringResource(R.string.add_source), modifier = if (sources.isEmpty()) FocusFirst() else Modifier) { nav.push(Screen.EditSource(null)) } }
                    item {
                        val speed = s.measuredMbps
                        ListRow(
                            stringResource(R.string.speed_test),
                            when {
                                testing -> stringResource(R.string.speed_testing)
                                speed > 0 -> stringResource(R.string.mbps, speed.toInt())
                                else -> ""
                            },
                            sub = speedError ?: if (speed > 0) stringResource(R.string.speed_result_sub, (speed * 0.8f).toInt()) else stringResource(R.string.speed_test_sub),
                        ) {
                            if (!testing) {
                                testing = true; speedError = null
                                scope.launch {
                                    withContext(Dispatchers.IO) { runCatching { lib.speedTest() } }.onFailure { speedError = it.message }
                                    testing = false
                                }
                            }
                        }
                    }
                }
                2 -> {
                    item {
                        val st = scan
                        val sub = when {
                            st == null -> null
                            st.finished -> listOfNotNull(st.message, st.error).joinToString(" · ")
                            else -> st.message
                        }
                        ListRow(stringResource(R.string.rescan), sub = sub, modifier = FocusFirst()) { lib.scanAll() }
                    }
                    item { ListRow(stringResource(R.string.refresh_metadata), sub = stringResource(R.string.refresh_metadata_sub)) { lib.clearLibrary(); lib.scanAll() } }
                    item {
                        var size by remember { mutableStateOf(lib.cacheBytes()) }
                        var asking by remember { mutableStateOf(false) }
                        // In red, and OK asks first, starting on "Cancel": a full cache took a long time to build.
                        ListRow(stringResource(R.string.library_cache), bytes(size), sub = stringResource(R.string.library_cache_sub), danger = true) { asking = true }
                        if (asking) androidx.compose.ui.window.Dialog({ asking = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                            TvScale {
                                OptionSheet(stringResource(R.string.clear_cache_confirm), listOf(
                                    stringResource(R.string.cancel) to {},
                                    stringResource(R.string.clear_cache_yes) to { lib.clearCache(); size = lib.cacheBytes() },
                                )) { asking = false }
                            }
                        }
                    }
                    item {
                        var msg by remember { mutableStateOf<String?>(null) }
                        ListRow(stringResource(R.string.backup_now), sub = msg ?: stringResource(R.string.backup_sub)) {
                            msg = App.instance.getString(R.string.speed_testing)
                            scope.launch { msg = withContext(Dispatchers.IO) { runCatching { App.instance.getString(R.string.backup_done, lib.backupToNas()) }.getOrElse { it.message ?: it.javaClass.simpleName } } }
                        }
                    }
                    item {
                        var msg by remember { mutableStateOf<String?>(null) }
                        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
                        ListRow(stringResource(R.string.restore_backup), sub = msg ?: stringResource(R.string.restore_sub)) {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { lib.restoreFromNas() } }
                                if (r.isSuccess) { activity?.finishAffinity(); Runtime.getRuntime().exit(0) } else msg = r.exceptionOrNull()?.message
                            }
                        }
                    }
                    item {
                    if (tick < 0) return@item   // read so a change redraws the rows
                    OnOffRow(stringResource(R.string.picks_setting), s.picksOfTheDay) { v -> change { s.picksOfTheDay = v } }
                }
                }
                3 -> item {
                    if (tick < 0) return@item   // read so a change redraws the rows
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OnOffRow(stringResource(R.string.poster_titles), s.showPosterTitles, FocusFirst()) { v -> change { s.showPosterTitles = v } }
                        OnOffRow(stringResource(R.string.watched_indicators), s.watchedIndicators, sub = stringResource(R.string.watched_indicators_sub)) { v -> change { s.watchedIndicators = v } }
                        OnOffRow(stringResource(R.string.show_filenames), s.showFilenames) { v -> change { s.showFilenames = v } }
                        OnOffRow(stringResource(R.string.list_view), s.listView) { v -> change { s.listView = v } }
                        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
                        // OK opens the three appearances to pick from (ticked: the one in use); picking redraws the app.
                        val themes = listOf(R.string.theme_black, R.string.dark, R.string.light).map { stringResource(it) }
                        var pickingTheme by remember { mutableStateOf(false) }
                        ListRow(stringResource(R.string.appearance), themes[s.theme.coerceIn(0, 2)]) { pickingTheme = true }
                        if (pickingTheme) androidx.compose.ui.window.Dialog({ pickingTheme = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                            TvScale {
                                OptionSheet(stringResource(R.string.appearance), themes.mapIndexed { i, name ->
                                    ((if (s.theme == i) "✓  " else "") + name) to {
                                        if (s.theme != i) {
                                            s.theme = i
                                            Palette.light = i == 2; Palette.black = i == 0
                                            AppLanguage.reopenOnSection = SECTIONS.indexOf(R.string.general)
                                            activity?.recreate()
                                        }
                                    }
                                }, start = s.theme.coerceIn(0, 2)) { pickingTheme = false }
                            }
                        }
                        // OK opens the colours to pick from (ticked: the one in use); the colour changes at once.
                        val accents = ACCENTS.map { stringResource(it.first) }
                        var pickingAccent by remember { mutableStateOf(false) }
                        ListRow(stringResource(R.string.theme_color), accents[s.accent.coerceIn(0, ACCENTS.lastIndex)]) { pickingAccent = true }
                        if (pickingAccent) androidx.compose.ui.window.Dialog({ pickingAccent = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                            TvScale {
                                OptionSheet(stringResource(R.string.theme_color), accents.mapIndexed { i, name ->
                                    ((if (s.accent == i) "✓  " else "") + name) to { change { s.accent = i; Palette.accent = i } }
                                }, start = s.accent.coerceIn(0, ACCENTS.lastIndex)) { pickingAccent = false }
                            }
                        }
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        ChoiceRow(stringResource(R.string.app_logo), listOf(false to stringResource(R.string.app_logo_black), true to stringResource(R.string.app_logo_white)), AppLogo.isWhite(ctx), sub = stringResource(R.string.app_logo_sub)) { white ->
                            change { AppLogo.set(ctx, white) }
                        }
                        // OK lists the hidden tiles; each pick brings that one back and the list stays open for more
                        // (Back closes it). On the right, how many are hidden.
                        val user by lib.user.collectAsState()
                        val hidden = user.hiddenTiles.filter { it in FAVORITE_TILE_NAMES.indices }.sorted()
                        var restoring by remember { mutableStateOf(false) }
                        var keepOpen by remember { mutableStateOf(false) }
                        ListRow(stringResource(R.string.restore_tiles), if (hidden.isEmpty()) "" else "${hidden.size}", sub = stringResource(R.string.restore_tiles_sub)) {
                            if (hidden.isNotEmpty()) restoring = true
                        }
                        if (restoring && hidden.isNotEmpty()) androidx.compose.ui.window.Dialog({ restoring = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                            TvScale {
                                // Keyed on the list, so after a pick the sheet is rebuilt with the rest, focus on the first.
                                androidx.compose.runtime.key(hidden) {
                                    OptionSheet(stringResource(R.string.restore_tiles), hidden.map { i ->
                                        stringResource(FAVORITE_TILE_NAMES[i]) to { keepOpen = hidden.size > 1; lib.showTile(i) }
                                    }) { if (keepOpen) keepOpen = false else restoring = false }
                                }
                            }
                        }
                        // What pressing Glint's icon opens: Home, or one of the sections Home has tiles for.
                        val starts = listOf(R.string.tab_home to -1) + FAVORITE_TILE_NAMES.mapIndexed { i, name -> name to i }
                        val startNames = starts.map { stringResource(it.first) }
                        val startAt = starts.indexOfFirst { it.second == s.startScreen }.coerceAtLeast(0)
                        var pickingStart by remember { mutableStateOf(false) }
                        ListRow(stringResource(R.string.start_screen), startNames[startAt], sub = stringResource(R.string.start_screen_sub)) { pickingStart = true }
                        if (pickingStart) androidx.compose.ui.window.Dialog({ pickingStart = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                            TvScale {
                                OptionSheet(stringResource(R.string.start_screen), starts.mapIndexed { i, (_, value) ->
                                    ((if (i == startAt) "✓  " else "") + startNames[i]) to { change { s.startScreen = value } }
                                }, start = startAt) { pickingStart = false }
                            }
                        }
                        OnOffRow(stringResource(R.string.hide_spoilers), s.hideSpoilers, sub = stringResource(R.string.hide_spoilers_sub)) { v -> change { s.hideSpoilers = v } }
                        val sorts = listOf(R.string.sort_title, R.string.sort_added, R.string.sort_year, R.string.sort_rating, R.string.sort_duration, R.string.sort_file_name)
                        ChoiceRow(stringResource(R.string.sort_order), sorts.mapIndexed { i, r -> i to stringResource(r) }, s.sortOrder) { v -> change { s.sortOrder = v } }
                    }
                }
                4 -> item {
                    if (tick < 0) return@item   // read so a change redraws the rows
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OnOffRow(stringResource(R.string.show_logos), s.showLogos, FocusFirst()) { v -> change { s.showLogos = v } }
                        OnOffRow(stringResource(R.string.prefer_local_metadata), s.preferLocalMetadata, sub = stringResource(R.string.prefer_local_metadata_sub)) { v -> change { s.preferLocalMetadata = v } }
                        OnOffRow(stringResource(R.string.show_ratings), s.showRatings) { v -> change { s.showRatings = v } }
                        // The viewer's own keys: TMDB for details, artwork and trailers; OMDb for IMDb and Rotten Tomatoes.
                        TmdbKeyField()
                        OmdbKeyField()
                    }
                }
                5 -> item {
                    if (tick < 0) return@item   // read so a change redraws the rows
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OnOffRow(stringResource(R.string.group_collections), s.groupMovieCollections, FocusFirst()) { v -> change { s.groupMovieCollections = v } }
                        OnOffRow(stringResource(R.string.collection_details), s.collectionDetails, sub = stringResource(R.string.collection_details_sub)) { v -> change { s.collectionDetails = v } }
                    }
                }
                6 -> item {
                    if (tick < 0) return@item   // read so a change redraws the rows
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OnOffRow(stringResource(R.string.autoplay_next), s.autoplayNext, FocusFirst()) { v -> change { s.autoplayNext = v } }
                        OnOffRow(stringResource(R.string.auto_select_next), s.autoSelectNext, sub = stringResource(R.string.auto_select_next_sub)) { v -> change { s.autoSelectNext = v } }
                        ChoiceRow(stringResource(R.string.resume_mode), listOf(R.string.resume_ask, R.string.resume_always, R.string.resume_never).mapIndexed { i, r -> i to stringResource(r) }, s.resumeMode) { v -> change { s.resumeMode = v } }
                        ChoiceRow(stringResource(R.string.edge_mode), listOf(0 to stringResource(R.string.edge_time), 1 to stringResource(R.string.chapters)), s.edgeMode, sub = stringResource(R.string.edge_mode_sub)) { v -> change { s.edgeMode = v } }
                        ChoiceRow(stringResource(R.string.forward_skip), listOf(5, 10, 15, 30, 60).map { it to stringResource(R.string.seconds, it) }, s.forwardSkip) { v -> change { s.forwardSkip = v } }
                        ChoiceRow(stringResource(R.string.backward_skip), listOf(5, 10, 15, 30, 60).map { it to stringResource(R.string.seconds, it) }, s.backwardSkip) { v -> change { s.backwardSkip = v } }
                        ChoiceRow(stringResource(R.string.skip_intros), listOf(R.string.off, R.string.skip_button, R.string.skip_auto_delay, R.string.skip_auto_instant).mapIndexed { i, r -> i to stringResource(r) }, s.skipIntros, sub = stringResource(R.string.skip_intros_sub)) { v -> change { s.skipIntros = v } }
                        ChoiceRow(stringResource(R.string.speed), listOf(75, 100, 125, 150, 200).map { it to "${it / 100f}×".replace(".0×", "×") }, s.defaultSpeed) { v -> change { s.defaultSpeed = v } }
                        ChoiceRow(stringResource(R.string.streaming_cache), listOf(R.string.cache_auto, R.string.cache_limited, R.string.cache_memory, R.string.cache_legacy).mapIndexed { i, r -> i to stringResource(r) }, s.cacheMode) { v -> change { s.cacheMode = v } }
                        ChoiceRow(stringResource(R.string.deinterlace), listOf(R.string.auto, R.string.on, R.string.off).mapIndexed { i, r -> i to stringResource(r) }, s.deinterlace) { v -> change { s.deinterlace = v } }
                        if (DolbyVision.decoderAvailable) OnOffRow(stringResource(R.string.dolby_vision), s.dolbyVision, sub = stringResource(R.string.dolby_vision_sub)) { v -> change { s.dolbyVision = v } }
                        if (DolbyVision.decoderAvailable && s.dolbyVision) OnOffRow(stringResource(R.string.dovi_compat), s.doviCompat, sub = stringResource(R.string.dovi_compat_sub)) { v -> change { s.doviCompat = v } }
                        OnOffRow(stringResource(R.string.match_frame_rate), s.matchFrameRate, sub = stringResource(R.string.match_frame_rate_sub)) { v -> change { s.matchFrameRate = v } }
                        ChoiceRow(stringResource(R.string.buffer), listOf(1500, 3000, 6000, 10000, 20000).map { it to stringResource(R.string.seconds, it / 1000) }, s.bufferMs, sub = stringResource(R.string.buffer_sub)) { v -> change { s.bufferMs = v } }
                    }
                }
                7 -> item {
                    if (tick < 0) return@item   // read so a change redraws the rows
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OnOffRow(stringResource(R.string.passthrough), s.passthrough, FocusFirst(), sub = stringResource(R.string.passthrough_sub)) { v -> change { s.passthrough = v } }
                        ChoiceRow(stringResource(R.string.volume_boost), listOf(100, 150, 200, 250, 300).map { it to "$it%" }, s.volumeBoost, sub = stringResource(R.string.volume_boost_sub)) { v -> change { s.volumeBoost = v } }
                        ChoiceRow(stringResource(R.string.downmix_volume), listOf(true to stringResource(R.string.downmix_normalized), false to stringResource(R.string.downmix_original)), s.normalizeDownmix, sub = stringResource(R.string.downmix_sub)) { v -> change { s.normalizeDownmix = v } }
                    }
                }
                8 -> item {
                    if (tick < 0) return@item   // read so a change redraws the rows
                    val colors = listOf(0xFFFFFF to R.string.color_white, 0xFFE15A to R.string.color_yellow, 0xB4B4B4 to R.string.color_grey)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OnOffRow(stringResource(R.string.forced_subtitles), s.forcedSubtitles, FocusFirst(), sub = stringResource(R.string.forced_subtitles_sub)) { v -> change { s.forcedSubtitles = v } }
                        ChoiceRow(stringResource(R.string.subtitle_size), listOf(75, 100, 125, 150, 175).map { it to "$it%" }, s.subtitleScale) { v -> change { s.subtitleScale = v } }
                        ChoiceRow(stringResource(R.string.subtitle_color), colors.map { (c, r) -> c to stringResource(r) }, s.subtitleColor) { v -> change { s.subtitleColor = v } }
                        OnOffRow(stringResource(R.string.subtitle_background), s.subtitleBackground) { v -> change { s.subtitleBackground = v } }
                        ChoiceRow(stringResource(R.string.subtitle_position), listOf(0 to stringResource(R.string.pos_bottom), 60 to stringResource(R.string.pos_raised), 140 to stringResource(R.string.pos_high)), s.subtitlePosition) { v -> change { s.subtitlePosition = v } }
                        ChoiceRow(stringResource(R.string.subtitle_outline), listOf(0 to stringResource(R.string.outline_none), 2 to stringResource(R.string.outline_thin), 4 to stringResource(R.string.outline_normal), 6 to stringResource(R.string.outline_thick)), s.subtitleOutline) { v -> change { s.subtitleOutline = v } }
                        OnOffRow(stringResource(R.string.subtitle_bold), s.subtitleBold) { v -> change { s.subtitleBold = v } }
                        Gap(10.dp)
                        OpenSubtitlesAccount()
                    }
                }
                9 -> item {
                    if (tick < 0) return@item   // read so a change redraws the rows
                    val ctx = App.instance
                    fun label(code: String, empty: Int) = when (code) {
                        "" -> ctx.getString(empty)
                        "auto" -> ctx.getString(R.string.lang_device)
                        "original" -> ctx.getString(R.string.lang_original)
                        else -> langName(code)
                    }
                    val meta = listOf("", "original") + LANGS.map { "$it-${TMDB_REGION[it] ?: it.uppercase()}" }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // The interface itself: OK opens the list of languages; picking one reopens the app in it,
                        // back on this page.
                        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
                        ChoiceRow(stringResource(R.string.app_language), (listOf("") + AppLanguage.CODES).map { it to label(it, R.string.lang_device) }, s.appLanguage, FocusFirst()) { code ->
                            s.appLanguage = code
                            AppLanguage.apply(App.instance)
                            AppLanguage.reopenOnSection = LANGUAGE_SECTION
                            activity?.recreate()
                        }
                        ChoiceRow(stringResource(R.string.audio_language), (listOf("") + LANGS).map { it to label(it, R.string.lang_default) }, s.audioLanguage) { v -> change { s.audioLanguage = v } }
                        ChoiceRow(stringResource(R.string.subtitle_language), (listOf("auto", "") + LANGS).map { it to label(it, R.string.subs_off) }, s.subtitleLanguage) { v -> change { s.subtitleLanguage = v } }
                        ChoiceRow(stringResource(R.string.metadata_language), meta.map { it to label(it, R.string.lang_device) }, s.metadataLanguage, sub = stringResource(R.string.metadata_language_sub)) { v -> change { s.metadataLanguage = v } }
                        ChoiceRow(stringResource(R.string.artwork_language), (listOf("", "original") + LANGS).map { it to label(it, R.string.same_as_metadata) }, s.artworkLanguage, sub = stringResource(R.string.artwork_language_sub)) { v -> change { s.artworkLanguage = v } }
                    }
                }
                else -> item {
                    Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Txt(stringResource(R.string.about, BuildConfig.VERSION_NAME), Type.Heading, modifier = FocusFirst())
                        Gap(10.dp)
                        Txt(stringResource(R.string.tmdb_notice), Type.Body, align = TextAlign.Center)
                        Txt(stringResource(R.string.omdb_notice), Type.Body, align = TextAlign.Center)
                        Gap(6.dp)
                        Txt(stringResource(R.string.license_notice), Type.Body, align = TextAlign.Center)
                        if (!lib.tmdb.enabled) { Gap(6.dp); Txt(stringResource(R.string.tmdb_missing), Type.Body.copy(color = Palette.Accent)) }
                        // The privacy policy, as Google Play asks: its address is shown, and OK opens it where the TV
                        // has a browser (many don't, hence the address on screen).
                        Gap(20.dp)
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        ListRow(stringResource(R.string.privacy_policy), sub = PRIVACY_URL, modifier = Modifier.widthIn(max = 720.dp)) {
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_URL))) }
                                .onFailure { android.widget.Toast.makeText(ctx, ctx.getString(R.string.no_browser, PRIVACY_URL), android.widget.Toast.LENGTH_LONG).show() }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A field for one of the viewer's own API keys, saved as typed; finishing the entry (OK on the keyboard) asks
 * [check] (off the main thread) whether the service accepts it, and says so below.
 */
@Composable
private fun ApiKeyField(label: Int, sub: Int, value: String, onSave: (String) -> Unit, modifier: Modifier = Modifier, check: (String) -> Boolean) {
    var text by remember { mutableStateOf(value) }
    var state by remember { mutableStateOf<Boolean?>(null) }     // null: not checked; true/false: works or not
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
        Txt(stringResource(label), Type.Heading)
        Txt(stringResource(sub), Type.Small, maxLines = 2)
        Gap(8.dp)
        TextInput(text, { text = it.trim(); onSave(text); state = null }, "", modifier.fillMaxWidth(), keyboard = androidx.compose.ui.text.input.KeyboardType.Ascii, onDone = {
            if (text.isEmpty()) return@TextInput   // cleared: nothing to check
            checking = true
            scope.launch { state = withContext(Dispatchers.IO) { check(text) }; checking = false }
        })
        when {
            checking -> Txt(stringResource(R.string.key_checking), Type.Small)
            state == true -> Txt(stringResource(R.string.key_ok), Type.Small.copy(color = Palette.Accent))
            state == false -> Txt(stringResource(R.string.key_bad), Type.Small.copy(color = Color(0xFFE5484D)))
        }
    }
}

/** The viewer's TMDB key; once TMDB accepts it, a library update matches the films. */
@Composable
fun TmdbKeyField(modifier: Modifier = Modifier) {
    val lib = App.library
    val s = lib.settings
    ApiKeyField(R.string.tmdb_key, R.string.tmdb_key_sub, s.tmdbKey, { s.tmdbKey = it }, modifier) { key ->
        Tmdb({ key }, { s.language }).works().also { if (it) lib.scanAll() }   // match the library now it can be
    }
}

/**
 * OpenSubtitles.com, for "Search subtitles" in the player: the viewer's API key, then their account. Entered once;
 * the user name and password are checked by logging in when the password is done.
 */
@Composable
private fun OpenSubtitlesAccount() {
    val s = App.library.settings
    var user by remember { mutableStateOf(s.osUser) }
    var pass by remember { mutableStateOf(s.osPassword) }
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun check() {
        if (s.osApiKey.isBlank() || user.isBlank() || pass.isEmpty()) return
        checking = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { OpenSubtitles.login() } }
            ok = r.isSuccess
            result = r.fold({ ctx.getString(R.string.os_logged_in, it) }, { it.message ?: it.javaClass.simpleName })
            checking = false
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Txt(stringResource(R.string.opensubtitles), Type.Title, Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
        Txt(stringResource(R.string.opensubtitles_sub), Type.Small, Modifier.padding(horizontal = 22.dp), maxLines = 3)
        ApiKeyField(R.string.os_api_key, R.string.os_api_key_sub, s.osApiKey, { s.osApiKey = it }) { OpenSubtitles.works(it) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
            Txt(stringResource(R.string.os_user), Type.Heading)
            Gap(8.dp)
            TextInput(user, { user = it.trim(); s.osUser = user; result = null }, "", Modifier.fillMaxWidth(), keyboard = androidx.compose.ui.text.input.KeyboardType.Email, onDone = ::check)
            Gap(12.dp)
            Txt(stringResource(R.string.os_password), Type.Heading)
            Gap(8.dp)
            TextInput(pass, { pass = it; s.osPassword = it; result = null }, "", Modifier.fillMaxWidth(), password = true, onDone = ::check)
            when {
                checking -> Txt(stringResource(R.string.key_checking), Type.Small)
                result != null -> Txt(result!!, Type.Small.copy(color = if (ok) Palette.Accent else Color(0xFFE5484D)))
            }
        }
    }
}

/** The viewer's OMDb key, for IMDb and Rotten Tomatoes scores. */
@Composable
fun OmdbKeyField(modifier: Modifier = Modifier) {
    val s = App.library.settings
    ApiKeyField(R.string.omdb_key, R.string.omdb_key_sub, s.omdbKey, { s.omdbKey = it }, modifier) { key ->
        Omdb.works(key).also { if (it) Omdb.clear(App.instance) }
    }
}

/**
 * Right after the first share is added (when there's no TMDB key yet): what the two free keys bring, with their
 * fields. Continue, or Back, goes on to Home; the keys stay in Settings → Metadata & Artwork for later.
 */
@Composable
fun KeysScreen(nav: Nav) {
    androidx.activity.compose.BackHandler { nav.tab(Screen.Home) }
    Column(Modifier.fillMaxSize().screenBackground().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 160.dp, vertical = 48.dp)) {
        Txt(stringResource(R.string.keys_title), Type.Hero)
        Gap(12.dp)
        Txt(stringResource(R.string.keys_body), Type.Body.copy(fontSize = 17.sp, lineHeight = 24.sp))
        Gap(24.dp)
        TmdbKeyField(FocusFirst())
        Gap(8.dp)
        OmdbKeyField()
        Gap(20.dp)
        Txt(stringResource(R.string.keys_later), Type.Small)
        Gap(16.dp)
        PillButton(stringResource(R.string.keys_continue), Modifier.padding(start = 22.dp), primary = true) { nav.tab(Screen.Home) }
    }
}
