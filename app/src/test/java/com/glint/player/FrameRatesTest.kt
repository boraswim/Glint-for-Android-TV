package com.glint.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRatesTest {
    private val tv = listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)

    private fun best(rates: List<Float>, fps: Double) = bestRefreshRate(rates, fps)?.let { rates[it] }

    @Test fun exactRateWins() {
        assertEquals(23.976f, best(tv, 23.976))
        assertEquals(24f, best(tv, 24.0))
        assertEquals(25f, best(tv, 25.0))
        assertEquals(59.94f, best(tv, 59.94))
    }

    @Test fun fixedSixtyHzHasNoMatchForFilms() {
        assertNull(best(listOf(60f), 23.976))
        assertNull(best(listOf(60f), 24.0))
        assertNull(best(listOf(60f), 25.0))
    }

    @Test fun fixedSixtyHzFitsThirtyAndSixty() {
        assertEquals(60f, best(listOf(60f), 30.0))
        assertEquals(60f, best(listOf(60f), 60.0))
    }

    @Test fun multiplesCover100And120Hz() {
        assertEquals(120f, best(listOf(60f, 120f), 24.0))
        assertEquals(100f, best(listOf(60f, 100f), 25.0))
        assertEquals(50f, best(listOf(50f, 60f, 100f), 25.0))
    }

    @Test fun lowestMultipleWins() {
        assertEquals(24f, best(listOf(24f, 48f, 120f), 24.0))
    }

    @Test fun nearbyRateIsAcceptedWhenExactIsMissing() {
        assertEquals(24f, best(listOf(24f, 60f), 23.976))
        assertEquals(60f, best(listOf(60f), 59.94))
    }

    @Test fun twentyFiveDoesNotTakeSixty() {
        assertNull(best(listOf(24f, 30f, 60f), 25.0))
    }

    @Test fun millisecondTimestampsGiveFilmRates() {
        // 23.976: 41.708 ms a frame, stored rounded to whole milliseconds
        val at23976 = (1..96).map { (Math.round(it * 41.7083) - Math.round((it - 1) * 41.7083)) * 1000 }
        assertEquals(23.976, fpsFromFrameGaps(at23976)!!, 0.001)
        assertEquals(25.0, fpsFromFrameGaps(List(96) { 40_000L })!!, 0.001)
        assertEquals(24.0, fpsFromFrameGaps(List(96) { if (it % 3 == 0) 41_000L else 42_000L }.let { g -> g.map { if (it == 41_000L) 41_000L else 42_000L } })!!, 0.05)
    }

    @Test fun droppedFramesDoNotChangeTheRate() {
        val gaps = List(96) { if (it % 20 == 0) 125_000L else 40_000L }
        assertEquals(25.0, fpsFromFrameGaps(gaps)!!, 0.001)
    }

    @Test fun containerNames() {
        assertEquals("Matroska / WebM", containerName("A.Film.2013.mkv"))
        assertEquals("MP4", containerName("a.MP4"))
        assertEquals("MPEG-TS", containerName("b.m2ts"))
    }

    @Test fun muxerIsReadFromTheHeader() {
        val name = "libebml v1.4.5 + libmatroska v1.7.1".toByteArray()
        val head = byteArrayOf(0x1A, 0x45, 0x4D, 0x23, 0x11) + byteArrayOf(0x4D, 0x80.toByte(), (0x80 or name.size).toByte()) + name + byteArrayOf(0x57, 0x41)
        assertEquals("libebml v1.4.5 + libmatroska v1.7.1", matroskaMuxer(head))
    }

    @Test fun noMuxerInOtherBytes() {
        assertNull(matroskaMuxer(ByteArray(64) { 0 }))
        assertNull(matroskaMuxer(byteArrayOf(0x4D, 0x80.toByte(), 0x85.toByte(), 1, 2, 3, 4, 5)))
    }

    @Test fun nothingToMeasure() {
        assertNull(fpsFromFrameGaps(emptyList()))
    }
}
