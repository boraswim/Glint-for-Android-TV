package com.glint.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

data class TrackOption(val id: Int, val label: String)

/** What is playing, for the overlay. */
data class NowPlaying(
    val key: String,
    val title: String,
    val subtitle: String,
    val file: VideoFile?,
    val overview: String = "",
)

/**
 * Full-screen playback with libVLC, which opens SMB itself and decodes nearly anything
 * (HEVC, AV1, TrueHD, DTS-HD, PGS, ASS). The remote drives it through [dispatchKeyEvent];
 * the Compose overlay ([PlayerOverlay]) only takes keys while a menu is open.
 */
class PlayerActivity : ComponentActivity() {
    private val lib get() = App.library
    private lateinit var vlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var root: FrameLayout
    /** Media3, for Dolby Vision films on a TV that can show them; null while libVLC plays. */
    private var exo: ExoPlayback? = null
    /** Whether the audio delay can be changed (libVLC only; subtitle delay works in both players). */
    val delaysSupported get() = exo == null

    // Overlay state
    var now by mutableStateOf<NowPlaying?>(null)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    /**
     * Set while a subtitle restyle reopens the film: where it was. libVLC restarts from the keyframe before
     * that, so the picture would show a few seconds back and rush forward; it stays covered until it's there.
     */
    var reopenAt by mutableStateOf<Long?>(null)
    private var revealing = false
    var buffering by mutableFloatStateOf(0f)
    var controls by mutableStateOf(true)
    var panel by mutableStateOf(false)
    var panelTab by mutableIntStateOf(0)
    /** The details box in a corner (Settings → Playback, or the Video tab's option). */
    var hud by mutableStateOf(false)
    /** Set while a list inside the menu is open: Back closes that first (returns true). */
    var menuBack: (() -> Boolean)? = null
    /** True while a menu list swaps its rows: focus passing over the tabs then mustn't switch tab. */
    var holdTab = false
    /** The menu's current tab: Up from the top of a list goes back to it, not to the nearest tab. */
    val menuTabFocus = androidx.compose.ui.focus.FocusRequester()
    /** Which of the track buttons above the timeline has focus (index into [trackButtons]), -1 for none. */
    var trackFocus by mutableIntStateOf(-1)

    /** The buttons above the timeline: chapters (menu tab 1), other titles (4), subtitles (3), audio (2). */
    fun trackButtons() = listOfNotNull(if (chapters.size > 1) 1 else null, 4, if (subtitleTracks.isNotEmpty() || canSearchSubtitles) 3 else null, 2)
    /** A film or episode from the library (not a typed link): its subtitle list ends with "Search subtitles". */
    private val canSearchSubtitles get() = now?.file != null
    /**
     * OK on the subtitle, audio or titles button pops its list up over the button (2 audio, 3 subtitles,
     * 4 other titles; -1 closed). Up and down move [popupFocus], OK picks, Back closes. Chapters open the full menu.
     */
    var trackPopup by mutableIntStateOf(-1)
    var popupFocus by mutableIntStateOf(0)
    /** The titles list (key to label), taken when it opens: see [quickList]. */
    private var quickItems = emptyList<Pair<String, String>>()

    /** Titles to jump to without leaving the player: this one, then the Watching row, then the newest films. */
    private fun quickList(): List<Pair<String, String>> {
        val cur = now?.key
        val newest = lib.data.value.movies.filter { it.catalogued }.sortedByDescending { it.file.added }.take(20).map { Item.MovieItem(it) }
        val others = (lib.continueWatching() + newest).mapNotNull { i ->
            when (i) {
                is Item.MovieItem -> i.movie.id to i.movie.title
                is Item.EpisodeItem -> i.episode.id to "${i.show.title}  ·  ${episodeLine(i.episode)}"
                else -> null
            }
        }.distinctBy { it.first }.filter { it.first != cur }
        return listOfNotNull(cur?.let { it to now?.title.orEmpty() }) + others.take(19)
    }

    /** The open pop-up's rows: label and whether it's the track playing. Subtitles start with None. */
    fun popupRows(): List<Pair<String, Boolean>> = when (trackPopup) {
        2 -> audioTracks.map { it.label to (it.id == audioTrack) }
        3 -> listOf(getString(R.string.none_choice) to (subtitleTrack == -1)) + subtitleTracks.map { it.label to (it.id == subtitleTrack) } +
            listOfNotNull(if (canSearchSubtitles) getString(R.string.search_subtitles) to false else null)
        4 -> quickItems.map { it.second to (it.first == now?.key) }
        // OpenSubtitles results, or one line saying what's happening (searching, downloading, an error).
        5 -> osStatus?.let { listOf(it to false) } ?: osHits.map { h ->
            listOfNotNull(
                langName(h.language), h.release.take(70),
                h.fps.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.3f fps", it).replace(".000 ", " ") },
                if (h.hearingImpaired) "SDH" else null, "↓ ${h.downloads}",
            ).joinToString("  ·  ") to false
        }
        else -> emptyList()
    }

    /**
     * A subtitle result split for its row: language and release name (cut short on one line), then frame rate,
     * SDH and downloads, always whole at the right. Null while a status line shows instead of results.
     */
    fun osRow(i: Int): Pair<String, String>? {
        if (trackPopup != 5 || osStatus != null) return null
        val h = osHits.getOrNull(i) ?: return null
        return "${langName(h.language)}  ·  ${h.release}" to listOfNotNull(
            h.fps.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.3f fps", it).replace(".000 ", " ") },
            if (h.hearingImpaired) "SDH" else null, "↓ ${h.downloads}",
        ).joinToString("  ·  ")
    }

    /** "Search subtitles": the results (pop-up 5) and a line shown instead of them while busy or after an error. */
    private var osHits by mutableStateOf<List<OpenSubtitles.Hit>>(emptyList())
    private var osStatus by mutableStateOf<String?>(null)

    private fun searchSubtitles() {
        osHits = emptyList()
        if (!OpenSubtitles.configured) { osStatus = getString(R.string.os_not_set_up); trackPopup = 5; popupFocus = 0; return }
        osStatus = getString(R.string.searching); trackPopup = 5; popupFocus = 0
        val key = now?.key ?: return
        val movie = lib.findMovie(key)
        val ep = lib.findEpisode(key)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    // By name, as the video's file gives it (usually the original title; the library's may be
                    // translated): the film's with its year, or the series' with the episode's season and number.
                    val parsed = now?.file?.let { Names.parse(it.path) }
                    val name = (parsed as? Parsed.MovieName)?.title ?: (parsed as? Parsed.EpisodeName)?.show
                        ?: movie?.title ?: ep?.first?.title ?: now?.title.orEmpty()
                    val year = (parsed as? Parsed.MovieName)?.year?.ifBlank { null } ?: movie?.year.orEmpty()
                    OpenSubtitles.search(name, year, ep?.second?.season ?: 0, ep?.second?.number ?: 0, ep?.first?.tmdbId ?: movie?.tmdbId ?: 0)
                }
            }
            if (trackPopup != 5) return@launch
            r.onSuccess { osHits = it; osStatus = if (it.isEmpty()) getString(R.string.os_none_found) else null }
                .onFailure { osStatus = it.message ?: it.javaClass.simpleName }
        }
    }

    /** Downloads the picked subtitle, keeps it with this title for next time and switches to it. */
    private fun downloadSubtitle(h: OpenSubtitles.Hit) {
        val key = now?.key ?: return
        osStatus = getString(R.string.os_downloading); popupFocus = 0
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = savedSubtitleDir(key).apply { mkdirs() }
                    val name = h.release.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)
                    // Numbered after the highest kept, so a removed one's number isn't handed out again over a kept one.
                    val n = dir.list().orEmpty().mapNotNull { it.substringBefore('-').toIntOrNull() }.maxOrNull()?.plus(1) ?: 0
                    File(dir, "$n-$name.${h.language}.srt").also { it.writeText(decodeSubtitle(OpenSubtitles.download(h.fileId)), Charsets.UTF_8) }
                }
            }
            r.onSuccess { f ->
                trackPopup = -1
                scheduleHide()
                addSubtitle(f)
                android.widget.Toast.makeText(this@PlayerActivity, getString(R.string.os_added, langName(h.language)), android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure { osStatus = it.message ?: it.javaClass.simpleName }
        }
    }

    /** Where subtitles downloaded for a title are kept (app storage, so they come back with the film next time). */
    private fun savedSubtitleDir(key: String) = File(filesDir, "opensubtitles/" + java.security.MessageDigest.getInstance("MD5")
        .digest(key.toByteArray()).joinToString("") { "%02x".format(it) })

    /** Adds a subtitle file to the film playing and selects it. */
    private fun addSubtitle(f: File) {
        val copy = File(File(cacheDir, "subs").apply { mkdirs() }, "${subtitleFiles.size}os-${f.name.substringAfter('-')}").also { f.copyTo(it, overwrite = true) }
        subtitleFiles = subtitleFiles + copy
        externalSubs = subtitleFiles.size
        val e = exo
        if (e != null) {
            // Media3 takes its subtitle files when the film opens: reopen it where it is, now with this one, and pick it.
            val (src, share, path) = playingFrom ?: return
            pickSubtitle = LAST
            keepAudio = audioTracks.indexOfFirst { it.id == audioTrack }
            e.load(src, share, path, subtitleFiles, subtitleFiles.map { subtitleLabel(it) }, now?.file?.dubs.orEmpty(), position, subtitleFiles.map { sidecarCode(it) })
        } else {
            player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(copy), true)
        }
    }

    /**
     * Set when the film reopens for a subtitle added or removed: the place in the subtitle list to select once its
     * tracks are known ([LAST]: the one just added; -1: none).
     */
    private var pickSubtitle: Int? = null

    /** The downloaded subtitle at this row of the subtitle list (row 0 is "None") that the viewer asked to remove, or null. */
    var removingSubtitle by mutableStateOf<Int?>(null)

    /** The added subtitle file a track plays, or null for the film's own. */
    private fun subtitleFileOf(id: Int): File? = exo?.let { e ->
        e.format(id)?.id?.let { f -> SIDECAR_ID.find(f) }?.groupValues?.get(1)?.toIntOrNull()?.let(subtitleFiles::getOrNull)
    } ?: spuIds.indexOf(id).takeIf { it >= embeddedSubs }?.let { subtitleFiles.getOrNull(it - embeddedSubs) }

    /**
     * Removes a downloaded subtitle from this title for good (it isn't brought back next time) and reopens the
     * film where it is without it; the subtitle that was on stays on, or none if it was this one.
     */
    fun removeSubtitle(row: Int) {
        removingSubtitle = null
        val t = subtitleTracks.getOrNull(row - 1)?.takeIf { subtitleKinds[it.id] == SubtitleKind.Downloaded } ?: return
        val key = now?.key ?: return
        val file = subtitleFileOf(t.id) ?: return
        val on = subtitleTracks.indexOfFirst { it.id == subtitleTrack }
        pickSubtitle = when { on < 0 || on == row - 1 -> -1; on > row - 1 -> on - 1; else -> on }
        keepAudio = audioTracks.indexOfFirst { it.id == audioTrack }
        savedSubtitleDir(key).listFiles()?.firstOrNull { it.name.substringAfter('-') == file.name.substringAfter('-') }?.delete()
        file.delete()
        subtitleFiles = subtitleFiles - file
        externalSubs = subtitleFiles.size
        val e = exo
        if (e != null) {
            val (src, share, path) = playingFrom ?: return
            e.load(src, share, path, subtitleFiles, subtitleFiles.map { subtitleLabel(it) }, now?.file?.dubs.orEmpty(), position, subtitleFiles.map { sidecarCode(it) })
        } else reopenVlc()     // libVLC can't drop a subtitle file it was given; a fresh one opens without it
    }
    /**
     * The place in the audio list of the track that was playing when Media3 reopened for a subtitle (-1: none), so
     * the same one plays on; otherwise Media3 picks its own, by the TV's language, and the sound changes.
     */
    private var keepAudio = -1
    /** Where the film playing comes from, for reopening it in Media3. */
    private var playingFrom: Triple<Source, String, String>? = null

    private fun langName(code: String) = Locale.forLanguageTag(code.substringBefore('-')).getDisplayLanguage(Locale.getDefault())
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifBlank { code }

    private fun openPopup(tab: Int) {
        if (tab == 4) quickItems = quickList() else refreshTracks()
        trackPopup = tab
        popupFocus = popupRows().indexOfFirst { it.second }.coerceAtLeast(0)
    }

    private fun pickPopup() {
        val i = popupFocus
        when (trackPopup) {
            2 -> audioTracks.getOrNull(i)?.let { chooseAudio(it.id) }
            3 -> when {
                i == 0 -> chooseSubtitle(-1)
                i > subtitleTracks.size -> { searchSubtitles(); return }
                else -> subtitleTracks.getOrNull(i - 1)?.let { chooseSubtitle(it.id) }
            }
            // A result downloads (the pop-up shows progress, then closes); a status line just closes.
            5 -> if (osStatus == null) { osHits.getOrNull(i)?.let { downloadSubtitle(it) }; return }
            // Another title: this one's place is kept, the new one starts where it was left.
            4 -> quickItems.getOrNull(i)?.first?.takeIf { it != now?.key }?.let { key ->
                saveProgress(force = true)
                trackPopup = -1; trackFocus = -1
                load(key, fromStart = false)
                return
            }
        }
        trackPopup = -1
    }
    var seekTarget by mutableStateOf<Long?>(null)
    var error by mutableStateOf<String?>(null)
    var upNext by mutableStateOf<Pair<Show, Episode>?>(null)
    var upNextSeconds by mutableIntStateOf(0)
    var audioTracks by mutableStateOf<List<TrackOption>>(emptyList())
    var subtitleTracks by mutableStateOf<List<TrackOption>>(emptyList())
    var audioTrack by mutableIntStateOf(-1)
    var subtitleTrack by mutableIntStateOf(-1)
    var subtitleDelay by mutableLongStateOf(0L)   // ms
    var audioDelay by mutableLongStateOf(0L)      // ms
    var rate by mutableFloatStateOf(1f)
    var scale by mutableIntStateOf(5)              // picture size, index into the menu's choices; starts on "Original"
    var chapters by mutableStateOf<List<Pair<String, Long>>>(emptyList())
    private var exoChapters: List<Pair<String, Long>> = emptyList()   // read from the file for the Dolby Vision player
    var techInfo by mutableStateOf<List<Pair<String, String>>>(emptyList())

    private var queue: List<String> = emptyList()
    private var hideJob: Job? = null
    private var decoderLost = false
    private var decoderRetries = 0
    private var seekJob: Job? = null
    private var tickJob: Job? = null
    private var upNextJob: Job? = null
    private var tracksApplied = false
    // Picked by the viewer in the menu (or kept from an earlier pick): only these are remembered for the file; the
    // rest follows the language settings each time.
    private var audioChosen = false
    private var subtitleChosen = false
    private var externalSubs = 0
    private var subtitleFiles: List<File> = emptyList()   // our added subtitle files, in the order added
    private var dubLabels: List<String> = emptyList()     // names for the dub audio tracks, in the order added
    private var modeSwitched = false
    /** Paused on purpose, not waiting for the film to load: the controls may leave the picture clean. */
    private var paused = false
    private var declaredFps: Double? = null
    private var modeResume: Job? = null
    private var lastSaved = 0L
    private var resumeAt = 0L   // where this play started; saves before playback reaches it keep the old place

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // A film that played DTS last time is opened decoding it at once (see [matchPassthrough]).
        intent.getStringExtra(EXTRA_KEY)?.let { dtsDecoded = lib.settings.passthrough && lib.settings.decodesDts(it) }
        createVlc()

        videoLayout = VLCVideoLayout(this)
        val overlay = ComposeView(this).apply { setContent { TvScale { PlayerOverlay(this@PlayerActivity) } } }
        root = FrameLayout(this)
        root.addView(videoLayout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        val key = intent.getStringExtra(EXTRA_KEY) ?: return finish()
        queue = intent.getStringArrayListExtra(EXTRA_QUEUE).orEmpty()
        load(key, intent.getBooleanExtra(EXTRA_FROM_START, false))
    }

    /** libVLC with the viewer's settings (buffering, subtitle style, audio); it reads them only here. */
    /**
     * Passthrough sends Dolby to the TV as it is, but this TV takes a DTS stream and plays it silent. So while the
     * track playing is DTS, libVLC is made without passthrough and decodes it; see [matchPassthrough].
     */
    private var dtsDecoded = false

    private fun createVlc() {
        val s = lib.settings
        val options = arrayListOf(
            "--network-caching=${s.bufferMs}",
            "--audio-time-stretch",
            "--avcodec-skiploopfilter=1",          // cheaper software H.264 on weak ARM boxes
            "--sub-text-scale=${s.subtitleScale}",
            "--freetype-color=${s.subtitleColor}",
            "--freetype-background-opacity=${s.subtitleBackground}",
            "--freetype-background-color=0",
            "--freetype-outline-thickness=${s.subtitleOutline}",
            if (s.subtitleBold) "--freetype-bold" else "--no-freetype-bold",
            "--sub-margin=${s.subtitlePosition}",
            if (s.passthrough && !dtsDecoded) "--spdif" else "--no-spdif",
            "--gain=${s.volumeBoost / 100f}",
            "--deinterlace=${when (s.deinterlace) { 1 -> 1; 2 -> 0; else -> -1 }}",
            "--deinterlace-mode=yadif",
            "--no-sub-autodetect-file",            // sidecar subtitles are added by us, converted to UTF-8
            // Read-ahead: keep pulling the file in big chunks while it plays, so busy scenes that
            // briefly need more than the network gives are covered by what's already downloaded.
            // Streaming cache: Auto reads far ahead, Limited and Memory only less, Legacy not at all.
            "--prefetch-buffer-size=${when (s.cacheMode) { 1 -> 96; 2 -> 48; 3 -> 1; else -> s.readAheadMb } * 1024}",
            "--prefetch-read-size=${if (s.cacheMode == 3) 16384 else 1 shl 20}",
        )
        if (s.normalizeDownmix) options += listOf("--audio-filter=normvol", "--norm-max-level=2.0")
        vlc = LibVLC(this, options)
        player = MediaPlayer(vlc)
        player.setEventListener(::onVlcEvent)
    }

    override fun onStart() {
        super.onStart()
        lib.playerShown(true)
        player.attachViews(videoLayout, null, true, false)
        player.videoScale = vlcScale(scale)   // keep the viewer's choice when coming back
        if (decoderLost) { decoderLost = false; exo?.player?.prepare() }
        if (exo?.isDolbyVision() == true) announceDolbyVision(true)
        if (paused) showControls()
    }

    override fun onStop() {
        super.onStop()
        lib.playerShown(false)
        saveProgress(force = true)
        lib.flush()
        modeResume?.cancel()
        // Only on leaving the film: the remote's app buttons pass through other windows while it stays on screen.
        if (isFinishing) announceDolbyVision(false, changed = true)
        if (player.isPlaying) player.pause()
        exo?.player?.pause()
        player.detachViews()
    }

    override fun onDestroy() {
        super.onDestroy()
        tickJob?.cancel()
        player.setEventListener(null)
        player.stop()
        player.release()
        vlc.release()
        exo?.release()
    }

    /** Switches between libVLC and Media3 for the next film, swapping the video surface. */
    private fun useExo(on: Boolean) {
        if (on && exo == null) {
            if (player.isPlaying || player.media != null) player.stop()
            exo = ExoPlayback(this).also { it.scale(scale); root.addView(it.view, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
            videoLayout.visibility = android.view.View.GONE
        } else if (!on && exo != null) {
            announceDolbyVision(false)
            exo?.let { root.removeView(it.view); it.release() }
            exo = null
            videoLayout.visibility = android.view.View.VISIBLE
        }
    }

    // ---- engine-neutral controls ----

    private fun isPlayingNow() = exo?.player?.isPlaying ?: player.isPlaying
    private fun pausePlayback() { exo?.player?.pause() ?: player.pause() }
    private fun resumePlayback() { paused = false; exo?.player?.play() ?: player.play() }
    private fun seekPlayback(ms: Long) { exo?.player?.seekTo(ms) ?: run { player.time = ms } }
    private fun nextChapter() { if (exo == null) player.nextChapter() }
    private fun previousChapter() { if (exo == null) player.previousChapter() }

    /**
     * Says whether a Dolby Vision picture is on screen, for other apps on the TV: Android has no way to ask, and
     * picture settings can depend on it. Carries nothing but this yes or no, and [changed] when a film's picture has
     * just appeared or gone.
     */
    internal fun announceDolbyVision(on: Boolean, changed: Boolean = false) {
        sendBroadcast(android.content.Intent("com.glint.player.action.VIDEO_FORMAT").putExtra("dolbyVision", on).putExtra("changed", changed))
    }

    // ---- Media3 events (the libVLC ones are in onVlcEvent) ----

    /**
     * Another app took the TV's video decoder (a second film, usually while this one was in the background). The file
     * is fine: Media3 picks up at the same spot once prepared again, which waits until the viewer is back. A decoder
     * still held elsewhere gets a few tries a second apart. False once those are used up, to show the error after all.
     */
    internal fun decoderTaken(): Boolean {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) { decoderLost = true; return true }
        if (decoderRetries >= 3) return false
        decoderRetries++
        lifecycleScope.launch { delay(1000); exo?.player?.prepare() }
        return true
    }

    internal fun enginePlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            decoderRetries = 0
            paused = false
            playing = true; buffering = 100f
            if (controls) scheduleHide()
        } else if (exo?.player?.playbackState != androidx.media3.common.Player.STATE_ENDED) {
            playing = false
            if (exo?.player?.playWhenReady == false) { paused = true; showControls(); saveProgress(force = true) }
        }
    }

    internal fun engineEnded() = onEnded()

    internal fun engineTracksChanged() {
        refreshTracks()
        if (!tracksApplied && (audioTracks.isNotEmpty() || subtitleTracks.isNotEmpty())) {
            applyTrackPreferences()
            lib.settings.defaultSpeed.takeIf { it != 100 }?.let { chooseRate(it / 100f) }
        }
    }

    // ---- loading ----

    fun load(key: String, fromStart: Boolean) {
        upNextJob?.cancel(); upNext = null
        error = null; tracksApplied = false; audioChosen = false; subtitleChosen = false; modeSwitched = false; declaredFps = null; modeResume?.cancel(); paused = false
        hud = lib.settings.playbackStats; sampledAt = 0; flowMbps = 0.0; peakMbps = 0.0; netMbps = 0.0; flowSamples = 0; shownFps = 0.0; sampledShown = 0; shownWindow.clear()
        muxer = null; muxerAsked = false
        position = 0; duration = 0; resumeAt = 0; chapters = emptyList(); exoChapters = emptyList(); techInfo = emptyList()
        subtitleDelay = 0; audioDelay = 0; rate = 1f
        markedIntro = lib.introOf(key); introStartMark = null; skippedMarked = false; skippedChapter = -1
        if (key.startsWith("url|")) { loadUrl(key, key.removePrefix("url|")); return }
        val parts = key.split('|', limit = 3)
        val src = lib.source(parts.getOrNull(0) ?: "")
        if (src == null || parts.size < 3) { error = getString(R.string.source_missing); return }
        val share = parts[1]
        val path = parts[2]
        playingFrom = Triple(src, share, path)
        now = describe(key, path)
        showControls()

        lifecycleScope.launch {
            // Sidecar subtitles: fetched and converted to UTF-8 (Turkish .srt files are often Windows-1254).
            val dubs = now?.file?.dubs.orEmpty()
            val subs = withContext(Dispatchers.IO) { runCatching { fetchSubtitles(src, share, path, dubs) }.getOrDefault(emptyList()) } +
                // Subtitles downloaded for this title before (Search subtitles), after the ones on the NAS.
                savedSubtitleDir(key).listFiles().orEmpty().sortedBy { it.name }.mapIndexed { i, f ->
                    File(File(cacheDir, "subs"), "${100 + i}os-${f.name.substringAfter('-')}").also { f.copyTo(it, overwrite = true) }
                }
            externalSubs = subs.size
            subtitleFiles = subs
            dubLabels = dubs.map { getString(R.string.dub_track, Dubs.label(it, path.substringAfterLast('/'))) }
            val saved = lib.progressOf(key)
            val start = if (!fromStart && saved != null && saved.inProgress && saved.position < saved.duration * 0.97) saved.position else 0L
            resumeAt = start
            useExo(DolbyVision.shouldUse(this@PlayerActivity, src, now?.file))
            exo?.let { e ->
                e.load(src, share, path, subs, subs.map { subtitleLabel(it) }, dubs, start, subs.map { sidecarCode(it) })
                startTicker()
                // Media3 doesn't give MKV chapters; read them from the file for the chapter list and skipping.
                exoChapters = withContext(Dispatchers.IO) { runCatching { MkvChapters.read(src, share, path) }.getOrDefault(emptyList()) }
                chapters = exoChapters
                return@launch
            }
            // A .strm file is a link to a video elsewhere; play what it points at. A DVD folder plays its
            // film's VOB pieces back to back as one stream; a Blu-ray folder its main .m2ts.
            val parts = now?.file?.parts.orEmpty()
            val url = now?.file?.strmUrl ?: when {
                parts.size > 1 -> "concat://"
                parts.size == 1 -> Fs.vlcUrl(src, share, parts[0])
                else -> Fs.vlcUrl(src, share, path)
            }
            val media = Media(vlc, Uri.parse(url))
            if (parts.size > 1) media.addOption(":concat-list=" + parts.joinToString(",") { Fs.vlcUrl(src, share, it).replace(",", "%2C") })
            // Other protocols carry the login in the URL itself.
            if (src.type == "smb" && src.user.isNotBlank()) {
                media.addOption(":smb-user=${src.user}")
                media.addOption(":smb-pwd=${src.password}")
                if (src.domain.isNotBlank()) media.addOption(":smb-domain=${src.domain}")
            }
            media.addOption(":network-caching=${lib.settings.bufferMs}")
            media.setHWDecoderEnabled(true, false)
            if (start > 0) media.addOption(":start-time=${start / 1000.0}")
            for (f in subs) media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, Uri.fromFile(f).toString()))
            // Dubs play from the NAS alongside the film, like a second audio stream inside the file.
            for (d in dubs) {
                val dubSource = lib.source(d.source) ?: continue
                media.addSlave(IMedia.Slave(IMedia.Slave.Type.Audio, 4, Fs.vlcUrl(dubSource, d.share, d.path)))
            }
            player.media = media
            media.release()
            player.play()
            startTicker()
        }
    }

    /** Direct URL playback (http, https, rtsp, smb…). */
    private fun loadUrl(key: String, url: String) {
        useExo(false)
        now = NowPlaying(key, Uri.parse(url).lastPathSegment ?: url, url, null)
        showControls()
        val media = Media(vlc, Uri.parse(url))
        media.addOption(":network-caching=${lib.settings.bufferMs}")
        media.setHWDecoderEnabled(true, false)
        player.media = media
        media.release()
        player.play()
        startTicker()
    }

    private fun describe(key: String, path: String): NowPlaying {
        lib.findMovie(key)?.let { m -> return NowPlaying(key, m.title, listOf(m.year, badges(m.file).take(3).joinToString(" ")).filter { it.isNotBlank() }.joinToString("  ·  "), m.file, m.overview) }
        lib.findEpisode(key)?.let { (s, e) -> return NowPlaying(key, s.title, listOf(e.code, e.title).filter { it.isNotBlank() }.joinToString("  ·  "), e.file, e.overview) }
        return NowPlaying(key, path.substringAfterLast('/').substringBeforeLast('.'), "", null)
    }

    /** Sidecar subtitles next to the film, then any that came with its dubs; saved locally as UTF-8. */
    private fun fetchSubtitles(src: Source, share: String, path: String, dubs: List<DubFile>): List<File> {
        // The library's scan found the files beside the video by the same rule; listing the folder again (a
        // share's top folder can hold hundreds of films) held the start of playback by a second or more.
        val subs = (now?.file?.takeIf { it.path == path }?.subtitles ?: run {
            val dir = path.substringBeforeLast('/', "")
            val base = path.substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)
            val entries = Fs.list(src, share, dir)
            val videos = entries.count { !it.isDir && Names.ext(it.name) in Names.VIDEO_EXT }
            entries.filter { !it.isDir && Names.ext(it.name) in Names.SUB_EXT && (it.name.lowercase(Locale.ROOT).startsWith(base) || videos == 1) }.map { it.path }
        }).map { Triple(src, share, it) }
        val dubSubs = dubs.flatMap { d -> lib.source(d.source)?.let { s -> d.subtitles.map { Triple(s, d.share, it) } }.orEmpty() }.distinct()
        val out = File(cacheDir, "subs").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        return (subs + dubSubs).mapIndexed { i, (s, sh, p) ->
            val bytes = Fs.readBytes(s, sh, p, limit = 8 shl 20)
            val prefix = if (i >= subs.size) "dub" else ""
            val f = File(out, "$i$prefix-${p.substringAfterLast('/')}")
            f.writeText(decodeSubtitle(bytes), Charsets.UTF_8)
            f
        }
    }

    private fun decodeSubtitle(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE)
        val strict = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        runCatching { return strict.decode(ByteBuffer.wrap(bytes)).toString() }
        val legacy = when (AppLanguage.system.language) {
            "tr" -> "windows-1254"
            "ru", "uk", "bg", "sr" -> "windows-1251"
            "el" -> "windows-1253"
            "pl", "cs", "hu", "ro", "hr" -> "windows-1250"
            else -> "windows-1252"
        }
        return String(bytes, Charset.forName(legacy))
    }

    // ---- libVLC events ----

    private fun onVlcEvent(e: MediaPlayer.Event) {
        when (e.type) {
            MediaPlayer.Event.Playing -> {
                playing = true; buffering = 100f
                if (controls) scheduleHide()
                // By the time playback starts every stream is known, so language choices can be made.
                if (!tracksApplied) {
                    refreshTracks(); applyTrackPreferences()
                    lib.settings.defaultSpeed.takeIf { it != 100 }?.let { chooseRate(it / 100f) }
                    matchPassthrough()
                }
            }
            MediaPlayer.Event.Paused -> { playing = false; paused = true; showControls(); saveProgress(force = true) }
            MediaPlayer.Event.Buffering -> buffering = e.buffering
            MediaPlayer.Event.LengthChanged -> duration = e.lengthChanged
            MediaPlayer.Event.TimeChanged -> {
                position = e.timeChanged
                reopenAt?.let { at -> if (playing && position >= at && !revealing) { revealing = true; resyncPassthrough { reopenAt = null; revealing = false } } }
                if (!modeSwitched && position > 0) onVideoOut()
                checkIntro()
            }
            // Streams are known before the first frame: match the TV to the film's frame rate right away.
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> { refreshTracks(); if (!modeSwitched) onVideoOut() }
            // A new surface (back from the background, a reopen) doesn't know the rate declared on the last one.
            MediaPlayer.Event.Vout -> if (e.voutCount > 0) { onVideoOut(); declareFrameRate(); announceDolbyVision(false, changed = true) }
            MediaPlayer.Event.EndReached -> onEnded()
            MediaPlayer.Event.EncounteredError -> playbackFailed(null)
        }
    }

    /**
     * Playback stopped on an error. Looks for the file first: a disk unplugged or a NAS switched off is something to
     * fix at the source, not a fault of the file. The check gets a few seconds, as a NAS that's off may not answer at all.
     */
    fun playbackFailed(code: String?) {
        val failed = getString(R.string.play_failed) + if (code != null) "  ($code)" else ""
        val from = playingFrom ?: run { error = failed; return }
        lifecycleScope.launch {
            val (src, share, path) = from
            val check = async(Dispatchers.IO) {
                runCatching { Fs.list(src, share, path.substringBeforeLast('/', "")).any { it.name == path.substringAfterLast('/') } }.getOrDefault(false)
            }
            val there = kotlinx.coroutines.withTimeoutOrNull(6_000) { check.await() } == true
            if (playingFrom === from) error = if (there) failed else getString(R.string.source_unreachable, src.name)
        }
    }

    private fun subtitleLabel(f: File) = when {
        isDubSubtitle(f) -> getString(if (isForced(f)) R.string.dub_subtitle_forced else R.string.dub_subtitle)
        else -> getString(R.string.external_subtitle, sidecarCode(f)?.let(::languageOf) ?: f.name.substringAfter('-').substringBeforeLast('.'))
    }

    /** The language code an added subtitle file's name gives ("Film.2013.tr.srt" → "tr"), or null; see [sidecarLanguageCode]. */
    private fun sidecarCode(f: File): String? =
        sidecarLanguageCode(f.name.substringAfter('-'), playingFrom?.third?.substringAfterLast('/')?.substringBeforeLast('.'))

    private fun refreshTracks() {
        exo?.let { e ->
            audioTracks = e.audioOptions(dubLabels)
            dubIds = e.dubIds()
            subtitleTracks = e.subtitleOptions()
            spuIds = subtitleTracks.map { it.id }
            subtitleKinds = subtitleTracks.mapNotNull { o ->
                e.format(o.id)?.id?.let { f -> SIDECAR_ID.find(f) }?.groupValues?.get(1)?.toIntOrNull()?.let(subtitleFiles::getOrNull)?.let { o.id to kindOf(it) }
            }.toMap()
            audioTrack = e.selected(androidx.media3.common.C.TRACK_TYPE_AUDIO)
            subtitleTrack = e.selected(androidx.media3.common.C.TRACK_TYPE_TEXT)
            val pick = pickSubtitle
            if (pick != null && (subtitleTracks.isNotEmpty() || (pick == -1 && audioTracks.isNotEmpty()))) {
                pickSubtitle = null
                audioTracks.getOrNull(keepAudio)?.let { if (it.id != audioTrack) chooseAudio(it.id, byViewer = audioChosen) }
                keepAudio = -1
                chooseSubtitle(if (pick == LAST) subtitleTracks.last().id else subtitleTracks.getOrNull(pick)?.id ?: -1)
            }
            chapters = exoChapters
            return
        }
        val info = mediaTracks().associateBy { it.id }
        val audio = player.audioTracks?.filter { it.id != -1 }.orEmpty()
        val embeddedAudio = (audio.size - dubLabels.size).coerceAtLeast(0)
        audioTracks = audio.mapIndexed { i, t -> TrackOption(t.id, dubLabels.getOrNull(i - embeddedAudio)?.takeIf { i >= embeddedAudio } ?: trackLabel(info[t.id]) ?: t.name) }
        dubIds = audio.drop(embeddedAudio).map { it.id }
        val spu = player.spuTracks?.filter { it.id != -1 }.orEmpty()
        val embedded = spu.size - externalSubs
        subtitleTracks = spu.mapIndexed { i, t ->
            val file = subtitleFiles.getOrNull(i - embedded)?.takeIf { i >= embedded }
            val label = when {
                file == null -> trackLabel(info[t.id]) ?: t.name
                else -> subtitleLabel(file)
            }
            TrackOption(t.id, label)
        }
        subtitleKinds = spu.mapIndexedNotNull { i, t -> subtitleFiles.getOrNull(i - embedded)?.takeIf { i >= embedded }?.let { t.id to kindOf(it) } }.toMap()
        // A restyle reopening the film: libVLC picks its own tracks while the streams come back, before the
        // viewer's are put back; the menu keeps showing the viewer's meanwhile, so its tick doesn't wander.
        if (reopenAt == null || tracksApplied) {
            audioTrack = player.audioTrack
            subtitleTrack = player.spuTrack
        }
        chapters = player.getChapters(-1)?.map { it.name.orEmpty() to it.timeOffset }.orEmpty()
        spuIds = spu.map { it.id }
        embeddedSubs = embedded
    }

    /** Where each subtitle track that isn't in the film comes from, by track id: a file beside it, or a download. */
    private var subtitleKinds: Map<Int, SubtitleKind> = emptyMap()

    /** Downloads are kept as "<n>os-<name>" (see [addSubtitle]); everything else came with the film from the NAS or USB. */
    private fun kindOf(f: File) = if (OS_FILE.containsMatchIn(f.name)) SubtitleKind.Downloaded else SubtitleKind.Beside

    /** How the subtitle at a row of a subtitle list came (row 0 is "None"); nothing for the film's own. */
    fun subtitleKind(row: Int): SubtitleKind? = subtitleTracks.getOrNull(row - 1)?.let { subtitleKinds[it.id] }

    /** The icon for a row of the open pop-up: in the subtitle list, how a track came. */
    fun popupKind(row: Int): SubtitleKind? = if (trackPopup == 3) subtitleKind(row) else null

    private var spuIds: List<Int> = emptyList()
    private var dubIds: List<Int> = emptyList()
    private var embeddedSubs = 0

    /**
     * Track names from the file's own details: "English - TrueHD 7.1 (Atmos)" for audio,
     * "English (SDH)" for subtitles; null when the file says nothing useful.
     */
    private fun trackLabel(t: IMedia.Track?): String? {
        t ?: return null
        val lang = languageOf(t.language)
        val desc = t.description?.trim().orEmpty()
        if (t is IMedia.AudioTrack) {
            // libVLC gives either a short code ("mlp ") or the codec's name ("DTS Audio").
            val c = listOfNotNull(t.codec, t.originalCodec).joinToString(" ").lowercase(Locale.ROOT)
            val d = desc.lowercase(Locale.ROOT)
            val codec = when {
                "truehd" in c || "mlp" in c || "trhd" in c -> "TrueHD"
                "eac3" in c || "e-ac3" in c || "e-ac-3" in c || "ec-3" in c || "enhanced" in c -> "Dolby Digital+"
                "a52" in c || "ac3" in c || "ac-3" in c || "a/52" in c -> "Dolby Digital"
                "dts" in c -> if ("dts-x" in d || "dts:x" in d) "DTS:X" else if ("hd" in d || " ma" in d || "master" in d) "DTS-HD MA" else "DTS"
                "aac" in c || "mp4a" in c -> "AAC"
                "flac" in c -> "FLAC"; "opus" in c -> "Opus"; "vorbis" in c -> "Vorbis"
                "mpga" in c || "mpeg audio" in c || "mp3" in c -> "MP3"
                "pcm" in c || "araw" in c || "s16l" in c || "s24l" in c -> "PCM"
                else -> t.codec?.trim().orEmpty()
            }
            val ch = when (t.channels) { 8 -> "7.1"; 7 -> "6.1"; 6 -> "5.1"; 2 -> "2.0"; 1 -> "1.0"; else -> "" }
            val head = listOf(lang, listOf(codec, ch).filter { it.isNotBlank() }.joinToString(" ")).filter { it.isNotBlank() }.joinToString(" - ")
            return (head + if (desc.isNotBlank() && !head.contains(desc, true)) " ($desc)" else "").ifBlank { null }
        }
        if (lang.isBlank()) return desc.ifBlank { null }
        return lang + if (desc.isNotBlank() && !desc.equals(lang, true)) " ($desc)" else ""
    }

    /** "eng"/"en"/"ger" → the language's name in the viewer's language. */
    private fun languageOf(code: String?): String {
        val c = code?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (c.isEmpty() || c == "und") return ""
        val two = BIBLIOGRAPHIC[c] ?: if (c.length == 3) Locale.getAvailableLocales().firstOrNull { runCatching { it.isO3Language }.getOrNull() == c }?.language ?: c else c
        return Locale(two).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifBlank { c }
    }

    /** Track details from the media; getMedia() hands out a retained reference that must be released. */
    private fun mediaTracks(): List<IMedia.Track> {
        val m = player.media ?: return emptyList()
        return try {
            (0 until m.trackCount).mapNotNull { m.getTrack(it) }
        } finally {
            m.release()
        }
    }

    fun openPanel(tab: Int? = null) {
        if (tab != null) panelTab = tab
        refreshTracks()
        techInfo = buildTechInfo()
        panel = true
        controls = true
        hideJob?.cancel()
    }

    /** Picks audio and subtitles: the viewer's last choice for this file, else the language settings. */
    private fun applyTrackPreferences() {
        val n = now ?: return
        if (audioTracks.isEmpty() && subtitleTracks.isEmpty()) return
        exo?.let { applyExoPreferences(it, n); return }
        tracksApplied = true
        val embedded = embeddedSubs
        val saved = lib.progressOf(n.key)
        val info = mediaTracks().associateBy { it.id }
        // Language from the stream's tag, or failing that from the name libVLC gives the track ("Türkçe", "[English]").
        fun speaks(t: TrackInfo, lang: String): Boolean {
            if (lang.isEmpty() || t.sidecar || TrackRules.speaks(t, lang)) return TrackRules.speaks(t, lang)
            val name = (audioTracks + subtitleTracks).firstOrNull { it.id == t.id }?.label?.lowercase() ?: return false
            val l = Locale(lang)
            return listOf(l.getDisplayLanguage(Locale.ENGLISH), l.getDisplayLanguage(l), l.displayLanguage).any { it.isNotEmpty() && name.contains(it.lowercase()) }
        }

        val audio = audioTracks.map { TrackInfo(it.id, info[it.id]?.language, dub = it.id in dubIds) }
        val (audioChoice, subtitlePref) = TrackRules.audioFor(audio, saved?.audioTrack ?: -2, lib.settings.audioLanguage,
            lib.settings.subtitleLanguage, animationLanguages(n.key), ::speaks)
        audioChoice?.let { pick ->
            if (pick.id in dubIds && !pick.byViewer) chooseAudio(pick.id, byViewer = false) else player.audioTrack = pick.id
            if (pick.byViewer) audioChosen = true
        }

        fun forcedTrack(t: IMedia.Track) = (t.description ?: "").contains("forced", true) ||
            subtitleTracks.firstOrNull { it.id == t.id }?.label?.contains("forced", true) == true
        val subs = spuIds.mapIndexed { i, id ->
            val file = subtitleFiles.getOrNull(i - embedded)?.takeIf { i >= embedded }
            if (file != null) TrackInfo(id, sidecarCode(file), isForced(file), sidecar = true, dub = isDubSubtitle(file))
            else TrackInfo(id, info[id]?.language, info[id]?.let(::forcedTrack) == true)
        }
        val pref = subtitlePref
        val dubPlaying = player.audioTrack in dubIds
        // The dubs on the NAS are Turkish.
        val audioLang = if (dubPlaying) "tr" else info[player.audioTrack]?.language
        val sub = TrackRules.subtitle(subs, if (pref == "auto") AppLanguage.system.language else pref, audioLang, dubPlaying,
            lib.settings.forcedSubtitles, saved?.subtitleTrack ?: -2, ::speaks)
        player.spuTrack = sub.id
        if (sub.byViewer) subtitleChosen = true
        // Reopened without a removed subtitle: the one that was on, by its new place (the saved track id may have moved).
        pickSubtitle?.let { i -> pickSubtitle = null; player.spuTrack = subtitleTracks.getOrNull(i)?.id ?: -1; subtitleChosen = true }
        audioTrack = player.audioTrack
        subtitleTrack = player.spuTrack
    }

    /** An animated film or series: its own audio and subtitle settings; null for anything else. */
    private fun animationLanguages(key: String): Pair<String, String>? {
        val genres = lib.findMovie(key)?.genres ?: lib.findEpisode(key)?.first?.genres ?: return null
        return if (TrackRules.isAnimation(genres)) lib.settings.animationAudio to lib.settings.animationSubtitles else null
    }

    /** The same choices as for libVLC, made with Media3's track formats. */
    private fun applyExoPreferences(e: ExoPlayback, n: NowPlaying) {
        tracksApplied = true
        val audioType = androidx.media3.common.C.TRACK_TYPE_AUDIO
        val textType = androidx.media3.common.C.TRACK_TYPE_TEXT
        fun lang(id: Int) = e.format(id)?.language?.lowercase(Locale.ROOT)
        val saved = lib.progressOf(n.key)
        // The track picked here: Media3 reports a new selection only once the player has applied it, a moment later.
        val audio = audioTracks.map { TrackInfo(it.id, lang(it.id), dub = it.id in dubIds) }
        val (audioChoice, subtitlePref) = TrackRules.audioFor(audio, saved?.audioTrack ?: -2, lib.settings.audioLanguage,
            lib.settings.subtitleLanguage, animationLanguages(n.key))
        val audioPick = audioChoice?.also { pick ->
            if (pick.id in dubIds && !pick.byViewer) chooseAudio(pick.id, byViewer = false) else e.select(audioType, pick.id)
            if (pick.byViewer) audioChosen = true
        }?.id ?: e.selected(audioType)
        // Files next to the film carry the language their names give; "sub3" is the fourth file added.
        val subs = subtitleTracks.map {
            val file = e.format(it.id)?.id?.let { f -> SIDECAR_ID.find(f) }?.groupValues?.get(1)?.toIntOrNull()?.let(subtitleFiles::getOrNull)
            TrackInfo(it.id, lang(it.id), e.isForced(it.id), e.isSidecar(it.id), dub = file != null && isDubSubtitle(file))
        }
        val pref = subtitlePref
        val dubPlaying = audioPick in dubIds
        // The dubs on the NAS are Turkish.
        val audioLang = if (dubPlaying) "tr" else lang(audioPick)
        val sub = TrackRules.subtitle(subs, if (pref == "auto") AppLanguage.system.language else pref, audioLang, dubPlaying,
            lib.settings.forcedSubtitles, saved?.subtitleTrack ?: -2)
        e.select(textType, sub.id)
        if (sub.byViewer) subtitleChosen = true
        audioTrack = e.selected(audioType)
        subtitleTrack = e.selected(textType)
    }

    // The details box's live figures, from the last two samples a second apart.
    private var sampledAt = 0L
    private var sampledRx = 0L
    private var sampledVideo = 0L
    private var sampledShown = 0
    private var shownFps = 0.0
    private val shownWindow = ArrayDeque<Pair<Long, Int>>()
    private var flowMbps = 0.0
    private var peakMbps = 0.0
    private var netMbps = 0.0
    private var flowSamples = 0
    private var muxer: String? = null
    private var muxerAsked = false

    /** Who made the file, read once from its first bytes when the details box first needs it. */
    private fun askMuxer() {
        if (muxerAsked) return
        muxerAsked = true
        val (src, share, path) = playingFrom ?: return
        if (containerName(path) != containerName("x.mkv")) return
        lifecycleScope.launch(Dispatchers.IO) {
            val found = runCatching { matroskaMuxer(Fs.readBytes(src, share, path, 64 * 1024)) }.getOrNull()
            withContext(Dispatchers.Main) { if (playingFrom?.third == path) muxer = found }
        }
    }

    private fun vlcStats(): IMedia.Stats? {
        val media = player.media ?: return null
        return try { media.stats } finally { media.release() }
    }

    /** The film's live bitrate and the network's speed since the last call. */
    private fun sampleFlow() {
        val at = System.currentTimeMillis()
        val rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
        val video = exo?.videoBytes() ?: 0L
        val shown = exo?.framesShown() ?: vlcStats()?.displayedPictures ?: 0
        val seconds = (at - sampledAt) / 1000.0
        if (sampledAt > 0 && seconds > 0.2) {
            if (rx >= sampledRx && sampledRx >= 0) netMbps = (rx - sampledRx) * 8 / 1e6 / seconds
            flowMbps = if (exo != null) (video - sampledVideo).coerceAtLeast(0) * 8 / 1e6 / seconds
                // libVLC gives bytes per microsecond.
                else (vlcStats()?.demuxBitrate?.toDouble() ?: 0.0) * 8
            // Not the first seconds: the player reads far ahead of the film then.
            if (playing && ++flowSamples > 5) peakMbps = maxOf(peakMbps, flowMbps)
            // Over several seconds: the players count their frames in bursts, so one second's count jumps about.
            if (!playing || shown < sampledShown) shownWindow.clear()
            shownWindow.addLast(at to shown)
            if (shownWindow.size > 6) shownWindow.removeFirst()
            val (fromAt, fromShown) = shownWindow.first()
            shownFps = if (shownWindow.size >= 3) (shown - fromShown) * 1000.0 / (at - fromAt) else 0.0
        }
        sampledAt = at; sampledRx = rx; sampledVideo = video; sampledShown = shown
    }

    private fun buildTechInfo(): List<Pair<String, String>> {
        fun mbps(v: Double) = "%.1f".format(Locale.getDefault(), v)
        val e = exo
        val tracks = if (e == null) mediaTracks() else emptyList()
        val file = now?.file
        askMuxer()
        val video = e?.videoInfo() ?: tracks.filterIsInstance<IMedia.VideoTrack>().firstOrNull()?.let { t ->
            val fps = if (t.frameRateDen > 0) "%.3f fps".format(Locale.ROOT, t.frameRateNum.toDouble() / t.frameRateDen) else ""
            val codec = t.codec.orEmpty().let { c -> if ("HEVC" in c) "HEVC" else if ("H264" in c || "AVC" in c) "H.264" else c }
            val hdr = file?.let { f -> badges(f).firstOrNull { it in setOf("Dolby Vision", "HDR10+", "HDR") } }.orEmpty()
            listOf(codec, hdr, "${t.width}×${t.height}", fps).filter { it.isNotBlank() }.joinToString(" · ")
        }
        val audio = e?.audioInfo() ?: tracks.filterIsInstance<IMedia.AudioTrack>().firstOrNull { it.id == player.audioTrack }?.let { t ->
            val channels = when (t.channels) { 8 -> "7.1"; 6 -> "5.1"; 2 -> "2.0"; 1 -> "1.0"; else -> "${t.channels}ch" }
            listOf(t.language.orEmpty(), t.codec.orEmpty(), "${t.rate} Hz", channels).filter { it.isNotBlank() }.joinToString(" · ")
        }
        // File size over running time: bits per millisecond divided by 1000 is Mbps.
        val average = if (file != null && duration > 0) file.size * 8 / 1e3 / duration else 0.0
        val stats = if (e == null) vlcStats() else null
        // Read ahead of the playhead, as time on Media3; libVLC only knows the bytes, so time comes from the average.
        val ahead = stats?.let { (it.readBytes - it.demuxReadBytes).coerceAtLeast(0) }
        val bufferSeconds = e?.bufferedMs()?.div(1000.0) ?: ahead?.let { if (average > 0) it * 8 / 1e6 / average else null }
        @Suppress("DEPRECATION") val mode = windowManager.defaultDisplay.mode
        val src = playingFrom?.first
        val memory = android.app.ActivityManager.MemoryInfo().also { getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
        val disk = android.os.StatFs(filesDir.path)
        fun gb(n: Long) = "%.2f GB".format(Locale.getDefault(), n / 1e9)
        return buildList {
            file?.let { add(getString(R.string.hud_file) to it.name) }
            src?.let { add(getString(R.string.hud_source) to "${it.name} (${it.type.uppercase(Locale.ROOT)})") }
            file?.let { add(getString(R.string.hud_container) to "${containerName(it.name)} · ${bytes(it.size)}") }
            muxer?.let { add(getString(R.string.hud_muxer) to it) }
            video?.let { add(getString(R.string.hud_video) to it) }
            audio?.let { add(getString(R.string.hud_audio) to it) }
            add(getString(R.string.hud_subtitle) to (subtitleTracks.firstOrNull { it.id == subtitleTrack }?.label ?: getString(R.string.off)))
            // The screen's rate, the film's, and what's really being shown, as in Infuse.
            val filmFps = e?.frameRate() ?: tracks.filterIsInstance<IMedia.VideoTrack>().firstOrNull { it.frameRateDen > 0 }?.let { it.frameRateNum.toDouble() / it.frameRateDen }
            add(getString(R.string.hud_display) to listOfNotNull(
                "%.2f Hz".format(Locale.ROOT, mode.refreshRate),
                filmFps?.let { "%.2f Hz".format(Locale.ROOT, it) },
                shownFps.takeIf { it > 0 }?.let { "%.2f Hz".format(Locale.ROOT, it) },
            ).joinToString(" / "))
            bufferSeconds?.let {
                val state = getString(if (it >= 3) R.string.hud_buffer_good else R.string.hud_buffer_low)
                add(getString(R.string.hud_buffer) to getString(R.string.hud_buffer_value, state, "%.1f".format(Locale.getDefault(), it)))
            }
            add(getString(R.string.hud_network) to "${mbps(netMbps)} Mbps")
            add(getString(R.string.hud_flow) to getString(R.string.hud_flow_value, mbps(flowMbps), mbps(average), mbps(peakMbps)))
            add(getString(R.string.hud_device) to "${android.os.Build.MODEL} · ${gb(memory.totalMem)}")
            add(getString(R.string.hud_disk) to "${gb(disk.availableBytes)} / ${gb(disk.totalBytes)}")
            add(getString(R.string.hud_player) to (if (e != null) listOf("Media3", e.decoderName).filter { it.isNotBlank() }.joinToString(" · ") else "VLC"))
        }
    }

    /**
     * As soon as the film's frame rate is known (before its first frame where possible): tell the display,
     * and switch the TV's output mode to a matching refresh rate when it offers one (24p without judder).
     * TVs with one fixed mode for apps (the Philips: 1080p60) handle the film's rate in their own video
     * pipeline; the declared rate is what they go by.
     */
    private fun onVideoOut() {
        if (modeSwitched || !lib.settings.matchFrameRate) return
        // The decoder doesn't always know the rate yet; the container usually does. Media3 measures it when not.
        val fps = if (exo != null) exo?.frameRate() else listOfNotNull(player.currentVideoTrack, mediaTracks().filterIsInstance<IMedia.VideoTrack>().firstOrNull())
            .firstOrNull { it.frameRateNum > 0 && it.frameRateDen > 0 }
            ?.let { it.frameRateNum.toDouble() / it.frameRateDen }
        if (fps == null) return   // asked again on the next time update
        modeSwitched = true
        declaredFps = fps
        declareFrameRate()
        @Suppress("DEPRECATION") val display = windowManager.defaultDisplay
        val current = display.mode
        val modes = display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        val best = bestRefreshRate(modes.map { it.refreshRate }, fps)?.let { modes[it] }
        Log.i(TAG, "Video %.3f fps, display %.3f Hz, switching to %s".format(Locale.ROOT, fps, current.refreshRate, best?.refreshRate))
        // No match for this film: back to the TV's own mode, not the one the last film in the queue asked for.
        val wanted = best?.modeId ?: 0
        if (wanted == window.attributes.preferredDisplayModeId || wanted == current.modeId) return
        window.attributes = window.attributes.apply { preferredDisplayModeId = wanted }
        // TVs go black for a moment while HDMI renegotiates; don't play through that. A viewer's pause, a new film
        // or leaving the player in those seconds cancels the restart.
        if (!isPlayingNow()) return
        pausePlayback()
        modeResume = lifecycleScope.launch {
            delay(2500)
            // A player busy with the change can miss the first request.
            repeat(5) { if (!isPlayingNow()) { resumePlayback(); delay(1000) } }
        }
    }

    /** Android 11+: the video surface carries the film's frame rate (Media3 already does this for its own). */
    private fun declareFrameRate() {
        val fps = declaredFps ?: return
        if (android.os.Build.VERSION.SDK_INT < 30 || exo != null) return
        fun find(v: android.view.View): android.view.SurfaceView? = when (v) {
            is android.view.SurfaceView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        val surface = find(videoLayout)?.holder?.surface?.takeIf { it.isValid } ?: return
        runCatching { surface.setFrameRate(fps.toFloat(), android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
            .onSuccess { Log.i(TAG, "Declared %.3f fps on the video surface".format(Locale.ROOT, fps)) }
    }

    private fun onEnded() {
        val n = now ?: return
        lib.saveProgress(n.key, Progress(duration, duration, System.currentTimeMillis(), watched = true, audioTrack, subtitleTrack))
        playing = false
        // Playlists and shuffles: the next item in the queue, or around again when repeating.
        val repeat = lib.settings.repeatMode
        if (repeat == 1) { load(n.key, fromStart = true); return }
        val i = queue.indexOf(n.key)
        if (i >= 0 && (i < queue.lastIndex || repeat == 2)) { load(queue[(i + 1) % queue.size], fromStart = true); return }
        val next = lib.nextEpisode(n.key)
        if (next != null && lib.settings.autoplayNext) {
            upNext = next
            upNextSeconds = 10
            upNextJob = lifecycleScope.launch {
                while (upNextSeconds > 0) { delay(1000); upNextSeconds-- }
                load(next.second.id, fromStart = true)
            }
        } else finish()
    }

    fun playNextNow() { upNext?.let { load(it.second.id, fromStart = true) } }
    fun cancelUpNext() { upNextJob?.cancel(); upNext = null; finish() }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            var n = 0
            while (isActive) {
                delay(250)
                exo?.player?.let { p ->
                    position = p.currentPosition
                    if (p.duration > 0) duration = p.duration
                    if (!modeSwitched && position > 0 && p.videoFormat != null) onVideoOut()
                    checkIntro()
                }
                if (++n % 4 == 0 && isPlayingNow()) saveProgress()
                if (hud && n % 4 == 0) { sampleFlow(); techInfo = buildTechInfo() }
            }
        }
    }

    private fun saveProgress(force: Boolean = false) {
        val n = now ?: return
        val t = System.currentTimeMillis()
        if (!force && t - lastSaved < 5000) return
        val old = lib.progressOf(n.key)
        val early = duration <= 0 || position < resumeAt - 5_000
        if (early && (!force || old?.watched == true)) return
        lastSaved = t
        if (early) {
            // Closed before playback got going (no length yet, or the resume point not reached): the title still
            // moves to the front of Watching, keeping any earlier place.
            val length = ((lib.findMovie(n.key)?.runtime ?: lib.findEpisode(n.key)?.second?.runtime ?: 0) * 60_000L).coerceAtLeast(1)
            lib.saveProgress(n.key, old?.takeIf { it.position > 0 }?.copy(updated = t) ?: Progress(1, length, t, false, chosenAudio(), chosenSubtitle()))
            return
        }
        val watched = position > duration * 0.92
        lib.saveProgress(n.key, Progress(position.coerceAtLeast(1), duration, t, watched, chosenAudio(), chosenSubtitle()))
    }

    private fun chosenAudio() = if (audioChosen) audioTrack else -2
    private fun chosenSubtitle() = if (subtitleChosen) subtitleTrack else -2

    // ---- choices from the overlay ----

    fun chooseAudio(id: Int, byViewer: Boolean = true) {
        if (byViewer) audioChosen = true
        if (reopenAt != null) return     // the film is reopening for the last change; it isn't there to switch yet
        exo?.let { e -> e.select(androidx.media3.common.C.TRACK_TYPE_AUDIO, id); audioTrack = id } ?: run {
            player.audioTrack = id
            audioTrack = player.audioTrack
            if (!matchPassthrough()) resyncPassthrough()
        }
        // A dub's forced subtitles cover the lines it leaves in English, so they come on with it.
        if (id in dubIds) {
            val forced = subtitleFiles.indexOfFirst { isDubSubtitle(it) && isForced(it) }
            spuIds.getOrNull(embeddedSubs + forced)?.takeIf { forced >= 0 }?.let { chooseSubtitle(it, byViewer = false) }
        }
        saveProgress(true)
        techInfo = buildTechInfo()
    }

    private fun isDubSubtitle(f: File) = f.name.substringBefore('-').endsWith("dub")
    private fun isForced(f: File) = Regex("""(?i)forced|zorunlu""").containsMatchIn(f.name)
    /** The subtitle playing is pictures (Blu-ray PGS and the like): its size can change, its colour and lettering can't. */
    fun imageSubtitles() = exo?.imageSubtitle(subtitleTrack) == true
    fun chooseSubtitle(id: Int, byViewer: Boolean = true) {
        if (byViewer) subtitleChosen = true
        exo?.let { e -> e.select(androidx.media3.common.C.TRACK_TYPE_TEXT, id); subtitleTrack = id } ?: run { player.spuTrack = id; subtitleTrack = player.spuTrack }
        saveProgress(true)
    }
    /**
     * With passthrough on, the TV hands a reopened audio output back with its old clock, and libVLC races the
     * picture ahead to catch up (after an audio track change, or the film reopened by a restyle). A seek to
     * where the film is flushes the output and starts its clock afresh; [then] runs once that has settled.
     */
    private fun resyncPassthrough(then: () -> Unit = {}) {
        lifecycleScope.launch {
            if (exo == null && lib.settings.passthrough) { delay(800); player.time = position }
            delay(400)
            then()
        }
    }

    /** Whether libVLC's audio track [id] is DTS (DTS-HD and DTS:X included). */
    private fun isDts(id: Int) = mediaTracks().firstOrNull { it.type == IMedia.Track.Type.Audio && it.id == id }
        ?.codec?.contains("dts", ignoreCase = true) == true

    /**
     * With passthrough on, reopens the film when the track playing has moved between DTS and the rest, so DTS is
     * decoded here and Dolby still goes to the TV untouched. True when it reopened.
     */
    private fun matchPassthrough(): Boolean {
        if (exo != null || !lib.settings.passthrough) return false
        val dts = isDts(audioTrack)
        now?.key?.let { lib.settings.rememberDts(it, dts) }
        if (dts == dtsDecoded) return false
        dtsDecoded = dts
        reopenVlc()
        return true
    }

    /**
     * Applies changed subtitle styling now. Media3 restyles in place; libVLC reads the style only when it
     * is created, so it's rebuilt and the film carries on from where it was.
     */
    fun restyleSubtitles() {
        exo?.let { it.restyle(); return }
        reopenVlc()
    }

    /** A new libVLC (its options read afresh), the film reopened where it was, covered until it's back there. */
    private fun reopenVlc() {
        val key = now?.key ?: return
        saveProgress(force = true)
        reopenAt = position
        lifecycleScope.launch { delay(10_000); reopenAt = null; revealing = false }     // never stay covered if it can't get there
        player.setEventListener(null); player.stop(); player.detachViews(); player.release(); vlc.release()
        createVlc()
        player.attachViews(videoLayout, null, true, false)
        player.videoScale = vlcScale(scale)
        load(key, fromStart = false)
    }
    fun shiftSubtitles(ms: Long) {
        subtitleDelay += ms
        exo?.setSubtitleDelay(subtitleDelay) ?: run { player.spuDelay = subtitleDelay * 1000 }
    }
    fun shiftAudio(ms: Long) { if (exo != null) return; audioDelay += ms; player.audioDelay = audioDelay * 1000 }
    fun chooseRate(r: Float) { rate = r; exo?.player?.setPlaybackSpeed(r) ?: run { player.rate = r } }
    fun chooseChapter(i: Int) { if (exo == null) player.chapter = i; showControls() }
    fun chooseScale(i: Int) {
        scale = i
        exo?.let { it.scale(i); return }
        player.aspectRatio = null
        player.videoScale = vlcScale(i)
    }

    private fun vlcScale(i: Int) = when (i) {
        1 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
        2 -> MediaPlayer.ScaleType.SURFACE_FILL
        3 -> MediaPlayer.ScaleType.SURFACE_16_9
        4 -> MediaPlayer.ScaleType.SURFACE_4_3
        // Fit, and "Original": the film's own shape, as large as fits, nothing stretched or cut. libVLC's own
        // SURFACE_ORIGINAL maps video pixels 1:1 onto the 1080p surface, which blows a 4K film up 2×.
        else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
    }

    fun closePanel() { panel = false; showControls() }

    // ---- skip intros & credits (from chapter names, or an intro the viewer marked for the series) ----

    var skipKind by mutableStateOf<Int?>(null)   // string resource of the button, null when nothing to skip
    // On its own the button stays for 10 seconds on screen, enough to decide; after that it comes back only with the
    // controls, until the intro or credits end.
    var skipShown by mutableStateOf(false)
    private var skipHideJob: Job? = null
    private var skipTarget = 0L
    private var skippedChapter = -1
    private var skippedMarked = false
    private var autoSkipJob: Job? = null

    /** The series' marked intro (ms), and a start marked in this episode that still waits for its end. */
    var markedIntro by mutableStateOf<Pair<Long, Long>?>(null)
    var introStartMark by mutableStateOf<Long?>(null)

    fun markIntroStart() { introStartMark = position }

    fun markIntroEnd() {
        val start = introStartMark ?: markedIntro?.first ?: 0L
        val key = now?.key ?: return
        if (position <= start + 5_000) return   // an intro is longer than a few seconds
        lib.setIntro(key, start, position)
        markedIntro = start to position
        introStartMark = null
        skippedMarked = true   // just watched it; don't offer to skip what's already behind us
    }

    fun clearIntroMarks() {
        now?.key?.let { lib.clearIntro(it) }
        markedIntro = null; introStartMark = null
    }

    private fun checkIntro() {
        val mode = lib.settings.skipIntros
        if (mode == 0) { skipKind = null; return }
        val c = SkipRules.check(chapters, position, duration, markedIntro, skippedChapter, skippedMarked)
        skippedChapter = c.skippedChapter; skippedMarked = c.skippedMarked
        val kind = when (c.kind) { SkipKind.INTRO -> R.string.skip_intro; SkipKind.CREDITS -> R.string.skip_credits; null -> null }
        if (kind == skipKind) return
        skipKind = kind
        skipHideJob?.cancel()
        skipShown = kind != null
        // Counted only while it's actually showing: not while paused, or under the controls or a panel.
        if (kind != null) skipHideJob = lifecycleScope.launch {
            var left = 10_000
            while (left > 0) { delay(250); if (SkipRules.aloneCounts(playing, controls, panel, upNext != null)) left -= 250 }
            skipShown = false
        }
        skipTarget = c.target
        autoSkipJob?.cancel()
        if (kind != null && mode >= 2) autoSkipJob = lifecycleScope.launch { if (mode == 2) delay(3000); doSkip() }
    }

    /** The skip button is on screen: alone for its first 10 seconds, then whenever the controls are up (not on the track buttons). */
    fun skipButtonShown() = SkipRules.buttonShown(skipKind != null, playing, paused, panel, upNext != null,
        controls, seekTarget != null, trackFocus >= 0 || trackPopup >= 0, skipShown)

    fun doSkip() {
        val target = skipTarget
        SkipRules.afterSkip(chapters, position, target, markedIntro, skippedChapter, skippedMarked).let { (c, m) -> skippedChapter = c; skippedMarked = m }
        skipKind = null
        skipHideJob?.cancel(); skipShown = false
        autoSkipJob?.cancel()
        if (target >= duration - 1000) onEnded() else { seekPlayback(target); position = target }
    }

    /** Per-video loudness on top of the global boost: 100, 150 or 200 percent. */
    var volume by mutableIntStateOf(100)
    fun chooseVolume(v: Int) { volume = v; exo?.player?.let { it.volume = (v / 100f).coerceAtMost(1f) } ?: run { player.volume = v } }

    var repeat by mutableIntStateOf(lib.settings.repeatMode)
    fun chooseRepeat(mode: Int) { repeat = mode; lib.settings.repeatMode = mode }

    // ---- remote ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        // The remote's playback keys work over the menu, a track pop-up and the up-next card too: those take
        // only arrows and OK, and Stop or Play/Pause pressed there did nothing.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) when (code) {
            KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                if ((panel || trackPopup >= 0 || upNext != null) && error == null && reopenAt == null) {
                    when (code) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> resumePlayback()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> pausePlayback()
                        else -> togglePause()
                    }
                    return true
                }
        }
        // Menus and the up-next card are ordinary focusable Compose UI.
        if (panel || upNext != null || error != null) return super.dispatchKeyEvent(event)
        // While a reopen catches up (a few seconds), the controls wait; Back is handled above.
        if (reopenAt != null) return true
        if (event.action == KeyEvent.ACTION_UP) return true
        // Paused on a clean picture: the first press only brings the controls back. Play and OK go on to play.
        if (paused && !controls) when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { showControls(); return true }
        }
        // On the track buttons or in a pop-up, the bar's 3 seconds start again from each press.
        if (trackFocus >= 0 || trackPopup >= 0) scheduleHide()
        // In a track pop-up: up and down walk the list, OK picks; left and right close it and move to the next button.
        if (trackPopup >= 0) when (code) {
            // Round and round: past the last row back to the first, and the other way.
            KeyEvent.KEYCODE_DPAD_UP -> { val n = popupRows().size; if (n > 0) popupFocus = (popupFocus - 1 + n) % n; return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { val n = popupRows().size; if (n > 0) popupFocus = (popupFocus + 1) % n; return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> trackPopup = -1
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) pickPopup()
                return true
            }
            else -> return true
        }
        // On the track buttons: arrows move between them, OK opens that button's list (chapters: the menu), down goes back.
        if (trackFocus >= 0) when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { trackFocus = (trackFocus - 1).coerceAtLeast(0); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { trackFocus = (trackFocus + 1).coerceAtMost(trackButtons().lastIndex); return true }
            KeyEvent.KEYCODE_DPAD_UP -> return true
            KeyEvent.KEYCODE_DPAD_DOWN -> { trackFocus = -1; scheduleHide(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) trackButtons().getOrNull(trackFocus)?.let { if (it == 1) { trackFocus = -1; openPanel(it) } else openPopup(it) }
                return true
            }
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> if (event.repeatCount == 0) {
                when {
                    seekTarget != null -> commitSeek()
                    skipButtonShown() -> doSkip()
                    else -> togglePause()
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { resumePlayback(); showControls() }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> pausePlayback()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                if (event.repeatCount == 0 && lib.settings.edgeMode == 1 && chapters.size > 1 && seekTarget == null) { previousChapter(); showControls() }
                else scrub(-step(lib.settings.backwardSkip * 1000L, event.repeatCount))
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                if (event.repeatCount == 0 && lib.settings.edgeMode == 1 && chapters.size > 1 && seekTarget == null) { nextChapter(); showControls() }
                else scrub(step(lib.settings.forwardSkip * 1000L, event.repeatCount))
            // Up shows the controls; with them already up it moves onto the track buttons.
            KeyEvent.KEYCODE_DPAD_UP -> if (controls && seekTarget == null) { trackFocus = 0; scheduleHide() } else showControls()
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> if (event.repeatCount == 0) openPanel()
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> if (chapters.size > 1) { nextChapter(); showControls() } else scrub(5 * 60_000)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (chapters.size > 1) { previousChapter(); showControls() } else scrub(-5 * 60_000)
            KeyEvent.KEYCODE_MEDIA_STOP -> finish()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    /** Holding a direction speeds up: 1×, then 3×, then a minute, then five minutes per step. */
    private fun step(skip: Long, repeat: Int) = when {
        repeat < 6 -> skip
        repeat < 20 -> skip * 3
        repeat < 40 -> 60_000L
        else -> 300_000L
    }

    private fun onBack() {
        when {
            panel -> if (menuBack?.invoke() != true) closePanel()
            trackPopup >= 0 -> { trackPopup = -1; scheduleHide() }
            trackFocus >= 0 -> { trackFocus = -1; scheduleHide() }
            upNext != null -> cancelUpNext()
            seekTarget != null -> { seekTarget = null; seekJob?.cancel(); scheduleHide() }
            // Reopening for a track or style change: not playing for a moment, but the film isn't over.
            reopenAt != null -> controls = false
            controls && playing -> { controls = false; hideJob?.cancel() }
            // Paused on a clean picture: bring the controls back rather than leave the film.
            paused && !controls -> showControls()
            else -> { saveProgress(force = true); finish() }
        }
    }

    private fun togglePause() {
        modeResume?.cancel()
        if (isPlayingNow()) pausePlayback() else { resumePlayback(); showControls() }
    }

    private fun scrub(delta: Long) {
        if (duration <= 0) return
        val base = seekTarget ?: position
        seekTarget = (base + delta).coerceIn(0, duration - 1000)
        showControls(sticky = true)
        seekJob?.cancel()
        seekJob = lifecycleScope.launch { delay(900); commitSeek() }
    }

    private fun commitSeek() {
        seekJob?.cancel()
        seekTarget?.let { seekPlayback(it); position = it }
        seekTarget = null
        scheduleHide()
    }

    fun showControls(sticky: Boolean = false) {
        controls = true
        hideJob?.cancel()
        if (!sticky) scheduleHide()
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = lifecycleScope.launch {
            delay(3000)
            // An open pop-up is still being read; its next key press starts the wait again.
            if ((playing || paused) && !panel && seekTarget == null && trackPopup < 0) { controls = false; trackFocus = -1 }
        }
    }

    override fun finish() {
        if (window.attributes.preferredDisplayModeId != 0) window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
        // Like Kodi: the picture goes the moment the film is left, so the TV leaves Dolby Vision or HDR at once rather
        // than when this screen is gone, and the next film switches in afresh (its banner shows again). The place is
        // saved first; nothing updates it after this.
        saveProgress(force = true)
        tickJob?.cancel()
        player.setEventListener(null)
        exo?.let { e -> e.player.stop(); e.player.clearVideoSurface(); root.removeView(e.view) }
        if (player.media != null) player.stop()
        player.detachViews()
        root.removeView(videoLayout)
        super.finish()
    }

    companion object {
        private const val TAG = "Player"
        private val SIDECAR_ID = Regex("""sub(\d+)""")
        private val OS_FILE = Regex("""^\d+os-""")
        /** [pickSubtitle]: the subtitle just added, last in the list. */
        private const val LAST = -2
        private const val EXTRA_KEY = "key"
        private const val EXTRA_FROM_START = "fromStart"
        private const val EXTRA_QUEUE = "queue"

        fun start(context: Context, key: String, fromStart: Boolean = false, queue: List<String>? = null) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_KEY, key)
                    .putExtra(EXTRA_FROM_START, fromStart)
                    .apply { if (queue != null) putStringArrayListExtra(EXTRA_QUEUE, ArrayList(queue)) }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
