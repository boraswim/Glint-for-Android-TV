package com.glint.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRulesTest {
    // Toy Story's tracks: English TrueHD and AC3, Turkish AC3; English and Turkish PGS.
    private val toyAudio = listOf(TrackInfo(1, "eng"), TrackInfo(2, "eng"), TrackInfo(3, "tur"))
    private val toySubs = listOf(TrackInfo(10, "eng"), TrackInfo(11, "tur"))

    private fun sub(tracks: List<TrackInfo>, want: String = "tr", audio: String? = "eng", dub: Boolean = false, forced: Boolean = true, saved: Int = -2) =
        TrackRules.subtitle(tracks, want, audio, dub, forced, saved)

    // ---- audio ----

    @Test fun audioInTheLanguageWanted() {
        assertEquals(TrackPick(1, false), TrackRules.audio(toyAudio, "en", -2))
        assertEquals(TrackPick(3, false), TrackRules.audio(toyAudio, "tr", -2))
    }

    @Test fun audioPickedByTheViewerWins() {
        assertEquals(TrackPick(2, true), TrackRules.audio(toyAudio, "en", 2))
    }

    @Test fun audioPickThatsGoneFallsBackToTheSettings() {
        assertEquals(TrackPick(1, false), TrackRules.audio(toyAudio, "en", 7))
    }

    @Test fun audioBibliographicCodes() {
        assertEquals(TrackPick(5, false), TrackRules.audio(listOf(TrackInfo(4, "eng"), TrackInfo(5, "ger")), "de", -2))
    }

    @Test fun audioNoneWantedOrNoneInThatLanguage() {
        assertNull(TrackRules.audio(toyAudio, "", -2))
        assertNull(TrackRules.audio(toyAudio, "fr", -2))
    }

    @Test fun turkishDubWhenTheFileHasNoTurkish() {
        val tracks = listOf(TrackInfo(1, "eng"), TrackInfo(9, null, dub = true))
        assertEquals(TrackPick(9, false), TrackRules.audio(tracks, "tr", -2))
        // The file's own Turkish comes before a dub.
        assertEquals(TrackPick(3, false), TrackRules.audio(toyAudio + TrackInfo(9, null, dub = true), "tr", -2))
        // Dubs are only for Turkish.
        assertEquals(TrackPick(1, false), TrackRules.audio(tracks, "en", -2))
    }

    // ---- subtitles ----

    @Test fun subtitleInTheLanguageWanted() {
        assertEquals(TrackPick(11, false), sub(toySubs))
        assertEquals(TrackPick(10, false), sub(toySubs, want = "en", audio = "tur"))
    }

    @Test fun noSubtitleWhenListeningInThatLanguage() {
        assertEquals(TrackPick(-1, false), sub(toySubs, audio = "tur"))
        assertEquals(TrackPick(-1, false), sub(toySubs, audio = "tr"))
    }

    @Test fun subtitleSettingsApplyUntilTheViewerPicks() {
        // -2: nothing picked for the file; the settings decide (subtitles once came up off because every play was saved as a pick).
        assertEquals(TrackPick(11, false), sub(toySubs, saved = -2))
        assertEquals(TrackPick(-1, true), sub(toySubs, saved = -1))
        assertEquals(TrackPick(10, true), sub(toySubs, saved = 10))
        assertEquals(TrackPick(11, false), sub(toySubs, saved = 42))
    }

    @Test fun fullSubtitleBeforeForced() {
        val tracks = listOf(TrackInfo(12, "tur", forced = true), TrackInfo(13, "tur"))
        assertEquals(TrackPick(13, false), sub(tracks))
        assertEquals(TrackPick(12, false), sub(listOf(TrackInfo(12, "tur", forced = true))))
    }

    @Test fun subtitleFilesByTheLanguageInTheirName() {
        val eng = TrackInfo(20, "eng", sidecar = true)
        val none = TrackInfo(21, null, sidecar = true)
        val tr = TrackInfo(22, "tr", sidecar = true)
        assertEquals(TrackPick(22, false), sub(listOf(eng, none, tr)))
        assertEquals(TrackPick(21, false), sub(listOf(eng, none)))
        assertEquals(TrackPick(-1, false), sub(listOf(eng)))
        // The film's own Turkish comes first, in its order.
        assertEquals(TrackPick(11, false), sub(toySubs + none))
    }

    @Test fun untaggedEmbeddedTrackIsntTakenForTheLanguage() {
        assertEquals(TrackPick(-1, false), sub(listOf(TrackInfo(30, null)), forced = false))
    }

    @Test fun forcedSubtitlesInTheAudiosLanguage() {
        val tracks = listOf(TrackInfo(40, "fre", forced = true), TrackInfo(41, "en", forced = true), TrackInfo(42, null, forced = true))
        assertEquals(TrackPick(41, false), sub(tracks))
        assertEquals(TrackPick(42, false), sub(tracks, audio = "spa"))
        assertEquals(TrackPick(-1, false), sub(tracks, forced = false))
    }

    @Test fun aDubBringsItsForcedSubtitles() {
        val tracks = listOf(TrackInfo(50, null, forced = true), TrackInfo(51, null, forced = true, sidecar = true, dub = true))
        assertEquals(TrackPick(51, false), sub(tracks, audio = "tr", dub = true))
        // Without the dub they stay off.
        assertEquals(TrackPick(50, false), sub(tracks, audio = "eng"))
    }

    // ---- animation languages ----

    @Test fun animationGenre() {
        assertTrue(TrackRules.isAnimation(listOf("Aile", "Animasyon")))
        assertTrue(TrackRules.isAnimation(listOf("Animation")))
        assertTrue(TrackRules.isAnimation(listOf("Мультфильм")))
        assertFalse(TrackRules.isAnimation(listOf("Aksiyon", "Macera")))
    }

    @Test fun animationUsesItsOwnLanguages() {
        // Preferred English audio with Turkish subtitles; cartoons in Turkish, no subtitles.
        val cartoon = "tr" to ""
        assertEquals(TrackPick(3, false) to "", TrackRules.audioFor(toyAudio, -2, "en", "tr", cartoon))
        // Not an animation: the preferred ones.
        assertEquals(TrackPick(1, false) to "tr", TrackRules.audioFor(toyAudio, -2, "en", "tr", null))
    }

    @Test fun animationWithoutItsAudioFallsBackToThePreferred() {
        val englishOnly = listOf(TrackInfo(1, "eng"), TrackInfo(2, "jpn"))
        assertEquals(TrackPick(1, false) to "tr", TrackRules.audioFor(englishOnly, -2, "en", "tr", "tr" to ""))
        // A Turkish dub from the NAS counts as Turkish audio.
        val withDub = englishOnly + TrackInfo(9, null, dub = true)
        assertEquals(TrackPick(9, false) to "", TrackRules.audioFor(withDub, -2, "en", "tr", "tr" to ""))
    }

    @Test fun animationSettingsThatFollowThePreferred() {
        assertEquals(TrackPick(1, false) to "", TrackRules.audioFor(toyAudio, -2, "en", "tr", TrackRules.SAME to ""))
        assertEquals(TrackPick(3, false) to "tr", TrackRules.audioFor(toyAudio, -2, "en", "tr", "tr" to TrackRules.SAME))
        assertEquals(TrackPick(1, false) to "tr", TrackRules.audioFor(toyAudio, -2, "en", "tr", TrackRules.SAME to TrackRules.SAME))
    }

    @Test fun viewersPickStillWinsInAnimation() {
        assertEquals(TrackPick(2, true) to "", TrackRules.audioFor(toyAudio, 2, "en", "tr", "tr" to ""))
    }

    // ---- skip intro / credits ----

    // Toy Story: "Opening Credits" from 2:03 to 4:01.
    private val toyChapters = listOf("Chapter 1" to 0L, "Opening Credits" to 123_000L, "Chapter 3" to 241_000L, "End Credits" to 4_500_000L)
    private fun check(position: Long, skippedChapter: Int = -1, marked: Pair<Long, Long>? = null, skippedMarked: Boolean = false, chapters: List<Pair<String, Long>> = toyChapters) =
        SkipRules.check(chapters, position, 4_860_000L, marked, skippedChapter, skippedMarked)

    @Test fun introFromChapterNames() {
        assertNull(check(60_000).kind)
        assertEquals(SkipKind.INTRO, check(130_000).kind)
        assertEquals(241_000L, check(130_000).target)
        assertNull(check(250_000).kind)
    }

    @Test fun creditsSkipToTheEnd() {
        val c = check(4_600_000)
        assertEquals(SkipKind.CREDITS, c.kind)
        assertEquals(4_860_000L, c.target)
    }

    @Test fun oneChapterIsntAnIntro() {
        assertNull(check(10_000, chapters = listOf("Opening" to 0L)).kind)
    }

    @Test fun skippedChapterIsOfferedAgainWhenComingBack() {
        // Skipped from inside it: not again while still in it.
        assertNull(check(130_000, skippedChapter = 1).kind)
        // Left it: forgotten, so going back into it offers the button again.
        val later = check(250_000, skippedChapter = 1)
        assertEquals(-1, later.skippedChapter)
        assertEquals(SkipKind.INTRO, check(130_000, skippedChapter = later.skippedChapter).kind)
    }

    @Test fun markedIntro() {
        val marked = 30_000L to 90_000L
        assertNull(check(20_000, marked = marked, chapters = emptyList()).kind)
        assertEquals(SkipKind.INTRO, check(50_000, marked = marked, chapters = emptyList()).kind)
        assertEquals(90_000L, check(50_000, marked = marked, chapters = emptyList()).target)
        // Not in its last two seconds.
        assertNull(check(88_500, marked = marked, chapters = emptyList()).kind)
        // Skipped: not again, until playback is back before it.
        assertNull(check(50_000, marked = marked, skippedMarked = true, chapters = emptyList()).kind)
        assertFalse(check(10_000, marked = marked, skippedMarked = true, chapters = emptyList()).skippedMarked)
    }

    @Test fun afterSkip() {
        assertEquals(1 to false, SkipRules.afterSkip(toyChapters, 130_000, 241_000, null, -1, false))
        assertEquals(-1 to true, SkipRules.afterSkip(emptyList(), 50_000, 90_000, 30_000L to 90_000L, -1, false))
    }

    private fun shown(controls: Boolean = false, alone: Boolean = true, paused: Boolean = false, panel: Boolean = false, upNext: Boolean = false, seeking: Boolean = false, onTrackButtons: Boolean = false) =
        SkipRules.buttonShown(true, true, paused, panel, upNext, controls, seeking, onTrackButtons, alone)

    @Test fun skipButtonAloneForItsFirstSecondsThenWithTheControls() {
        assertTrue(shown())
        assertFalse(shown(alone = false))
        assertTrue(shown(controls = true, alone = false))
        assertFalse(shown(controls = true, seeking = true))
        assertFalse(shown(controls = true, onTrackButtons = true))
    }

    @Test fun skipButtonHiddenWhilePausedOrUnderAPanel() {
        assertFalse(shown(paused = true))
        assertFalse(shown(panel = true))
        assertFalse(shown(upNext = true))
        assertFalse(SkipRules.buttonShown(false, true, false, false, false, false, false, false, true))
    }

    @Test fun skipButtonTimeCountsOnlyWhileAlone() {
        assertTrue(SkipRules.aloneCounts(playing = true, controls = false, panel = false, upNext = false))
        assertFalse(SkipRules.aloneCounts(playing = false, controls = false, panel = false, upNext = false))
        assertFalse(SkipRules.aloneCounts(playing = true, controls = true, panel = false, upNext = false))
    }
}
