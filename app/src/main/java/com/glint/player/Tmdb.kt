package com.glint.player

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SearchHit(
    val id: Int, val title: String, val original: String, val year: String,
    val poster: String?, val overview: String, val isTv: Boolean,
)

/**
 * themoviedb.org: matches names to films and series, then fetches artwork, plots, cast and
 * episode details. Accepts a v3 API key or a v4 read access token; [keyOf] is asked each time, so a key the
 * viewer enters in Settings takes effect at once.
 */
class Tmdb(private val keyOf: () -> String, private val language: () -> String, private val artLanguage: () -> String = language) {
    private val key get() = keyOf()


    /** The language to ask TMDB for; "original" needs the title's own, which searches don't have yet. */
    private fun lang(original: String? = null) = language().let { if (it == "original") original?.ifBlank { null } ?: "en-US" else it }

    val enabled get() = key.isNotBlank()

    /** Whether TMDB accepts the key. Call off the main thread. */
    fun works(): Boolean = enabled && runCatching { get("configuration"); true }.getOrDefault(false)

    fun search(query: String, tv: Boolean, year: String = ""): List<SearchHit> {
        val kind = if (tv) "tv" else "movie"
        val yearParam = when {
            year.isEmpty() -> ""
            tv -> "&first_air_date_year=$year"
            else -> "&year=$year"
        }
        val json = JSONObject(get("search/$kind?query=${enc(query)}$yearParam&include_adult=false&language=${lang()}"))
        val results = json.getJSONArray("results")
        return (0 until results.length()).map { i ->
            val o = results.getJSONObject(i)
            SearchHit(
                id = o.getInt("id"),
                title = o.optString(if (tv) "name" else "title"),
                original = o.optString(if (tv) "original_name" else "original_title"),
                year = o.optString(if (tv) "first_air_date" else "release_date").take(4),
                poster = o.img("poster_path", "w342"),
                overview = o.optString("overview"),
                isTv = tv,
            )
        }
    }

    /**
     * Best TMDB id for a parsed name, or 0. Release names often differ from TMDB titles
     * ("Jurassic Park 2 The Lost World", "Star Wars Episode V ..."), so a few variants are
     * tried and every result is scored on title overlap and year.
     */
    fun match(title: String, year: String, tv: Boolean): Int {
        if (title.isBlank()) return 0
        val queries = LinkedHashSet<String>()
        queries += title
        val noNumbers = title.replace(Regex("""(?i)\b(episode|part|chapter)\s+([ivx]+|\d+)\b"""), " ")
            .replace(Regex("""\b(\d{1,2}|[IVX]{1,4})\b"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (noNumbers.isNotBlank()) queries += noNumbers
        // "Jurassic Park 2 The Lost World" → "The Lost World"
        Regex("""\b(?:\d{1,2}|[IVX]{1,4})\s+(.{4,})$""").find(title)?.let { queries += it.groupValues[1] }
        title.split(Regex("""\s+-\s+|:""")).firstOrNull()?.takeIf { it != title && it.length > 3 }?.let { queries += it.trim() }

        var best = 0
        var bestScore = 0.0
        val wanted = Names.normalise(title).split(' ').toSet()
        for (q in queries) {
            val hits = runCatching { search(q, tv, year) }.getOrDefault(emptyList()) +
                if (year.isNotEmpty()) runCatching { search(q, tv) }.getOrDefault(emptyList()).take(5) else emptyList()
            for ((rank, h) in hits.withIndex()) {
                val names = listOf(h.title, h.original).map { Names.normalise(it).split(' ').toSet() }
                val overlap = names.maxOf { n -> if (n.isEmpty()) 0.0 else (n intersect wanted).size.toDouble() / (n union wanted).size }
                val yearScore = when {
                    year.isEmpty() || h.year.isEmpty() -> 0.0
                    h.year == year -> 1.0
                    kotlin.math.abs(h.year.toInt() - year.toInt()) == 1 -> 0.6
                    else -> -0.8
                }
                val score = overlap * 2 + yearScore - rank * 0.05
                if (score > bestScore) {
                    bestScore = score; best = h.id
                }
            }
            if (bestScore >= 2.5) break
        }
        return if (bestScore >= 0.9) best else 0
    }

    fun movie(id: Int, file: VideoFile, fallbackTitle: String): Movie {
        val o = details("movie/$id", "credits,release_dates,images")
        val crew = o.optJSONObject("credits")?.optJSONArray("crew")
        val director = crew?.objects()?.firstOrNull { it.optString("job") == "Director" }?.optString("name").orEmpty()
        val collection = o.optJSONObject("belongs_to_collection")
        return Movie(
            file = file,
            title = o.optString("title").ifBlank { fallbackTitle },
            year = o.optString("release_date").take(4),
            tmdbId = id,
            overview = o.optString("overview"),
            tagline = o.optString("tagline"),
            poster = o.img("poster_path", "w500"),
            backdrop = o.img("backdrop_path", "w1280"),
            logo = logo(o),
            rating = o.optDouble("vote_average", 0.0),
            runtime = o.optInt("runtime"),
            genres = o.optJSONArray("genres")?.objects()?.map { it.optString("name") }.orEmpty(),
            certification = movieCertification(o),
            director = director,
            cast = cast(o),
            collectionId = collection?.optInt("id") ?: 0,
            collectionName = collection?.optString("name").orEmpty(),
            collectionPoster = collection?.img("poster_path", "w500"),
            collectionBackdrop = collection?.img("backdrop_path", "w1280"),
            releaseDate = o.optString("release_date"),
        )
    }

    /** Series details plus every season's episodes we have files for. */
    fun show(id: Int, fallbackTitle: String, seasons: Set<Int>): Pair<Show, Map<Pair<Int, Int>, JSONObject>> {
        val o = details("tv/$id", "aggregate_credits,content_ratings,images")
        val seasonPosters = HashMap<Int, String>()
        o.optJSONArray("seasons")?.objects()?.forEach { s -> s.img("poster_path", "w342")?.let { seasonPosters[s.optInt("season_number")] = it } }
        val episodes = HashMap<Pair<Int, Int>, JSONObject>()
        for (n in seasons) {
            val s = runCatching { JSONObject(get("tv/$id/season/$n?language=${o.optString("_lang")}")) }.getOrNull() ?: continue
            s.optJSONArray("episodes")?.objects()?.forEach { e -> episodes[n to e.optInt("episode_number")] = e }
        }
        val rating = o.optJSONObject("content_ratings")?.optJSONArray("results")?.objects()
            ?.let { list -> list.firstOrNull { it.optString("iso_3166_1") == region() } ?: list.firstOrNull { it.optString("iso_3166_1") == "US" } }
            ?.optString("rating").orEmpty()
        val show = Show(
            key = "tmdb:$id",
            title = o.optString("name").ifBlank { fallbackTitle },
            year = o.optString("first_air_date").take(4),
            tmdbId = id,
            overview = o.optString("overview"),
            poster = o.img("poster_path", "w500"),
            backdrop = o.img("backdrop_path", "w1280"),
            logo = logo(o),
            rating = o.optDouble("vote_average", 0.0),
            genres = o.optJSONArray("genres")?.objects()?.map { it.optString("name") }.orEmpty(),
            certification = rating,
            network = o.optJSONArray("networks")?.objects()?.firstOrNull()?.optString("name").orEmpty(),
            status = o.optString("status"),
            cast = cast(o, "aggregate_credits"),
            seasonPosters = seasonPosters,
        )
        return show to episodes
    }

    /** TMDB id for an IMDb id ("tt0133093"), or null. */
    fun findImdb(imdb: String): Int? = runCatching {
        JSONObject(get("find/$imdb?external_source=imdb_id")).getJSONArray("movie_results").objects().firstOrNull()?.getInt("id")
    }.getOrNull()

    /** IMDb id ("tt0133093") of a TMDB film or series, or null. */
    fun imdbIdOf(tmdbId: Int, tv: Boolean): String? = runCatching {
        JSONObject(get("${if (tv) "tv" else "movie"}/$tmdbId/external_ids")).optString("imdb_id").takeIf { it.startsWith("tt") }
    }.getOrNull()

    /** YouTube key of the best trailer: one in the viewer's language, else English. */
    fun trailer(id: Int, tv: Boolean): String? {
        val kind = if (tv) "tv" else "movie"
        val iso = lang().substringBefore('-')
        for (lang in listOf(iso, "en").distinct()) {
            val list = runCatching { JSONObject(get("$kind/$id/videos?language=$lang")).getJSONArray("results").objects() }.getOrNull() ?: continue
            list.filter { it.optString("site") == "YouTube" }
                .sortedWith(compareBy({ it.optString("type") != "Trailer" }, { !it.optBoolean("official") }))
                .firstOrNull()?.optString("key")?.let { return it }
        }
        return null
    }

    fun episode(file: VideoFile, season: Int, number: Int, e: JSONObject?) = Episode(
        file = file, season = season, number = number,
        title = e?.optString("name").orEmpty(),
        overview = e?.optString("overview").orEmpty(),
        still = e?.img("still_path", "w780"),
        airDate = e?.optString("air_date").orEmpty(),
        runtime = e?.optInt("runtime") ?: 0,
        rating = e?.optDouble("vote_average", 0.0) ?: 0.0,
    )

    /**
     * Details in the viewer's language, with English filling a missing plot. Posters follow the artwork
     * language when it differs. "_lang" and "_art" record the languages used.
     */
    private fun details(path: String, append: String): JSONObject {
        val original = if (language() == "original" || artLanguage() == "original")
            runCatching { JSONObject(get("$path?language=en-US")).optString("original_language") }.getOrNull() else null
        val lang = lang(original)
        val iso = lang.substringBefore('-')
        val art = artLanguage().let { if (it == "original") original?.ifBlank { null } ?: iso else it.substringBefore('-') }
        val o = JSONObject(get("$path?append_to_response=$append&language=$lang&include_image_language=${listOf(art, iso, "en").distinct().joinToString(",")},null"))
        o.put("_lang", lang); o.put("_art", art)
        if (art != iso) {
            o.optJSONObject("images")?.optJSONArray("posters")?.objects()
                ?.filter { it.optString("iso_639_1") == art }?.maxByOrNull { it.optDouble("vote_average") }
                ?.let { o.put("poster_path", it.optString("file_path")) }
        }
        if (o.optString("overview").isBlank() && iso != "en") {
            runCatching { JSONObject(get("$path?language=en-US")) }.getOrNull()?.let { en ->
                o.put("overview", en.optString("overview"))
                if (o.optString("tagline").isBlank()) o.put("tagline", en.optString("tagline"))
            }
        }
        return o
    }

    /** Title logo of a film or series on its own, for titles catalogued before logos were stored. */
    fun logoOf(tmdbId: Int, tv: Boolean): String? = runCatching {
        val iso = lang().substringBefore('-')
        val art = artLanguage().let { if (it == "original") iso else it.substringBefore('-') }
        val o = JSONObject().put("images", JSONObject(get("${if (tv) "tv" else "movie"}/$tmdbId/images?include_image_language=${listOf(art, iso, "en").distinct().joinToString(",")},null")))
        o.put("_lang", lang()); o.put("_art", art)
        logo(o)
    }.getOrNull()

    /**
     * Every poster (or background) TMDB has for a title, to choose from. Posters in the artwork language come first,
     * then English and ones without text; backgrounds without text come first. Best voted first within each.
     */
    fun images(tmdbId: Int, tv: Boolean, backdrops: Boolean): List<String> {
        val iso = lang().substringBefore('-')
        val art = artLanguage().let { if (it == "original") iso else it.substringBefore('-') }
        val o = JSONObject(get("${if (tv) "tv" else "movie"}/$tmdbId/images?include_image_language=${listOf(art, iso, "en").distinct().joinToString(",")},null"))
        val order = if (backdrops) listOf("", art, iso, "en") else listOf(art, iso, "en", "")   // "": no text
        fun rank(p: JSONObject): Int {
            val i = order.indexOf(if (p.isNull("iso_639_1")) "" else p.optString("iso_639_1"))
            return if (i < 0) order.size else i
        }
        return o.optJSONArray(if (backdrops) "backdrops" else "posters")?.objects().orEmpty()
            .sortedWith(compareBy<JSONObject>(::rank).thenByDescending { it.optDouble("vote_average") })
            .mapNotNull { it.img("file_path", if (backdrops) "w1280" else "w500") }
            .distinct().take(80)
    }

    private fun logo(o: JSONObject): String? {
        val logos = o.optJSONObject("images")?.optJSONArray("logos")?.objects() ?: return null
        val iso = o.optString("_art").ifBlank { lang().substringBefore('-') }
        val pick = logos.firstOrNull { it.optString("iso_639_1") == iso && it.optString("file_path").endsWith(".png") }
            ?: logos.firstOrNull { it.optString("iso_639_1") == o.optString("_lang").substringBefore('-') && it.optString("file_path").endsWith(".png") }
            ?: logos.firstOrNull { it.optString("iso_639_1") == "en" && it.optString("file_path").endsWith(".png") }
        return pick?.img("file_path", "w500")
    }

    private fun cast(o: JSONObject, field: String = "credits"): List<Person> =
        o.optJSONObject(field)?.optJSONArray("cast")?.objects()?.take(20)?.map { c ->
            val role = c.optString("character").ifBlank {
                c.optJSONArray("roles")?.objects()?.firstOrNull()?.optString("character").orEmpty()
            }
            Person(c.optString("name"), role, c.img("profile_path", "w185"))
        }.orEmpty()

    private fun movieCertification(o: JSONObject): String {
        val list = o.optJSONObject("release_dates")?.optJSONArray("results")?.objects() ?: return ""
        for (country in listOf(region(), "US")) {
            val dates = list.firstOrNull { it.optString("iso_3166_1") == country }?.optJSONArray("release_dates")?.objects()
            dates?.map { it.optString("certification") }?.firstOrNull { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun region() = language().let { if (it == "original") AppLanguage.system.country.ifEmpty { "US" } else it.substringAfter('-', "US") }

    private fun get(path: String): String {
        val bearer = key.startsWith("eyJ")
        val sep = if ('?' in path) "&" else "?"
        val url = URL("https://api.themoviedb.org/3/$path" + if (bearer) "" else "${sep}api_key=$key")
        repeat(3) { attempt ->
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/json")
            if (bearer) c.setRequestProperty("Authorization", "Bearer $key")
            try {
                when (c.responseCode) {
                    200 -> return c.inputStream.bufferedReader().use { it.readText() }
                    429 -> Thread.sleep(1000L * (attempt + 1))   // rate limited: back off and retry
                    else -> throw IOException("TMDB HTTP ${c.responseCode}")
                }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("TMDB rate limit")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun JSONObject.img(name: String, size: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.startsWith("/") }?.let { "$IMAGES/$size$it" }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    companion object {
        const val IMAGES = "https://image.tmdb.org/t/p"
    }
}

