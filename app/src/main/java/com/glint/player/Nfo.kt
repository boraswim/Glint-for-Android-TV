package com.glint.player

/** The parts of a Kodi/tinyMediaManager .nfo (or .xml) the library uses. */
data class NfoData(val title: String?, val year: String?, val plot: String?, val tmdbId: Int?, val imdbId: String?)

object Nfo {
    private fun tag(xml: String, name: String) =
        Regex("""<$name(?:\s[^>]*)?>\s*(?:<!\[CDATA\[)?(.*?)(?:]]>)?\s*</$name>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun uniqueId(xml: String, type: String) =
        Regex("""<uniqueid[^>]*type="$type"[^>]*>\s*([^<]+?)\s*</uniqueid>""", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)

    fun parse(xml: String): NfoData {
        val imdb = uniqueId(xml, "imdb") ?: tag(xml, "imdbid") ?: tag(xml, "id")?.takeIf { it.startsWith("tt") }
        val tmdb = (uniqueId(xml, "tmdb") ?: tag(xml, "tmdbid"))?.toIntOrNull()
        return NfoData(
            title = tag(xml, "title")?.unescape(),
            year = tag(xml, "year") ?: tag(xml, "premiered")?.take(4),
            plot = tag(xml, "plot")?.unescape(),
            tmdbId = tmdb,
            imdbId = imdb,
        )
    }

    private fun String.unescape() = replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
}
