package com.glint.player

import kotlin.math.abs

private val FILM_RATES = listOf(23.976, 24.0, 25.0, 29.97, 30.0, 50.0, 59.94, 60.0)

/**
 * Which of a TV's refresh rates shows a film without judder, as an index, or null if none does: a whole multiple
 * of the film's rate. The lowest multiple wins (24 Hz over 120), then the closest (23.976 over 24).
 */
fun bestRefreshRate(refreshRates: List<Float>, fps: Double): Int? =
    refreshRates.indices.mapNotNull { i ->
        val ratio = refreshRates[i] / fps
        val multiple = Math.round(ratio)
        if (multiple in 1..5 && abs(ratio - multiple) < 0.01) Triple(i, multiple, abs(ratio - multiple)) else null
    }.minWithOrNull(compareBy({ it.second }, { it.third }))?.first

/**
 * A film's frame rate from the gaps between its frames' timestamps (microseconds). Averaged, as files store them
 * in whole milliseconds (41, 42, 42 for 23.976), leaving out jumps from dropped frames and seeks.
 */
fun fpsFromFrameGaps(gapsUs: List<Long>): Double? {
    if (gapsUs.isEmpty()) return null
    val middle = gapsUs.sorted()[gapsUs.size / 2]
    if (middle <= 0) return null
    val steady = gapsUs.filter { it < middle * 3 / 2 }
    val fps = 1_000_000.0 * steady.size / steady.sum()
    return FILM_RATES.minByOrNull { abs(it - fps) }?.takeIf { abs(it - fps) / it < 0.02 } ?: fps
}
