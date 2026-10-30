package com.glint.player

import android.content.Context
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * IMDb ratings from IMDb's own daily dataset (title.ratings.tsv.gz, free for personal use). It's fetched
 * at most once a week and kept as a sorted table of 8-byte records (id, rating, votes), so a lookup is a
 * few file reads, works offline and holds nothing in memory. TMDB supplies each title's IMDb id.
 */
object ImdbRatings {
    private const val SOURCE = "https://datasets.imdbws.com/title.ratings.tsv.gz"
    private const val MAX_AGE = 7 * 24 * 3600_000L

    data class Rating(val score: Double, val votes: Int)

    private fun table(ctx: Context) = File(ctx.filesDir, "imdb-ratings.bin")

    /** Downloads and rebuilds the table when it's missing or a week old. Safe to call from any thread. */
    @Synchronized
    fun refresh(ctx: Context) {
        val out = table(ctx)
        if (out.exists() && System.currentTimeMillis() - out.lastModified() < MAX_AGE) return
        runCatching {
            val c = URL(SOURCE).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            var packed = LongArray(2_000_000)
            var n = 0
            try {
                GZIPInputStream(c.inputStream).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        // tconst \t averageRating \t numVotes, after a header line.
                        if (!line.startsWith("tt")) continue
                        val a = line.indexOf('\t'); val b = line.indexOf('\t', a + 1)
                        if (a < 0 || b < 0) continue
                        val id = line.substring(2, a).toLongOrNull() ?: continue
                        val score = ((line.substring(a + 1, b).toDoubleOrNull() ?: continue) * 10).toLong().coerceIn(0, 100)
                        val votes = (line.substring(b + 1).trim().toLongOrNull() ?: 0).coerceAtMost(0xFFFFFF)
                        if (n == packed.size) packed = packed.copyOf(n * 3 / 2)
                        packed[n++] = (id shl 32) or (score shl 24) or votes
                    }
                }
            } finally {
                c.disconnect()
            }
            if (n < 100_000) error("IMDb ratings look incomplete ($n)")
            packed = packed.copyOf(n).also { it.sort() }
            val tmp = File(out.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { d -> for (v in packed) d.writeLong(v) }
            tmp.renameTo(out)
        }.onFailure { android.util.Log.w("Imdb", "Couldn't update IMDb ratings", it) }
    }

    /** The rating of "tt0133093", by binary search over the table; null if unknown or not downloaded yet. */
    fun lookup(ctx: Context, imdbId: String): Rating? {
        val id = imdbId.removePrefix("tt").toLongOrNull() ?: return null
        val f = table(ctx).takeIf { it.exists() } ?: return null
        RandomAccessFile(f, "r").use { r ->
            var lo = 0L
            var hi = r.length() / 8 - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                r.seek(mid * 8)
                val v = r.readLong()
                val vid = v ushr 32
                when {
                    vid < id -> lo = mid + 1
                    vid > id -> hi = mid - 1
                    else -> return Rating(((v ushr 24) and 0xFF) / 10.0, (v and 0xFFFFFF).toInt())
                }
            }
        }
        return null
    }

    /** IMDb rating of a TMDB film or series; looks the IMDb id up once and remembers it. Call off the main thread. */
    fun forTitle(ctx: Context, tmdbId: Int, tv: Boolean): Rating? {
        val imdb = imdbId(ctx, tmdbId, tv) ?: return null
        if (BuildConfig.STORE) return Omdb.of(ctx, imdb)?.imdb
        refresh(ctx)
        return lookup(ctx, imdb)
    }

    /** The IMDb id of a TMDB film or series, asked of TMDB once and remembered. Call off the main thread. */
    fun imdbId(ctx: Context, tmdbId: Int, tv: Boolean): String? {
        if (tmdbId == 0) return null
        val prefs = ctx.getSharedPreferences("imdb-ids", Context.MODE_PRIVATE)
        val key = (if (tv) "t" else "m") + tmdbId
        return prefs.getString(key, null) ?: App.library.tmdb.imdbIdOf(tmdbId, tv)?.also { prefs.edit().putString(key, it).apply() }
    }
}

/**
 * Rotten Tomatoes' Tomatometer (critics) and Popcornmeter (audience). Wikidata maps
 * the IMDb id to the title's Rotten Tomatoes page ("m/furiosa_a_mad_max_saga"); the scores are read from
 * that page's scorecard data. Both are kept for a week.
 */
object RottenTomatoes {
    data class Scores(val critics: Int?, val audience: Int?)

    private const val MAX_AGE = 7 * 24 * 3600_000L
    private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"

    /** Call off the main thread. Null when the title has no Rotten Tomatoes page or it can't be reached. */
    fun forTitle(ctx: Context, tmdbId: Int, tv: Boolean): Scores? {
        val prefs = ctx.getSharedPreferences("rotten-tomatoes", Context.MODE_PRIVATE)
        val imdb = ImdbRatings.imdbId(ctx, tmdbId, tv) ?: return null
        if (BuildConfig.STORE) return Omdb.of(ctx, imdb)?.rottenTomatoes?.let { Scores(it, null) }
        val page = prefs.getString("id:$imdb", null) ?: pageOf(imdb)?.also { prefs.edit().putString("id:$imdb", it).apply() } ?: return null
        if (page.isEmpty()) return null                                  // looked up before: none
        prefs.getString("s:$page", null)?.split('|')?.takeIf { it.size == 3 }?.let { (c, a, t) ->
            if (System.currentTimeMillis() - (t.toLongOrNull() ?: 0) < MAX_AGE) return Scores(c.toIntOrNull(), a.toIntOrNull())
        }
        val scores = scoresOf(page) ?: return null
        prefs.edit().putString("s:$page", "${scores.critics ?: ""}|${scores.audience ?: ""}|${System.currentTimeMillis()}").apply()
        return scores
    }

    /** "m/300" for an IMDb id, "" when Wikidata has no Rotten Tomatoes link, null when it couldn't be asked. */
    private fun pageOf(imdb: String): String? = runCatching {
        val q = "SELECT ?rt WHERE { ?f wdt:P345 \"$imdb\" . ?f wdt:P1258 ?rt . } LIMIT 1"
        val c = URL("https://query.wikidata.org/sparql?query=" + java.net.URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/sparql-results+json")
        c.setRequestProperty("User-Agent", "Glint/1.0 (personal media player)")
        try {
            val json = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            json.getJSONObject("results").getJSONArray("bindings").optJSONObject(0)?.getJSONObject("rt")?.getString("value").orEmpty()
        } finally { c.disconnect() }
    }.getOrNull()

    private fun scoresOf(page: String): Scores? = runCatching {
        val c = URL("https://www.rottentomatoes.com/$page").openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        val html = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        fun score(name: String) = Regex("\"$name\":\\{[^}]*?\"score\":\"(\\d+)\"").find(html)?.groupValues?.get(1)?.toIntOrNull()
        Scores(score("criticsScore"), score("audienceScore")).takeIf { it.critics != null || it.audience != null }
    }.getOrNull()
}

/**
 * IMDb and Rotten Tomatoes scores from the OMDb API (omdbapi.com), with the viewer's own key: what the store build
 * uses instead of IMDb's datasets and Rotten Tomatoes' pages. Answers are kept for a week.
 */
object Omdb {
    data class Ratings(val imdb: ImdbRatings.Rating?, val rottenTomatoes: Int?)

    private const val MAX_AGE = 7 * 24 * 3600_000L

    /** Call off the main thread. Null without a key, or when OMDb can't be reached. */
    fun of(ctx: Context, imdbId: String): Ratings? {
        val key = App.library.settings.omdbKey.ifBlank { return null }
        val prefs = ctx.getSharedPreferences("omdb", Context.MODE_PRIVATE)
        prefs.getString(imdbId, null)?.split('|')?.takeIf { it.size == 4 }?.let { (score, votes, rt, t) ->
            if (System.currentTimeMillis() - (t.toLongOrNull() ?: 0) < MAX_AGE)
                return Ratings(score.toDoubleOrNull()?.let { ImdbRatings.Rating(it, votes.toIntOrNull() ?: 0) }, rt.toIntOrNull())
        }
        val o = fetch(key, imdbId) ?: return null
        if (o.optString("Response") != "True") return null
        val score = o.optString("imdbRating").toDoubleOrNull()
        val votes = o.optString("imdbVotes").replace(",", "").toIntOrNull() ?: 0
        val rt = o.optJSONArray("Ratings")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            ?.firstOrNull { it.optString("Source") == "Rotten Tomatoes" }?.optString("Value")?.removeSuffix("%")?.toIntOrNull()
        prefs.edit().putString(imdbId, "${score ?: ""}|$votes|${rt ?: ""}|${System.currentTimeMillis()}").apply()
        return Ratings(score?.let { ImdbRatings.Rating(it, votes) }, rt)
    }

    /** Whether OMDb accepts the key. Call off the main thread. */
    fun works(key: String): Boolean = key.isNotBlank() && fetch(key, "tt0133093")?.optString("Response") == "True"

    /** Forgets kept answers, for a new key. */
    fun clear(ctx: Context) = ctx.getSharedPreferences("omdb", Context.MODE_PRIVATE).edit().clear().apply()

    private fun fetch(key: String, imdbId: String): org.json.JSONObject? = runCatching {
        val c = URL("https://www.omdbapi.com/?i=$imdbId&apikey=" + java.net.URLEncoder.encode(key, "UTF-8")).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        try {
            // OMDb answers a bad key with 401 and a JSON body that says so.
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream ?: return@runCatching null
            org.json.JSONObject(stream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }.getOrNull()
}
