package com.glint.player

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaCodecList
import android.net.Uri
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import java.io.File
import java.io.IOException
import java.util.EnumSet
import java.util.Locale

/** Whether this TV can show Dolby Vision, and which files should use it. */
object DolbyVision {
    val decoderAvailable: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    fun displaySupports(activity: Activity): Boolean = runCatching {
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.hdrCapabilities.supportedHdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
    }.getOrDefault(false)

    /** A Dolby Vision file on an SMB share, with a TV that has both the decoder and the screen for it. */
    fun shouldUse(activity: Activity, src: Source, file: VideoFile?): Boolean =
        file != null && App.library.settings.dolbyVision && src.type == "smb" && file.strmUrl == null && file.parts.isEmpty() &&
            "Dolby Vision" in badges(file.name) && decoderAvailable && displaySupports(activity)
}

/** Separate SMB connection for Media3, so a scan or poster loads never stall playback. */
object SmbStream : SmbLane()

/**
 * Reads a file on an SMB share for Media3. Uri: glintsmb://<sourceId>/<share>/<path>.
 * Reads go out in chunks of the server's maximum read size into a local buffer, since Media3 asks
 * for small pieces and every SMB request is a network round trip.
 */
@OptIn(UnstableApi::class)
class SmbDataSource : BaseDataSource(true) {
    private var file: com.hierynomus.smbj.share.File? = null
    private var uri: Uri? = null
    private var opened = false
    private var position = 0L
    private var remaining = 0L
    private var chunk = ByteArray(0)
    private var chunkStart = 0L
    private var chunkLength = 0

    private var src: Source? = null
    private var shareName = ""
    private var path = ""

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        src = App.library.source(dataSpec.uri.host ?: "") ?: throw IOException("Source gone")
        val segments = dataSpec.uri.pathSegments
        shareName = segments.firstOrNull() ?: throw IOException("No share")
        path = segments.drop(1).joinToString("\\")
        val f = reopen()
        val size = f.fileInformation.standardInformation.endOfFile
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        chunkLength = 0
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    /**
     * Opens (again) the file. Media3 reads one film through several of these at once (seeking, the
     * audio and video loaders), so a broken connection is replaced, never closed under the others.
     */
    private fun reopen(): com.hierynomus.smbj.share.File {
        file?.let { runCatching { it.close() } }
        file = null
        val s = src ?: throw IOException("Source gone")
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                if (attempt == 2) SmbStream.forget(s)   // last resort: a fresh connection
                val disk = SmbStream.share(s, shareName)
                val maxRead = runCatching { disk.treeConnect.session.connection.negotiatedProtocol.maxReadSize }.getOrDefault(64 * 1024)
                if (chunk.isEmpty()) chunk = ByteArray(maxRead.coerceIn(64 * 1024, 1 shl 20))
                return disk.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
                    .also { file = it }
            } catch (e: Exception) {
                last = e
                SmbStream.dropClosed(s, shareName)
            }
        }
        throw IOException("Couldn't open $path", last)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        if (position < chunkStart || position >= chunkStart + chunkLength) {
            val want = minOf(chunk.size.toLong(), remaining).toInt()
            // A dropped connection mid-film: reopen and carry on from the same byte.
            val n = try {
                file!!.read(chunk, position, 0, want)
            } catch (e: Exception) {
                android.util.Log.w("SmbDataSource", "Read failed at $position, reopening", e)
                reopen().read(chunk, position, 0, want)
            }
            if (n <= 0) return C.RESULT_END_OF_INPUT
            chunkStart = position; chunkLength = n
        }
        val from = (position - chunkStart).toInt()
        val n = minOf(length, chunkLength - from, minOf(remaining, Int.MAX_VALUE.toLong()).toInt())
        System.arraycopy(chunk, from, buffer, offset, n)
        position += n; remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        file?.let { runCatching { it.close() } }
        file = null; uri = null
        if (opened) { opened = false; transferEnded() }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }

    companion object {
        fun uri(src: Source, share: String, path: String): Uri =
            Uri.Builder().scheme("glintsmb").authority(src.id).appendPath(share)
                .apply { path.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) } }.build()
    }
}

/**
 * Media3 behind [PlayerActivity]'s own overlay, for Dolby Vision films. It fills in the same state the
 * libVLC path does (position, tracks, playing) so the screen looks and works the same. Track ids are
 * group * 1000 + track. MKV chapters aren't exposed by Media3, so these films have none.
 */
@OptIn(UnstableApi::class)
class ExoPlayback(private val a: PlayerActivity) {
    val view = PlayerView(a).apply {
        useController = false
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(false)
    }
    val player: ExoPlayer
    private var dubCount = 0
    /** Set after the Dolby Vision decoder failed to start: play the HDR10 base layer instead. */
    private var allowHevc = false
    private lateinit var factory: DoviRenderersFactory

    /** Shows subtitles [ms] later (negative: earlier) than the picture. */
    fun setSubtitleDelay(ms: Long) { factory.subtitleOffsetUs = ms * 1000 }
    private var subtitleLabels: List<Pair<String, String>> = emptyList()   // config id → label

    init {
        // TV firmware often lists Dolby Vision decoders without saying which profiles they take, so Media3
        // rules them out and plays the HEVC base layer. Offer only them, and keep plain HEVC for when the
        // Dolby Vision decoder won't start. On the Philips (MediaTek) "dvhe.stn" signals Dolby Vision for
        // every profile 8 remux tried; "dvhe.st" missed some (multi-slice encodes like Mad Max: Fury Road).
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            val list = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            when (mime) {
                // Media3 re-sorts what it gets, so ordering isn't enough: hand it only the best one there is.
                MimeTypes.VIDEO_DOLBY_VISION -> list.filter { !it.secure && it.name.contains("dvhe.stn.") }.ifEmpty { list }
                MimeTypes.VIDEO_H265 -> if (allowHevc) list else emptyList()
                else -> list
            }
        }
        val renderers = DoviRenderersFactory(a).also { factory = it }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector(selector)
            .setEnableDecoderFallback(true)
        // Memory, not time, is what limits a 4K remux: a 2.7 GB TV can't hold a minute of 80 Mbps.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
            .setTargetBufferBytes(160 shl 20)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(a, SmbDataSource.Factory()))
        player = ExoPlayer.Builder(a, renderers).setLoadControl(load).setMediaSourceFactory(sources).build()
        view.player = player
        styleSubtitles()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = a.enginePlayingChanged(isPlaying)
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> a.buffering = 0f
                    Player.STATE_READY -> { a.buffering = 100f; a.duration = player.duration.coerceAtLeast(0) }
                    Player.STATE_ENDED -> a.engineEnded()
                }
            }
            override fun onTracksChanged(tracks: Tracks) = a.engineTracksChanged()
            // Runs after PlayerView's own cue listener (added before this one), so the scaled cues are what show.
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) { cues = cueGroup.cues; showCues() }
            override fun onPlayerError(error: PlaybackException) {
                val decoder = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                if (decoder && !allowHevc) {
                    android.util.Log.w("ExoPlayback", "Dolby Vision decoder failed, playing HDR10 instead", error)
                    allowHevc = true
                    player.prepare()
                    return
                }
                a.error = a.getString(R.string.play_failed) + "  (" + error.errorCodeName + ")"
            }
        })
    }

    fun load(src: Source, share: String, path: String, subs: List<File>, subLabels: List<String>, dubs: List<DubFile>, startMs: Long) {
        factory.subtitleOffsetUs = 0
        subtitleLabels = subs.mapIndexed { i, _ -> "sub$i" to subLabels.getOrElse(i) { "" } }
        val item = MediaItem.Builder()
            .setUri(SmbDataSource.uri(src, share, path))
            .setSubtitleConfigurations(subs.mapIndexed { i, f ->
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                    .setId("sub$i").setLabel(subLabels.getOrNull(i)).setMimeType(subtitleMime(f.name))
                    .setSelectionFlags(if (Regex("(?i)forced|zorunlu").containsMatchIn(f.name)) C.SELECTION_FLAG_FORCED else 0)
                    .build()
            })
            .build()
        val main = DefaultMediaSourceFactory(DefaultDataSource.Factory(a, SmbDataSource.Factory())).createMediaSource(item)
        // Dubs from the NAS ride along as extra audio, like a second track inside the file.
        val dubSources = dubs.mapNotNull { d ->
            val s = App.library.source(d.source)?.takeIf { it.type == "smb" } ?: return@mapNotNull null
            ProgressiveMediaSource.Factory(SmbDataSource.Factory()).createMediaSource(MediaItem.fromUri(SmbDataSource.uri(s, d.share, d.path)))
        }
        dubCount = dubSources.size
        player.setMediaSource(if (dubSources.isEmpty()) main else MergingMediaSource(true, true, main, *dubSources.toTypedArray()), startMs)
        player.prepare()
        player.playWhenReady = true
    }

    private fun subtitleMime(name: String) = when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt" -> MimeTypes.TEXT_VTT
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    fun restyle() { styleSubtitles(); showCues() }

    /** The cues on screen now, as the film gave them; [showCues] draws them at the viewer's size. */
    private var cues: List<androidx.media3.common.text.Cue> = emptyList()

    /** True while the subtitle playing is pictures (Blu-ray PGS, DVD VobSub): only its size can change. */
    fun imageSubtitles() = groups(C.TRACK_TYPE_TEXT).any { (_, g) ->
        (0 until g.length).any { g.isTrackSelected(it) && g.getTrackFormat(it).codecs.orEmpty().let { c -> "pgs" in c || "vobsub" in c || "dvb" in c } }
    }

    /**
     * Text cues take the size from [styleSubtitles]; picture cues (PGS, VobSub) are scaled here, about their
     * bottom centre so they stay in place above the bottom of the frame.
     */
    private fun showCues() {
        val k = App.library.settings.subtitleScale / 100f
        val shown = if (k == 1f) cues else cues.map { c ->
            if (c.bitmap == null || c.size == androidx.media3.common.text.Cue.DIMEN_UNSET) c
            else c.buildUpon().apply {
                setSize(c.size * k)
                if (c.bitmapHeight != androidx.media3.common.text.Cue.DIMEN_UNSET) setBitmapHeight(c.bitmapHeight * k)
                if (c.position != androidx.media3.common.text.Cue.DIMEN_UNSET && c.positionAnchor == androidx.media3.common.text.Cue.ANCHOR_TYPE_START)
                    setPosition(c.position + c.size * (1 - k) / 2)
                if (c.line != androidx.media3.common.text.Cue.DIMEN_UNSET && c.lineType == androidx.media3.common.text.Cue.LINE_TYPE_FRACTION &&
                    c.lineAnchor == androidx.media3.common.text.Cue.ANCHOR_TYPE_START && c.bitmapHeight != androidx.media3.common.text.Cue.DIMEN_UNSET)
                    setLine(c.line + c.bitmapHeight * (1 - k), c.lineType)
            }.build()
        }
        view.subtitleView?.setCues(shown)
    }

    private fun styleSubtitles() {
        val s = App.library.settings
        val sub = view.subtitleView ?: return
        sub.setStyle(
            CaptionStyleCompat(
                Color.rgb(s.subtitleColor shr 16 and 255, s.subtitleColor shr 8 and 255, s.subtitleColor and 255),
                if (s.subtitleBackground) Color.argb(160, 0, 0, 0) else Color.TRANSPARENT,
                Color.TRANSPARENT,
                if (s.subtitleOutline > 0) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
                Color.BLACK,
                if (s.subtitleBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT,
            )
        )
        sub.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s.subtitleScale / 100f)
        sub.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION + s.subtitlePosition / 1080f)
        sub.setApplyEmbeddedStyles(true)
    }

    // ---- tracks ----

    private fun groups(type: Int) = player.currentTracks.groups.withIndex().filter { it.value.type == type }

    /** Dub audio comes from the merged sources after the film's own (their group ids start "1:", "2:"…). */
    private fun dubIndex(g: Tracks.Group): Int? =
        g.mediaTrackGroup.id.substringBefore(':', "").toIntOrNull()?.takeIf { it > 0 && dubCount > 0 }?.minus(1)

    fun audioOptions(dubLabels: List<String>): List<TrackOption> = groups(C.TRACK_TYPE_AUDIO).flatMap { (gi, g) ->
        (0 until g.length).filter { g.isTrackSupported(it, true) }.map { ti ->
            val label = dubIndex(g)?.let { dubLabels.getOrNull(it) } ?: describeAudio(g.getTrackFormat(ti))
            TrackOption(gi * 1000 + ti, label)
        }
    }

    fun dubIds(): List<Int> = groups(C.TRACK_TYPE_AUDIO).filter { dubIndex(it.value) != null }.map { it.index * 1000 }

    fun subtitleOptions(): List<TrackOption> = groups(C.TRACK_TYPE_TEXT).flatMap { (gi, g) ->
        (0 until g.length).filter { g.isTrackSupported(it, true) }.map { ti ->
            val f = g.getTrackFormat(ti)
            val label = f.label?.takeIf { it.isNotBlank() } ?: languageName(f.language).ifBlank { "Track ${gi + 1}" }
            TrackOption(gi * 1000 + ti, label + if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) " (forced)" else "")
        }
    }

    fun format(id: Int): Format? = player.currentTracks.groups.getOrNull(id / 1000)?.let { g -> (id % 1000).takeIf { it < g.length }?.let { g.getTrackFormat(it) } }

    fun isForced(id: Int) = format(id)?.let { it.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || it.label?.contains("forced", true) == true } == true

    fun isSidecar(id: Int) = format(id)?.id?.contains("sub") == true

    fun selected(type: Int): Int {
        for ((gi, g) in groups(type)) for (ti in 0 until g.length) if (g.isTrackSelected(ti)) return gi * 1000 + ti
        return -1
    }

    fun select(type: Int, id: Int) {
        val b = player.trackSelectionParameters.buildUpon()
        if (id < 0) b.setTrackTypeDisabled(type, true)
        else {
            val g = player.currentTracks.groups.getOrNull(id / 1000) ?: return
            b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, id % 1000))
        }
        player.trackSelectionParameters = b.build()
    }

    private fun describeAudio(f: Format): String {
        val codec = when (f.sampleMimeType) {
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_X -> "DTS"
            MimeTypes.AUDIO_AAC -> "AAC"
            else -> f.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT).orEmpty()
        }
        val channels = when (f.channelCount) { 8 -> "7.1"; 6 -> "5.1"; 2 -> "2.0"; 1 -> "1.0"; else -> "" }
        return listOf(f.label.orEmpty().ifBlank { languageName(f.language) }, codec, channels).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun languageName(code: String?): String {
        if (code.isNullOrBlank() || code == "und") return ""
        val l = Locale.forLanguageTag(code)
        return l.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    fun techInfo(): List<String> {
        val out = ArrayList<String>()
        player.videoFormat?.let { v ->
            val dv = v.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
            out += "Video: ${if (dv) "Dolby Vision" else v.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT)} ${v.codecs.orEmpty()} ${v.width}×${v.height}" +
                (if (v.frameRate > 0) "  %.3f fps".format(Locale.ROOT, v.frameRate) else "")
        }
        player.audioFormat?.let { out += "Audio: " + describeAudio(it) + "  (Media3)" }
        return out
    }

    fun frameRate(): Double? = player.videoFormat?.frameRate?.takeIf { it > 0 }?.toDouble()

    fun scale(i: Int) {
        view.resizeMode = when (i) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    fun release() { view.player = null; player.release() }
}
