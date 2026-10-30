package com.glint.player

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * IMDb and Rotten Tomatoes scores from the OMDb API (omdbapi.com), with the viewer's own key from Settings →
 * Metadata & Artwork. TMDB supplies each title's IMDb id. Answers are kept for a week.
 */
object Omdb {
    data class Rating(val score: Double, val votes: Int)
    data class Ratings(val imdb: Rating?, val rottenTomatoes: Int?)

    private const val MAX_AGE = 7 * 24 * 3600_000L

    /** IMDb rating and Rotten Tomatoes' Tomatometer of a TMDB film or series. Call off the main thread. */
    fun forTitle(ctx: Context, tmdbId: Int, tv: Boolean): Ratings? = imdbId(ctx, tmdbId, tv)?.let { of(ctx, it) }

    /** The IMDb id of a TMDB film or series, asked of TMDB once and remembered. Call off the main thread. */
    private fun imdbId(ctx: Context, tmdbId: Int, tv: Boolean): String? {
        if (tmdbId == 0) return null
        val prefs = ctx.getSharedPreferences("imdb-ids", Context.MODE_PRIVATE)
        val key = (if (tv) "t" else "m") + tmdbId
        return prefs.getString(key, null) ?: App.library.tmdb.imdbIdOf(tmdbId, tv)?.also { prefs.edit().putString(key, it).apply() }
    }

    /** Call off the main thread. Null without a key, or when OMDb can't be reached. */
    private fun of(ctx: Context, imdbId: String): Ratings? {
        val key = App.library.settings.omdbKey.ifBlank { return null }
        val prefs = ctx.getSharedPreferences("omdb", Context.MODE_PRIVATE)
        prefs.getString(imdbId, null)?.split('|')?.takeIf { it.size == 4 }?.let { (score, votes, rt, t) ->
            if (System.currentTimeMillis() - (t.toLongOrNull() ?: 0) < MAX_AGE)
                return Ratings(score.toDoubleOrNull()?.let { Rating(it, votes.toIntOrNull() ?: 0) }, rt.toIntOrNull())
        }
        val o = fetch(key, imdbId) ?: return null
        if (o.optString("Response") != "True") return null
        val score = o.optString("imdbRating").toDoubleOrNull()
        val votes = o.optString("imdbVotes").replace(",", "").toIntOrNull() ?: 0
        val rt = o.optJSONArray("Ratings")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            ?.firstOrNull { it.optString("Source") == "Rotten Tomatoes" }?.optString("Value")?.removeSuffix("%")?.toIntOrNull()
        prefs.edit().putString(imdbId, "${score ?: ""}|$votes|${rt ?: ""}|${System.currentTimeMillis()}").apply()
        return Ratings(score?.let { Rating(it, votes) }, rt)
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
