package com.glint.player

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * OpenSubtitles.com (REST API v1), with the viewer's own API key from Settings → Subtitles. The key alone searches
 * and allows a few downloads a day; an account there (optional) raises that to the account's allowance.
 */
object OpenSubtitles {
    private const val API = "https://api.opensubtitles.com/api/v1"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()
    private val s get() = App.library.settings

    /**
     * One subtitle to pick: its file, language code, release name (the video version it was timed to), frame rate
     * (0 when unknown), downloads and whether it's for the hard of hearing.
     */
    data class Hit(val fileId: Int, val language: String, val release: String, val fps: Double, val downloads: Int, val hearingImpaired: Boolean)

    val configured get() = s.osApiKey.isNotBlank()

    private fun request(url: String, key: String = s.osApiKey) = Request.Builder().url(url)
        .header("Api-Key", key)
        .header("User-Agent", "Glint v${BuildConfig.VERSION_NAME}")   // the API asks every app to name itself
        .header("Accept", "application/json")

    private fun call(r: Request): JSONObject = http.newCall(r).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        if (resp.code == 403 && "consume" in body) throw IOException(App.instance.getString(R.string.os_bad_key))
        if (!resp.isSuccessful) throw IOException(o.optString("message").ifBlank { o.optJSONArray("errors")?.optString(0) }.orEmpty().ifBlank { "OpenSubtitles ${resp.code}" })
        o
    }

    /**
     * Whether [key] looks like an OpenSubtitles API key (letters and digits, about 32 of them), not a pasted address.
     * The service can't check a key on its own: searching takes any key, and a sign-in without an account answers
     * a right key and a wrong one alike. Signing in with the account (Settings) is the real check.
     */
    fun works(key: String): Boolean = key.matches(Regex("[A-Za-z0-9]{20,64}"))

    /** Logs in with the saved account; returns the downloads it allows a day. Keeps the token for later downloads. */
    fun login(): Int {
        val body = JSONObject().put("username", s.osUser).put("password", s.osPassword).toString().toRequestBody(json)
        val o = call(request("$API/login").post(body).build())
        s.osToken = o.getString("token")
        s.osBaseUrl = o.optString("base_url").takeIf { it.isNotBlank() }?.let { "https://$it/api/v1" }.orEmpty()
        return o.optJSONObject("user")?.optInt("allowed_downloads") ?: 0
    }

    private fun token(): String = s.osToken.ifBlank { login(); s.osToken }

    /** The languages to ask for: the subtitle language from Settings (or the TV's), then English. */
    fun languages(): List<String> {
        val pref = s.subtitleLanguage.takeIf { it.isNotBlank() && it != "auto" } ?: AppLanguage.system.language
        return listOf(osCode(pref), "en").distinct()
    }

    private fun osCode(code: String) = when (code.lowercase(Locale.ROOT)) { "pt" -> "pt-pt,pt-br"; "zh" -> "zh-cn,zh-tw"; else -> code.lowercase(Locale.ROOT) }

    /**
     * Subtitles by the film's name and year, or for an episode by the series' name with [season] and [episode]
     * (season 0: a film). Nothing is picked: the viewer chooses by release name and frame rate. The preferred
     * language first, then the most downloaded.
     */
    fun search(title: String, year: String, season: Int, episode: Int, tmdbId: Int = 0): List<Hit> {
        val byName = find(sortedMapOf("query" to title.lowercase(Locale.ROOT)).apply {
            if (season > 0) { put("season_number", "$season"); put("episode_number", "$episode"); put("type", "episode") }
            else { put("type", "movie"); if (year.isNotBlank()) put("year", year) }
        })
        // A name OpenSubtitles doesn't know (a translated title): the TMDB id finds the same film.
        if (byName.isNotEmpty() || tmdbId == 0) return byName
        return find(sortedMapOf<String, String>().apply {
            if (season > 0) { put("parent_tmdb_id", "$tmdbId"); put("season_number", "$season"); put("episode_number", "$episode") }
            else put("tmdb_id", "$tmdbId")
        })
    }

    private fun find(params: java.util.SortedMap<String, String>): List<Hit> {
        val langs = languages()
        // Parameters in alphabetical order and lowercase: the API redirects any other order.
        params["languages"] = langs.joinToString(",").split(',').sorted().joinToString(",")
        val query = params.entries.joinToString("&") { (k, v) -> "$k=" + java.net.URLEncoder.encode(v, "UTF-8").replace("%2C", ",").replace("+", "%20") }
        val data = call(request("$API/subtitles?$query").build()).optJSONArray("data") ?: return emptyList()
        val first = langs.first().split(',')
        return (0 until data.length()).mapNotNull { i ->
            val a = data.getJSONObject(i).optJSONObject("attributes") ?: return@mapNotNull null
            val file = a.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
            Hit(file.optInt("file_id"), a.optString("language"), a.optString("release").ifBlank { file.optString("file_name") },
                a.optDouble("fps", 0.0).takeIf { !it.isNaN() } ?: 0.0, a.optInt("download_count"), a.optBoolean("hearing_impaired"))
        }.sortedWith(compareBy<Hit> { if (it.language in first) 0 else 1 }.thenByDescending { it.downloads })
    }

    /** Downloads a subtitle file's bytes (one of the account's daily downloads). */
    fun download(fileId: Int): ByteArray {
        val body = JSONObject().put("file_id", fileId).toString().toRequestBody(json)
        fun ask(t: String?) = call(request(s.osBaseUrl.ifBlank { API } + "/download").apply { if (t != null) header("Authorization", "Bearer $t") }.post(body).build())
        // No account: OpenSubtitles allows a few downloads a day with the key alone. With one, a token the server
        // dropped early means logging in again once.
        val o = if (s.osUser.isBlank() || s.osPassword.isEmpty()) ask(null)
            else runCatching { ask(token()) }.getOrElse { s.osToken = ""; ask(token()) }
        val link = o.optString("link").ifBlank { throw IOException(o.optString("message").ifBlank { "OpenSubtitles: no link" }) }
        return http.newCall(Request.Builder().url(link).header("User-Agent", "Glint v${BuildConfig.VERSION_NAME}").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("OpenSubtitles ${r.code}")
            r.body!!.bytes()
        }
    }
}
