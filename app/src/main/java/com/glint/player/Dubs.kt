package com.glint.player

import java.text.Normalizer
import java.util.Locale

/**
 * Pairs dub audio files (a folder like "Film sesleri" full of "Aladdin.1992.640Kbps…TR.ac3")
 * with the films they belong to. Dub names follow no single scheme: English or Turkish titles,
 * "Part.3" for "Part III", "Antman" for "Ant-Man", so titles are compared in a squashed form.
 */
object Dubs {
    /** Folders whose files are dubs rather than films: "Film sesleri", "TR Ses", "Dublajlar"… */
    val FOLDER = Regex("""(?i)(^|[\s._-])(ses|sesler|sesleri|dublaj|dublajlar|dubs?)($|[\s._-])""")
    val AUDIO_EXT = setOf("ac3", "eac3", "ec3", "dts", "dtshd", "thd", "truehd", "mka", "aac", "flac", "mkv", "mp4", "m4a")

    data class Film(val id: String, val titles: List<String>, val year: String)

    /** Film id → its dubs, best first. */
    fun match(films: List<Film>, dubs: List<DubFile>): Map<String, List<DubFile>> {
        val keyed = films.map { f -> f to f.titles.map(::key).filter { it.isNotEmpty() }.toSet() }
        val out = HashMap<String, MutableList<DubFile>>()
        for (d in dubs) {
            val (title, year) = parse(d.path)
            val k = key(title)
            // One-character titles ("9") are only trusted with a year.
            if (k.isEmpty() || (k.length < 2 && year.isEmpty())) continue
            val film = pick(keyed, k, title, year) ?: continue
            out.getOrPut(film.id) { ArrayList() } += d
        }
        return out
    }

    private fun pick(keyed: List<Pair<Film, Set<String>>>, k: String, title: String, year: String): Film? {
        fun yearOk(fy: String, slack: Int) = year.isEmpty() || fy.isEmpty() || kotlin.math.abs(fy.toInt() - year.toInt()) <= slack
        // Same title (and a year at most one off).
        val exact = keyed.filter { (f, keys) -> k in keys && yearOk(f.year, if (k.length < 2) 0 else 1) }
        if (exact.isNotEmpty()) return exact.minByOrNull { (f, _) -> if (year.isEmpty() || f.year.isEmpty()) 9 else kotlin.math.abs(f.year.toInt() - year.toInt()) }!!.first
        // Same year and one title starts the other: "Star Wars Episode 4" ↔ "Star Wars Episode 4 A New Hope".
        if (year.isEmpty()) return null
        val prefix = keyed.filter { (f, keys) ->
            f.year == year && keys.any { fk -> minOf(fk.length, k.length) >= 6 && (fk.startsWith(k) || k.startsWith(fk)) }
        }
        if (prefix.size == 1) return prefix.single().first
        // Same year and the words line up: "Indiana Jones and the Last Crusade" ↔ "Indiana Jones 3 and the Last Crusade",
        // "Kanca – Hook" ↔ "Hook".
        val words = wordsOf(title)
        val similar = keyed.filter { (f, _) ->
            f.year == year && f.titles.any { t ->
                val fw = wordsOf(t)
                val common = (fw intersect words).size
                fw.isNotEmpty() && words.isNotEmpty() && (common == minOf(fw.size, words.size) || common.toDouble() / (fw union words).size >= 0.6)
            }
        }
        return similar.singleOrNull()?.first
    }

    private fun wordsOf(t: String) = words(t).filter { it.toIntOrNull() == null }.toSet()

    /** Title and year from a dub's file name, or its folder's when the file name says nothing useful. */
    fun parse(path: String): Pair<String, String> {
        val parts = path.split('/')
        // "+1100ver", "artı.800ms ver": a note about the audio offset, not part of the title.
        val name = parts.last().substringBeforeLast('.').replace(VERSION, " ")
        val own = Names.titleAndYear(name)
        if (own.second.isNotEmpty() || parts.size < 2) return own
        val folder = parts[parts.size - 2]
        if (FOLDER.containsMatchIn(folder)) return own
        val fromFolder = Names.titleAndYear(folder)
        return if (fromFolder.second.isNotEmpty()) fromFolder else own
    }

    /** "Back to the Future Part III" → "backtothefuture3"; "Altıncı His" → "altincihis". */
    fun key(title: String): String = words(title).joinToString("")

    /** Title words, accent-free, with numbers unified and dub-file chatter ("TR Ses", "Türkçe") dropped. */
    private fun words(title: String): List<String> {
        val plain = Normalizer.normalize(title.replace('ı', 'i').replace('İ', 'I'), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
        val words = Names.normalise(plain).split(' ').filter { it.isNotEmpty() }
            .map { ROMAN[it] ?: it }
            .filter { it !in STOP && !(it.length >= 3 && it.all(Char::isDigit) && it.length != 4) }
            .toMutableList()
        if (words.size > 1 && words.first() == "the") words.removeAt(0)                 // "Matrix" is "The Matrix"
        if (words.size > 1 && words.last() == "1") words.removeAt(words.lastIndex)     // "Cars 1" is just "Cars"
        return words
    }

    /** What the player calls a dub track. */
    fun label(d: DubFile, videoName: String): String {
        val name = d.path.lowercase(Locale.ROOT)
        val ext = name.substringAfterLast('.').uppercase(Locale.ROOT)
        val codec = when {
            "dts-hd" in name || "dtshd" in name -> "DTS-HD"
            "e-ac3" in name || "eac3" in name || "dd+" in name || ext == "EAC3" -> "Dolby Digital+"
            ext == "DTS" || ".dts" in name -> "DTS"
            ext == "AC3" || "ac3" in name || ".dd." in name -> "Dolby Digital"
            ext == "AAC" -> "AAC"
            else -> ext
        }
        val channels = Regex("""(\d)ch""").find(name)?.groupValues?.get(1)?.let { if (it == "6") "5.1" else if (it == "2") "2.0" else "${it}ch" }
        val synced = "framestor" in name && "framestor" in videoName.lowercase(Locale.ROOT)
        return listOfNotNull(codec, channels, if (synced) "FraMeSToR" else null).joinToString(" ")
    }

    /** Dubs synced to this exact release first. */
    fun ordered(dubs: List<DubFile>, videoName: String): List<DubFile> =
        dubs.sortedByDescending { "framestor" in it.path.lowercase(Locale.ROOT) && "framestor" in videoName.lowercase(Locale.ROOT) }

    private val VERSION = Regex("""(?i)[+]\s*\d+\s*(ms)?\s*\.?\s*ver|art[iı][. ]*\d+\s*(ms)?[. ]*ver""")
    private val STOP = setOf("part", "bolum", "ses", "sesi", "dosyasi", "turkce", "turkish", "audio", "dublaj", "tr", "full", "izle", "hd", "kbps", "fps")
    private val ROMAN = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10")
}
