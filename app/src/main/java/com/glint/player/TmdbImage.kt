package com.glint.player

/** A poster or background TMDB has for a title: its address, size in pixels, language (null: no text) and votes. */
data class TmdbImage(val url: String, val width: Int, val height: Int, val language: String?, val vote: Double) {
    /** Sharpness steps, as [sizeLabel] names them: 4K, 1440p, 1080p, 720p and below; 2000, 1000 px wide and below for posters. */
    fun tier(wide: Boolean) = if (wide) when {
        width >= 3840 -> 4; width >= 2560 -> 3; width >= 1920 -> 2; width >= 1280 -> 1; else -> 0
    } else when {
        width >= 2000 -> 2; width >= 1000 -> 1; else -> 0
    }

    /** What the grid shows on it: "4K", "1080p", "720p" for backgrounds, "2000×3000" for posters; null when unknown. */
    fun sizeLabel(wide: Boolean): String? = when {
        width <= 0 || height <= 0 -> null
        !wide -> "$width×$height"
        width >= 3840 -> "4K"
        width >= 2560 -> "1440p"
        width >= 1920 -> "1080p"
        width >= 1280 -> "720p"
        else -> "${height}p"
    }

    companion object {
        /**
         * Posters by language in [languages]' order ("" for no text; others after), then sharpest step first, then best
         * voted. Backgrounds sharpest step first, then by language, then votes: a sharp one with text beats a soft one
         * without. Steps rather than pixels, so a few pixels more don't win over many more votes.
         */
        fun order(images: List<TmdbImage>, languages: List<String>, wide: Boolean): List<TmdbImage> {
            fun rank(i: TmdbImage) = languages.indexOf(i.language ?: "").let { if (it < 0) languages.size else it }
            val sharp = compareByDescending<TmdbImage> { it.tier(wide) }
            return images.sortedWith(
                if (wide) sharp.thenBy(::rank).thenByDescending { it.vote }
                else compareBy<TmdbImage>(::rank).then(sharp).thenByDescending { it.vote }
            )
        }
    }
}
