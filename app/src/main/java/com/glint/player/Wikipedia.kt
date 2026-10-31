package com.glint.player

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Life stories TMDB has no text for in the viewer's language. The person's Wikidata id, which TMDB gives, names their
 * exact article in that language's Wikipedia, so a namesake can't be picked up. No key is needed.
 */
object Wikipedia {
    /** The article's text up to its lists (films, awards, sources), or "" if this Wikipedia has no article on them. */
    fun biography(wikidataId: String, language: String): String {
        val site = "${language}wiki"
        val links = get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${enc(wikidataId)}&props=sitelinks&sitefilter=$site&format=json")
        val title = links.optJSONObject("entities")?.optJSONObject(wikidataId)?.optJSONObject("sitelinks")?.optJSONObject(site)?.optString("title").orEmpty()
        if (title.isBlank()) return ""
        val pages = get("https://$language.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&exsectionformat=wiki&redirects=1&format=json&titles=${enc(title)}")
            .optJSONObject("query")?.optJSONObject("pages") ?: return ""
        val page = pages.keys().asSequence().firstOrNull()?.let { pages.optJSONObject(it) } ?: return ""
        return tidy(page.optString("extract"))
    }

    /** Stops at the first section that's a list rather than a story, and writes the remaining headings as plain lines. */
    internal fun tidy(article: String): String {
        val lines = article.lines()
        val end = lines.indexOfFirst { line ->
            val heading = HEADING.matchEntire(line.trim())?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            heading != null && (LISTS_ANYWHERE.any { it in heading } || LISTS.any { heading.startsWith(it) })
        }
        return lines.take(if (end < 0) lines.size else end)
            .joinToString("\n") { line -> HEADING.matchEntire(line.trim())?.groupValues?.get(1) ?: line }
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private val HEADING = Regex("""=+\s*(.+?)\s*=+""")
    private val LISTS_ANYWHERE = listOf("filmogra", "diskogra", "discogra", "фильмограф", "дискограф")
    private val LISTS = listOf(
        "kaynak", "dış bağlantı", "notlar", "ödül", "dipnot", "filmler", "filmleri", "diziler", "dizileri", "albüm", "televizyon",
        "references", "external links", "notes", "awards", "see also",
        "weblinks", "einzelnachweise", "auszeichnung", "literatur", "siehe auch",
        "références", "liens externes", "distinctions", "voir aussi", "notes et références",
        "referencias", "enlaces externos", "premios", "véase también",
        "note", "collegamenti esterni", "riconoscimenti", "premi", "voci correlate",
        "referências", "ligações externas", "prêmios", "prémios", "ver também",
        "externe links", "bronnen", "prijzen", "referenties",
        "przypisy", "linki zewnętrzne", "nagrody", "zobacz też",
        "примечания", "ссылки", "награды", "литература", "см. также",
    )

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        // Wikimedia turns away requests that don't name their app.
        c.setRequestProperty("User-Agent", "Glint/${BuildConfig.VERSION_NAME} (https://github.com/boraswim/Glint-for-Android-TV)")
        try {
            if (c.responseCode != 200) throw IOException("Wikipedia HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
