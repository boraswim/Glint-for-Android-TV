package com.glint.player

import java.util.Locale

// The player's decisions on their own, without a player, so they're tested (PlaybackRulesTest): which audio and
// subtitles a film opens with, and when the skip intro / credits button shows. libVLC and Media3 each describe their
// tracks as [TrackInfo] and act on what comes back.

/** A track as the rules see it. [language] is its code in any form ("tr", "tur", "eng"), null or "und" when untagged. */
data class TrackInfo(
    val id: Int,
    val language: String? = null,
    val forced: Boolean = false,
    val sidecar: Boolean = false,   // a subtitle file next to the film
    val dub: Boolean = false,       // a dub from the NAS, or a subtitle file that goes with one
)

/** A track picked, and whether it's the viewer's own choice (kept for the file) rather than the settings'. */
data class TrackPick(val id: Int, val byViewer: Boolean)

// ISO 639-2/B codes that differ from the /T codes Java returns (Matroska uses /B).
private val ISO_B = mapOf("deu" to "ger", "fra" to "fre", "nld" to "dut", "zho" to "chi", "ces" to "cze", "ell" to "gre", "fas" to "per", "ron" to "rum", "slk" to "slo", "sqi" to "alb", "hye" to "arm", "mkd" to "mac", "isl" to "ice", "kat" to "geo", "eus" to "baq", "msa" to "may", "mya" to "bur", "cym" to "wel")

/** Every way a tag can name the language: "tr" → tr, tur; "de" → de, deu, ger. */
fun languageCodes(lang: String): Set<String> = runCatching {
    val l = Locale(lang)
    setOf(lang.lowercase(Locale.ROOT), l.isO3Language, ISO_B[l.isO3Language] ?: "")
}.getOrDefault(setOf(lang.lowercase(Locale.ROOT))) - ""

object TrackRules {
    fun untagged(t: TrackInfo) = t.language.isNullOrBlank() || t.language.equals("und", true)

    /** Two codes for the same language ("en" and "eng", "de" and "ger"). */
    fun sameLanguage(a: String?, b: String?) =
        !a.isNullOrBlank() && !b.isNullOrBlank() && languageCodes(a).intersect(languageCodes(b)).isNotEmpty()

    fun speaks(t: TrackInfo, lang: String) = !untagged(t) && sameLanguage(t.language, lang)

    /**
     * The audio: the viewer's earlier pick for the file while it's still there; else the first track in the language
     * wanted; else, when Turkish is wanted, a dub from the NAS (they're Turkish); else null, the file's own default.
     * [speaks] can also look at a track's name, as libVLC's untagged tracks need.
     */
    fun audio(tracks: List<TrackInfo>, want: String, saved: Int, speaks: (TrackInfo, String) -> Boolean = ::speaks): TrackPick? {
        if (saved != -2 && tracks.any { it.id == saved }) return TrackPick(saved, byViewer = true)
        if (want.isEmpty()) return null
        tracks.firstOrNull { !it.dub && speaks(it, want) }?.let { return TrackPick(it.id, byViewer = false) }
        if ("tur" in languageCodes(want)) tracks.firstOrNull { it.dub }?.let { return TrackPick(it.id, byViewer = false) }
        return null
    }

    /**
     * The subtitle, -1 for off: the viewer's earlier pick while it's still there. Else, unless the audio is already in
     * the language wanted, a track in that language (a full one before a forced one), then a file next to the film that
     * names no language (never one naming another). Else, with forced subtitles on, the forced ones of a dub playing,
     * or a forced track in the audio's language or untagged. Else off.
     */
    fun subtitle(
        tracks: List<TrackInfo>, want: String, audioLanguage: String?, dubPlaying: Boolean, forcedOn: Boolean, saved: Int,
        speaks: (TrackInfo, String) -> Boolean = ::speaks,
    ): TrackPick {
        if (saved == -1 || (saved != -2 && tracks.any { it.id == saved })) return TrackPick(saved, byViewer = true)
        val wanted = when {
            want.isEmpty() -> null
            sameLanguage(audioLanguage, want) -> null
            else -> tracks.firstOrNull { !it.dub && !it.forced && speaks(it, want) }
                ?: tracks.firstOrNull { !it.dub && speaks(it, want) }
                ?: tracks.firstOrNull { it.sidecar && !it.dub && !it.forced && untagged(it) }
        }
        if (wanted != null) return TrackPick(wanted.id, byViewer = false)
        if (!forcedOn) return TrackPick(-1, byViewer = false)
        val forced = tracks.filter { it.forced }
        val pick = (if (dubPlaying) forced.firstOrNull { it.dub } else null)
            ?: forced.firstOrNull { !it.dub && speaks(it, audioLanguage.orEmpty()) }
            ?: forced.firstOrNull { !it.dub && untagged(it) }
        return TrackPick(pick?.id ?: -1, byViewer = false)
    }
}

enum class SkipKind { INTRO, CREDITS }

/**
 * What can be skipped at a moment: [kind] (null for nothing), where the skip goes, and the updated memory of what was
 * skipped already ([skippedChapter], [skippedMarked]).
 */
data class SkipCheck(val kind: SkipKind?, val target: Long, val skippedChapter: Int, val skippedMarked: Boolean)

object SkipRules {
    val INTRO = Regex("""(?i)(intro|opening|recap|previously|giriş|önceki bölüm)""")
    val CREDITS = Regex("""(?i)(credits|ending|outro|end titles|jenerik)""")

    /**
     * From the chapter playing (named like an intro or credits; to its end), or the intro marked for the series (while
     * it plays, up to two seconds before its end). A skipped chapter is offered again once playback is back in it from
     * elsewhere; a skipped marked intro once playback is back before it.
     */
    fun check(
        chapters: List<Pair<String, Long>>, position: Long, duration: Long, markedIntro: Pair<Long, Long>?,
        skippedChapter: Int, skippedMarked: Boolean,
    ): SkipCheck {
        val i = chapters.indexOfLast { it.second <= position }
        val name = chapters.getOrNull(i)?.first.orEmpty()
        val chapterSkipped = if (skippedChapter >= 0 && i != skippedChapter) -1 else skippedChapter
        val fromChapter = when {
            chapters.size < 2 || i < 0 || i == chapterSkipped -> null
            INTRO.containsMatchIn(name) -> SkipKind.INTRO
            CREDITS.containsMatchIn(name) -> SkipKind.CREDITS
            else -> null
        }
        val markedSkipped = skippedMarked && !(markedIntro != null && position < markedIntro.first)
        val marked = markedIntro?.takeIf { (s, e) -> !markedSkipped && position >= s && position < e - 2_000 }
        val kind = fromChapter ?: marked?.let { SkipKind.INTRO }
        val target = if (fromChapter != null) chapters.getOrNull(i + 1)?.second ?: duration else marked?.second ?: 0L
        return SkipCheck(kind, target, chapterSkipped, markedSkipped)
    }

    /** After skipping from [position] to [target]: the chapter or marked intro that's now behind, as (skippedChapter, skippedMarked). */
    fun afterSkip(chapters: List<Pair<String, Long>>, position: Long, target: Long, markedIntro: Pair<Long, Long>?, skippedChapter: Int, skippedMarked: Boolean) =
        if (markedIntro?.second == target) skippedChapter to true
        else chapters.indexOfLast { it.second <= position } to skippedMarked

    /**
     * The button is on screen while playing (not paused, no panel or up-next): alone during its first 10 seconds
     * ([alone]), and afterwards whenever the controls are up, unless the viewer is seeking or on the track buttons.
     */
    fun buttonShown(
        skip: Boolean, playing: Boolean, paused: Boolean, panel: Boolean, upNext: Boolean,
        controls: Boolean, seeking: Boolean, onTrackButtons: Boolean, alone: Boolean,
    ) = skip && playing && !paused && !panel && !upNext && if (controls) !seeking && !onTrackButtons else alone

    /** The 10 seconds alone count only while the button can be seen on its own. */
    fun aloneCounts(playing: Boolean, controls: Boolean, panel: Boolean, upNext: Boolean) = playing && !controls && !panel && !upNext
}
