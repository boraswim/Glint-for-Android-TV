package com.glint.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SidecarsTest {
    @Test fun languageAfterTheVideoName() {
        assertEquals("tr", sidecarLanguageCode("Film.2013.tr.srt", "Film.2013"))
        assertEquals("eng", sidecarLanguageCode("Film.2013.eng.srt", "Film.2013"))
        assertEquals("tr", sidecarLanguageCode("Film.2013.Turkish.srt", "Film.2013"))
        assertEquals("de", sidecarLanguageCode("Film.2013.german.forced.srt", "Film.2013"))
    }

    @Test fun noLanguage() {
        assertEquals(null, sidecarLanguageCode("Film.2013.srt", "Film.2013"))
        assertEquals(null, sidecarLanguageCode("Film.2013.1080p.srt", "Film.2013"))
    }

    @Test fun titleWordsAreNotLanguages() {
        // "Ada" is a language code too; it's the title here.
        assertEquals(null, sidecarLanguageCode("Ada.2005.srt", "Ada.2005"))
        assertEquals("tr", sidecarLanguageCode("Ada.2005.tr.srt", "Ada.2005"))
    }

    @Test fun anyNameWhenItDoesntStartWithTheVideo() {
        assertEquals("tr", sidecarLanguageCode("altyazi.tr.srt", "Film.2013"))
        assertEquals(null, sidecarLanguageCode("altyazi.srt", "Film.2013"))
    }
}
