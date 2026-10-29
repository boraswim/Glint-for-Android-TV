package com.glint.player

import java.util.Locale

/** What a file name says about itself. */
sealed interface Parsed {
    data class MovieName(val title: String, val year: String) : Parsed
    data class EpisodeName(val show: String, val year: String, val season: Int, val episode: Int) : Parsed
}

/**
 * Reads scene-style and hand-made names:
 *   "Toy.Story.3.2010.UHD.BluRay.2160p...mkv", "Madagascar 2005 1080p Blu-ray Remux...",
 *   "Show.Name.S01E02.1080p...", "Show Name - 1x02 - Title", ".../Show Name/Season 1/02 - Title.mkv"
 */
object Names {
    val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "mts", "wmv", "webm", "mpg", "mpeg", "flv", "vob", "divx", "3gp", "ogv", "iso", "strm")
    val SUB_EXT = setOf("srt", "ass", "ssa", "vtt", "sub", "smi")
    val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    private val EPISODE = Regex("""(?i)(?:^|[\s._\-\[(])s(\d{1,2})[\s._\-]?e(\d{1,3})""")
    private val CROSS = Regex("""(?i)(?:^|[\s._\-\[(])(\d{1,2})x(\d{2,3})(?:[\s._\-\])]|$)""")
    private val SEASON_DIR = Regex("""(?i)^(?:season|sezon|staffel|saison|s)[\s._\-]*(\d{1,2})\b.*""")
    private val SEASON_TAG = Regex("""(?i)[\s._\-]s(\d{1,2})(?:[\s._\-]|$).*""")
    private val LEADING_NUMBER = Regex("""^(\d{1,3})(?:[\s._\-]|$)""")
    private val YEAR = Regex("""(?<![\p{L}\p{N}])(19[2-9]\d|20[0-4]\d)(?![\p{L}\p{N}])""")
    private val BRACKETS = Regex("""\[[^\]]*\]|\{[^}]*\}""")

    // Release-name words; the title ends where the first of these starts.
    private val JUNK = setOf(
        "2160p", "1080p", "1080i", "720p", "576p", "480p", "4k", "uhd", "hdr", "hdr10", "hdr10p", "hdr10plus", "dv", "dovi",
        "bluray", "blu", "bdrip", "brrip", "bdremux", "remux", "web", "webdl", "webrip", "hdtv", "dvdrip", "dvd", "hdrip",
        "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "divx", "10bit", "8bit", "truehd", "atmos", "dts", "dtshd",
        "ddp", "dd", "ac3", "eac3", "aac", "flac", "repack", "proper", "extended", "theatrical", "unrated", "remastered",
        "imax", "hybrid", "multi", "dual", "internal", "limited", "complete", "directors", "criterion",
    )

    fun parse(path: String): Parsed {
        val parts = path.split('/')
        val file = parts.last().substringBeforeLast('.')
        val dirs = parts.dropLast(1)

        EPISODE.find(file)?.let { m -> return episode(file, m.range.first, m.groupValues[1], m.groupValues[2], dirs) }
        CROSS.find(file)?.let { m -> return episode(file, m.range.first, m.groupValues[1], m.groupValues[2], dirs) }

        // "Show/Season 2/05 - Name.mkv": number-only file in a season folder.
        val seasonDir = dirs.lastOrNull()?.let { SEASON_DIR.matchEntire(it.trim()) }
        val leading = LEADING_NUMBER.find(file.trim())
        if (seasonDir != null && leading != null && dirs.size >= 2) {
            val (title, year) = titleAndYear(dirs[dirs.size - 2])
            return Parsed.EpisodeName(title, year, seasonDir.groupValues[1].toInt(), leading.groupValues[1].toInt())
        }

        // Use the folder name when the file name is generic ("movie.mkv", "BDMV" rips and the like).
        val (title, year) = titleAndYear(file)
        if ((title.isBlank() || title.lowercase(Locale.ROOT) in GENERIC) && dirs.isNotEmpty()) {
            val (t, y) = titleAndYear(dirs.last())
            return Parsed.MovieName(t, y.ifEmpty { year })
        }
        return Parsed.MovieName(title, year)
    }

    private fun episode(file: String, at: Int, season: String, number: String, dirs: List<String>): Parsed.EpisodeName {
        var (title, year) = titleAndYear(file.substring(0, at))
        // Folder name, skipping "Season 1" folders and cutting "Show.1978.S01.1080p" at the season tag.
        val folder = dirs.lastOrNull { SEASON_DIR.matchEntire(it.trim()) == null }?.let { titleAndYear(SEASON_TAG.replace(it, "")) }
        if (title.isBlank() && folder != null) {
            title = folder.first; year = folder.second
        } else if (year.isEmpty() && folder != null && normalise(folder.first) == normalise(title)) {
            year = folder.second   // "Show.S01E01.mkv" inside "Show.1978.S01..."
        }
        return Parsed.EpisodeName(title, year, season.toInt(), number.toInt())
    }

    /** "Toy.Story.3.2010.UHD..." → ("Toy Story 3", "2010"). Takes the last plausible year, so "2001 A Space Odyssey 1968" works. */
    fun titleAndYear(raw: String): Pair<String, String> {
        val cleaned = BRACKETS.replace(raw, " ")
            .replace(Regex("""(?<=\w)\.(?=\w)|_"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        // Leading release words ("204kbps.Moonwalker 1988") aren't part of the title either.
        val words = cleaned.split(' ').dropWhile { isJunk(it) }.ifEmpty { listOf("") }
        // Title stops at the first release word.
        var end = words.indexOfFirst(::isJunk)
        if (end < 0) end = words.size
        var head = words.subList(0, end).joinToString(" ")
        var year = ""
        val years = YEAR.findAll(head).toList()
        val y = years.lastOrNull { it.range.first > 0 }
        if (y != null) {
            year = y.value
            head = head.substring(0, y.range.first)
        } else {
            // "Avatar.Extended.Cut.2009.UHD": the year can come after an edition word.
            year = words.drop(end).take(4).firstNotNullOfOrNull { YEAR.matchEntire(it.trim('.', '(', ')', '-'))?.value }.orEmpty()
        }
        val title = head.trim(' ', '-', '(', ')', '.', ',', '[').replace(Regex("""\s+"""), " ")
            // "G.O.R.A." became "G O R A"; join the letters back up.
            .replace(Regex("""(?<![\p{L}\p{N}])(?:\p{L} ){2,}\p{L}(?![\p{L}\p{N}])""")) { it.value.replace(" ", "") }
        return title to year
    }

    private val RATE = Regex("""\d+(kbps|mbps|fps|ch|khz|bit)""")

    private fun isJunk(word: String): Boolean {
        val w = word.lowercase(Locale.ROOT).trim('-', '(', ')', '.', ',')
        return w in JUNK || RATE.matches(w)
    }

    private val EDITION_TAG = Regex("""\{edition-([^}]+)\}""", RegexOption.IGNORE_CASE)
    private val EDITIONS = listOf(
        "Director's Cut" to Regex("""(?i)director'?s[ ._-]?cut"""),
        "Extended" to Regex("""(?i)extended"""),
        "Theatrical" to Regex("""(?i)theatrical"""),
        "Unrated" to Regex("""(?i)unrated"""),
        "Uncut" to Regex("""(?i)uncut"""),
        "IMAX" to Regex("""(?i)(?<![a-z])imax(?![a-z])"""),
        "Remastered" to Regex("""(?i)remastered"""),
        "Ultimate" to Regex("""(?i)ultimate[ ._-]?(edition|cut)"""),
        "Special Edition" to Regex("""(?i)special[ ._-]?edition"""),
        "Final Cut" to Regex("""(?i)final[ ._-]?cut"""),
    )

    /** "Blade.Runner.1982.Final.Cut…" → "Final Cut"; `{edition-My Cut}` wins. */
    fun edition(name: String): String =
        EDITION_TAG.find(name)?.groupValues?.get(1)?.trim()
            ?: EDITIONS.filter { (_, r) -> r.containsMatchIn(name) }.joinToString(" · ") { it.first }

    private val TMDB_TAG = Regex("""(?i)[{\[]tmdb(?:id)?[-=]?(\d+)[}\]]""")
    private val IMDB_TAG = Regex("""(?i)[{\[](?:imdb(?:id)?[-=]?)?(tt\d{6,9})[}\]]""")

    /** `{tmdb-603}` or `[tmdbid-603]` anywhere in the path. */
    fun tmdbTag(path: String): Int? = TMDB_TAG.find(path)?.groupValues?.get(1)?.toIntOrNull()
    fun imdbTag(path: String): String? = IMDB_TAG.find(path)?.groupValues?.get(1)

    private val EXTRA_SUFFIX = Regex("""(?i)[-_. ](trailer|featurette|behindthescenes|behind[-_. ]the[-_. ]scenes|deleted|deletedscene|interview|scene|short|other|extra)$""")
    val EXTRA_FOLDERS = mapOf(
        "trailers" to "trailer", "featurettes" to "featurette", "behind the scenes" to "behind the scenes",
        "deleted scenes" to "deleted", "interviews" to "interview", "scenes" to "scene", "shorts" to "short",
        "extras" to "other", "other" to "other",
    )

    /** "Inception-trailer.mkv" → ("Inception", "trailer"), or null for an ordinary video. */
    fun extraOf(base: String): Pair<String, String>? = EXTRA_SUFFIX.find(base)?.let { m ->
        base.substring(0, m.range.first) to m.groupValues[1].lowercase(Locale.ROOT).replace(Regex("[-_. ]"), "").let {
            when (it) { "behindthescenes" -> "behind the scenes"; "deletedscene" -> "deleted"; "extra" -> "other"; else -> it }
        }
    }

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun normalise(s: String) = s.lowercase(Locale.ROOT)
        .replace("&", "and")
        .replace(Regex("""['’`]"""), "")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private val GENERIC = setOf("movie", "film", "video", "main", "feature", "title", "title t00", "00000")
}
