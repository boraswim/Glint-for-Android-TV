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
    fun onOff(v: Boolean) = App.instance.getString(if (v) R.string.on else R.string.off)

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
                        // OK asks first, starting on "Cancel": a full cache took a long time to build.
                        ListRow(stringResource(R.string.library_cache), bytes(size), sub = stringResource(R.string.library_cache_sub)) { asking = true }
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
                    ListRow(stringResource(R.string.picks_setting), onOff(s.picksOfTheDay)) { change { s.picksOfTheDay = !s.picksOfTheDay } }
                }
                }
                3 -> item {
                    if (tick < 0) return@item   // read so a change redraws the rows
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ListRow(stringResource(R.string.poster_titles), onOff(s.showPosterTitles), FocusFirst()) { change { s.showPosterTitles = !s.showPosterTitles } }
                        ListRow(stringResource(R.string.watched_indicators), onOff(s.watchedIndicators), sub = stringResource(R.string.watched_indicators_sub)) { change { s.watchedIndicators = !s.watchedIndicators } }
                        ListRow(stringResource(R.string.show_filenames), onOff(s.showFilenames)) { change { s.showFilenames = !s.showFilenames } }
                        ListRow(stringResource(R.string.list_view), onOff(s.listView)) { change { s.listView = !s.listView } }
                        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
                        ListRow(stringResource(R.string.appearance), stringResource(listOf(R.string.theme_black, R.string.dark, R.string.light)[s.theme.coerceIn(0, 2)])) {
                            s.theme = (s.theme + 1) % 3
                            Palette.light = s.theme == 2; Palette.black = s.theme == 0
                            activity?.recreate()
                        }
                        ListRow(stringResource(R.string.theme_color), stringResource(ACCENTS[s.accent.coerceIn(0, ACCENTS.lastIndex)].first)) {
                            change { s.accent = (s.accent + 1) % ACCENTS.size; Palette.accent = s.accent }
                        }
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        ListRow(stringResource(R.string.app_logo), stringResource(if (AppLogo.isWhite(ctx)) R.string.app_logo_white else R.string.app_logo_black), sub = stringResource(R.string.app_logo_sub)) {
                            change { AppLogo.set(ctx, !AppLogo.isWhite(ctx)) }
                        }
                        ListRow(stringResource(R.string.restore_tiles), sub = stringResource(R.string.restore_tiles_sub)) { lib.showAllTiles() }
                        ListRow(stringResource(R.string.hide_spoilers), onOff(s.hideSpoilers), sub = stringResource(R.string.hide_spoilers_sub)) { change { s.hideSpoilers = !s.hideSpoilers } }
                        val sorts = listOf(R.string.sort_title, R.string.sort_added, R.string.sort_year, R.string.sort_rating, R.string.sort_duration, R.string.sort_file_name)
                        ListRow(stringResource(R.string.sort_order), stringResource(sorts[s.sortOrder.coerceIn(0, sorts.lastIndex)])) { change { s.sortOrder = (s.sortOrder + 1) % sorts.size } }
                    }
                }
                4 -> item {
                    if (tick < 0) return@item   // read so a change redraws the rows
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ListRow(stringResource(R.string.show_logos), onOff(s.showLogos), FocusFirst()) { change { s.showLogos = !s.showLogos } }
                        ListRow(stringResource(R.string.prefer_local_metadata), onOff(s.preferLocalMetadata), sub = stringResource(R.string.prefer_local_metadata_sub)) { change { s.preferLocalMetadata = !s.preferLocalMetadata } }
                        ListRow(stringResource(R.string.show_ratings), onOff(s.showRatings)) { change { s.showRatings = !s.showRatings } }
                        // The viewer's own keys: TMDB for details, artwork and trailers; OMDb for IMDb and Rotten Tomatoes.
                        TmdbKeyField()
                        OmdbKeyField()
                    }
                }
                5 -> item {
                    if (tick < 0) return@item   // read so a change redraws the rows
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ListRow(stringResource(R.string.group_collections), onOff(s.groupMovieCollections), FocusFirst()) { change { s.groupMovieCollections = !s.groupMovieCollections } }
                        ListRow(stringResource(R.string.collection_details), onOff(s.collectionDetails), sub = stringResource(R.string.collection_details_sub)) { change { s.collectionDetails = !s.collectionDetails } }
                    }
                }
                6 -> item {
                    if (tick < 0) return@item   // read so a change redraws the rows
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ListRow(stringResource(R.string.autoplay_next), onOff(s.autoplayNext), FocusFirst()) { change { s.autoplayNext = !s.autoplayNext } }
                        ListRow(stringResource(R.string.auto_select_next), onOff(s.autoSelectNext), sub = stringResource(R.string.auto_select_next_sub)) { change { s.autoSelectNext = !s.autoSelectNext } }
                        ListRow(stringResource(R.string.resume_mode), stringResource(listOf(R.string.resume_ask, R.string.resume_always, R.string.resume_never)[s.resumeMode])) { change { s.resumeMode = (s.resumeMode + 1) % 3 } }
                        ListRow(stringResource(R.string.edge_mode), stringResource(if (s.edgeMode == 1) R.string.chapters else R.string.edge_time), sub = stringResource(R.string.edge_mode_sub)) { change { s.edgeMode = 1 - s.edgeMode } }
                        ListRow(stringResource(R.string.forward_skip), stringResource(R.string.seconds, s.forwardSkip)) { change { s.forwardSkip = cycle(listOf(10, 15, 30, 60, 5), s.forwardSkip) } }
                        ListRow(stringResource(R.string.backward_skip), stringResource(R.string.seconds, s.backwardSkip)) { change { s.backwardSkip = cycle(listOf(10, 15, 30, 60, 5), s.backwardSkip) } }
                        ListRow(stringResource(R.string.skip_intros), stringResource(listOf(R.string.off, R.string.skip_button, R.string.skip_auto_delay, R.string.skip_auto_instant)[s.skipIntros]), sub = stringResource(R.string.skip_intros_sub)) { change { s.skipIntros = (s.skipIntros + 1) % 4 } }
                        ListRow(stringResource(R.string.speed), "${s.defaultSpeed / 100f}×".replace(".0×", "×")) { change { s.defaultSpeed = cycle(listOf(100, 125, 150, 200, 75), s.defaultSpeed) } }
                        ListRow(stringResource(R.string.streaming_cache), stringResource(listOf(R.string.cache_auto, R.string.cache_limited, R.string.cache_memory, R.string.cache_legacy)[s.cacheMode])) { change { s.cacheMode = (s.cacheMode + 1) % 4 } }
                        ListRow(stringResource(R.string.deinterlace), stringResource(listOf(R.string.auto, R.string.on, R.string.off)[s.deinterlace])) { change { s.deinterlace = (s.deinterlace + 1) % 3 } }
                        if (DolbyVision.decoderAvailable) ListRow(stringResource(R.string.dolby_vision), onOff(s.dolbyVision), sub = stringResource(R.string.dolby_vision_sub)) { change { s.dolbyVision = !s.dolbyVision } }
                        if (DolbyVision.decoderAvailable && s.dolbyVision) ListRow(stringResource(R.string.dovi_compat), onOff(s.doviCompat), sub = stringResource(R.string.dovi_compat_sub)) { change { s.doviCompat = !s.doviCompat } }
                        ListRow(stringResource(R.string.match_frame_rate), onOff(s.matchFrameRate), sub = stringResource(R.string.match_frame_rate_sub)) { change { s.matchFrameRate = !s.matchFrameRate } }
                        ListRow(stringResource(R.string.buffer), stringResource(R.string.seconds, s.bufferMs / 1000), sub = stringResource(R.string.buffer_sub)) { change { s.bufferMs = cycle(listOf(3000, 6000, 10000, 20000, 1500), s.bufferMs) } }
                    }
                }
                7 -> item {
                    if (tick < 0) return@item   // read so a change redraws the rows
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ListRow(stringResource(R.string.passthrough), onOff(s.passthrough), FocusFirst(), sub = stringResource(R.string.passthrough_sub)) { change { s.passthrough = !s.passthrough } }
                        ListRow(stringResource(R.string.volume_boost), "${s.volumeBoost}%", sub = stringResource(R.string.volume_boost_sub)) { change { s.volumeBoost = cycle(listOf(100, 150, 200, 250, 300), s.volumeBoost) } }
                        ListRow(stringResource(R.string.downmix_volume), stringResource(if (s.normalizeDownmix) R.string.downmix_normalized else R.string.downmix_original), sub = stringResource(R.string.downmix_sub)) { change { s.normalizeDownmix = !s.normalizeDownmix } }
                    }
                }
                8 -> item {
                    if (tick < 0) return@item   // read so a change redraws the rows
                    val colors = listOf(0xFFFFFF to R.string.color_white, 0xFFE15A to R.string.color_yellow, 0xB4B4B4 to R.string.color_grey)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ListRow(stringResource(R.string.forced_subtitles), onOff(s.forcedSubtitles), FocusFirst(), sub = stringResource(R.string.forced_subtitles_sub)) { change { s.forcedSubtitles = !s.forcedSubtitles } }
                        ListRow(stringResource(R.string.subtitle_size), "${s.subtitleScale}%") { change { s.subtitleScale = cycle(listOf(100, 125, 150, 175, 75), s.subtitleScale) } }
                        ListRow(stringResource(R.string.subtitle_color), stringResource(colors.firstOrNull { it.first == s.subtitleColor }?.second ?: R.string.color_white)) { change { s.subtitleColor = cycle(colors.map { it.first }, s.subtitleColor) } }
                        ListRow(stringResource(R.string.subtitle_background), onOff(s.subtitleBackground)) { change { s.subtitleBackground = !s.subtitleBackground } }
                        ListRow(stringResource(R.string.subtitle_position), stringResource(listOf(R.string.pos_bottom, R.string.pos_raised, R.string.pos_high)[listOf(0, 60, 140).indexOf(s.subtitlePosition).coerceAtLeast(0)])) { change { s.subtitlePosition = cycle(listOf(0, 60, 140), s.subtitlePosition) } }
                        ListRow(stringResource(R.string.subtitle_outline), stringResource(listOf(R.string.outline_none, R.string.outline_thin, R.string.outline_normal, R.string.outline_thick)[listOf(0, 2, 4, 6).indexOf(s.subtitleOutline).coerceAtLeast(0)])) { change { s.subtitleOutline = cycle(listOf(4, 6, 0, 2), s.subtitleOutline) } }
                        ListRow(stringResource(R.string.subtitle_bold), onOff(s.subtitleBold)) { change { s.subtitleBold = !s.subtitleBold } }
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
                        var picking by remember { mutableStateOf(false) }
                        ListRow(stringResource(R.string.app_language), label(s.appLanguage, R.string.lang_device), FocusFirst()) { picking = true }
                        if (picking) OptionSheet(stringResource(R.string.app_language), (listOf("") + AppLanguage.CODES).map { code ->
                            ((if (code == s.appLanguage) "✓  " else "") + label(code, R.string.lang_device)) to {
                                if (code != s.appLanguage) {
                                    s.appLanguage = code
                                    AppLanguage.apply(App.instance)
                                    AppLanguage.reopenOnLanguagePage = true
                                    activity?.recreate()
                                }
                            }
                        }) { picking = false }
                        ListRow(stringResource(R.string.audio_language), label(s.audioLanguage, R.string.lang_default)) {
                            change { s.audioLanguage = cycle(listOf("") + LANGS, s.audioLanguage) }
                        }
                        ListRow(stringResource(R.string.subtitle_language), label(s.subtitleLanguage, R.string.subs_off)) {
                            change { s.subtitleLanguage = cycle(listOf("auto", "") + LANGS, s.subtitleLanguage) }
                        }
                        ListRow(stringResource(R.string.metadata_language), label(s.metadataLanguage, R.string.lang_device), sub = stringResource(R.string.metadata_language_sub)) {
                            change { s.metadataLanguage = cycle(meta, s.metadataLanguage) }
                        }
                        ListRow(stringResource(R.string.artwork_language), label(s.artworkLanguage, R.string.same_as_metadata), sub = stringResource(R.string.artwork_language_sub)) {
                            change { s.artworkLanguage = cycle(listOf("", "original") + LANGS, s.artworkLanguage) }
                        }
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
