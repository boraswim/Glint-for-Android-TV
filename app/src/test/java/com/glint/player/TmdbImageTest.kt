package com.glint.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbImageTest {
    private fun img(name: String, w: Int, vote: Double, lang: String? = null) = TmdbImage(name, w, w * 9 / 16, lang, vote)

    @Test fun sharperCopyFirstEvenWithFewerVotes() {
        // Mr. Peabody: the same picture at 1280 and at 3840 px; the smaller copy has the better votes.
        val list = listOf(img("small", 1280, 5.5), img("big", 3840, 5.2), img("other", 1920, 5.4))
        assertEquals(listOf("big", "other", "small"), TmdbImage.order(list, listOf(""), wide = true).map { it.url })
    }

    @Test fun stepsFollowTheLabels() {
        val list = listOf(img("hd", 1920, 6.0), img("qhd", 2560, 5.0))
        assertEquals(listOf("qhd", "hd"), TmdbImage.order(list, listOf(""), wide = true).map { it.url })
    }

    @Test fun votesDecideWithinTheSameStep() {
        val list = listOf(img("a", 1920, 5.1), img("b", 2000, 5.6))
        assertEquals(listOf("b", "a"), TmdbImage.order(list, listOf(""), wide = true).map { it.url })
    }

    @Test fun languageStillComesFirst() {
        val list = listOf(img("en4k", 3840, 6.0, "en"), img("plain", 1920, 5.0))
        assertEquals(listOf("plain", "en4k"), TmdbImage.order(list, listOf("", "en"), wide = true).map { it.url })
    }

    @Test fun labels() {
        assertEquals("4K", img("x", 3840, 0.0).sizeLabel(true))
        assertEquals("1080p", img("x", 1920, 0.0).sizeLabel(true))
        assertEquals("720p", img("x", 1280, 0.0).sizeLabel(true))
        assertEquals("2000×3000", TmdbImage("p", 2000, 3000, null, 0.0).sizeLabel(false))
        assertEquals(null, TmdbImage("p", 0, 0, null, 0.0).sizeLabel(true))
    }
}
