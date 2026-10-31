package com.glint.player

import android.content.Context
import java.util.Locale

/** Viewer preferences, in SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** TMDB language; empty follows the device, "original" each title's own language. */
    var metadataLanguage: String
        get() = prefs.getString("metadataLanguage", "")!!
        set(v) = prefs.edit().putString("metadataLanguage", v).apply()
    val language get() = metadataLanguage.ifEmpty { AppLanguage.system.toLanguageTag() }

    /** Language of posters and logos; empty follows the metadata language, "original" each title's own. */
    var artworkLanguage: String
        get() = prefs.getString("artworkLanguage", "")!!
        set(v) = prefs.edit().putString("artworkLanguage", v).apply()
    val artLanguage get() = artworkLanguage.ifEmpty { language }

    /** The viewer's own TMDB key (used when the build has none) and OMDb key (ratings in the store build). */
    var tmdbKey: String
        get() = prefs.getString("tmdbKey", "")!!
        set(v) = prefs.edit().putString("tmdbKey", v.trim()).apply()
    var omdbKey: String
        get() = prefs.getString("omdbKey", "")!!
        set(v) = prefs.edit().putString("omdbKey", v.trim()).apply()

    /** OpenSubtitles.com: the viewer's API key and account, for searching subtitles from the player. */
    var osApiKey: String
        get() = prefs.getString("osApiKey", "")!!
        set(v) = prefs.edit().putString("osApiKey", v.trim()).remove("osToken").apply()
    var osUser: String
        get() = prefs.getString("osUser", "")!!
        set(v) = prefs.edit().putString("osUser", v.trim()).remove("osToken").apply()
    var osPassword: String
        get() = prefs.getString("osPassword", "")!!
        set(v) = prefs.edit().putString("osPassword", v).remove("osToken").apply()
    /** The login's token and the server it named, kept for a day (OpenSubtitles' tokens last 24 hours). */
    var osToken: String
        get() = prefs.getString("osToken", "")!!.takeIf { System.currentTimeMillis() - prefs.getLong("osTokenAt", 0) < 20 * 3600_000L }.orEmpty()
        set(v) = prefs.edit().putString("osToken", v).putLong("osTokenAt", System.currentTimeMillis()).apply()
    var osBaseUrl: String
        get() = prefs.getString("osBaseUrl", "")!!
        set(v) = prefs.edit().putString("osBaseUrl", v).apply()

    /** Send Dolby/DTS bitstreams to the TV or receiver instead of decoding them. */
    var passthrough: Boolean
        get() = prefs.getBoolean("passthrough", false)
        set(v) = prefs.edit().putBoolean("passthrough", v).apply()

    /** Switch the display refresh rate to the video's frame rate (24p films without judder). */
    var matchFrameRate: Boolean
        get() = prefs.getBoolean("matchFrameRate", true)
        set(v) = prefs.edit().putBoolean("matchFrameRate", v).apply()

    var autoplayNext: Boolean
        get() = prefs.getBoolean("autoplayNext", true)
        set(v) = prefs.edit().putBoolean("autoplayNext", v).apply()

    /** Subtitle size in percent of normal. */
    /** Subtitle size in %. The menu's old Large (130) and Extra large (160) became 115 and 130: a saved one keeps its name. */
    var subtitleScale: Int
        get() = prefs.getInt("subtitleScale", 100).let { v ->
            if (prefs.getBoolean("subtitleScaleV2", false)) v
            else (when (v) { 130 -> 115; 160 -> 130; else -> v }).also { prefs.edit().putInt("subtitleScale", it).putBoolean("subtitleScaleV2", true).apply() }
        }
        set(v) = prefs.edit().putInt("subtitleScale", v).apply()

    var subtitleColor: Int
        get() = prefs.getInt("subtitleColor", 0xFFFFFF)
        set(v) = prefs.edit().putInt("subtitleColor", v).apply()

    var subtitleBackground: Boolean
        get() = prefs.getBoolean("subtitleBackground", false)
        set(v) = prefs.edit().putBoolean("subtitleBackground", v).apply()

    /** ISO 639 code preferred for subtitles ("" = off unless forced, "auto" = device language). */
    var subtitleLanguage: String
        get() = prefs.getString("subtitleLanguage", "auto")!!
        set(v) = prefs.edit().putString("subtitleLanguage", v).apply()

    /** ISO 639 code preferred for audio ("" = the file's default). */
    var audioLanguage: String
        get() = prefs.getString("audioLanguage", "")!!
        set(v) = prefs.edit().putString("audioLanguage", v).apply()

    /** Network buffer in milliseconds; 4K remuxes over Wi-Fi like more. */
    var bufferMs: Int
        get() = prefs.getInt("bufferMs", 3000)
        set(v) = prefs.edit().putInt("bufferMs", v).apply()

    var skipSeconds: Int
        get() = prefs.getInt("skipSeconds", 10)
        set(v) = prefs.edit().putInt("skipSeconds", v).apply()


    /** What Glint's icon opens: a built-in tile's place in FAVORITE_TILE_NAMES, or -1 for Home. */
    var startScreen: Int
        get() = prefs.getInt("startScreen", -1)
        set(v) = prefs.edit().putInt("startScreen", v).apply()

    /** Last measured NAS → box speed in Mbps (0 = never measured). */
    var measuredMbps: Float
        get() = prefs.getFloat("measuredMbps", 0f)
        set(v) = prefs.edit().putFloat("measuredMbps", v).apply()

    /** Memory used to read ahead of playback, in MB (0 = libVLC's default small cache). */
    var readAheadMb: Int
        get() = prefs.getInt("readAheadMb", 192)
        set(v) = prefs.edit().putInt("readAheadMb", v).apply()

    private fun flag(name: String, default: Boolean) = prefs.getBoolean(name, default)
    private fun setFlag(name: String, v: Boolean) = prefs.edit().putBoolean(name, v).apply()

    /** Orange corner on unwatched posters, progress bar on started ones. */
    var watchedIndicators: Boolean get() = flag("watchedIndicators", true); set(v) = setFlag("watchedIndicators", v)
    var showPosterTitles: Boolean get() = flag("showPosterTitles", true); set(v) = setFlag("showPosterTitles", v)
    var showFilenames: Boolean get() = flag("showFilenames", false); set(v) = setFlag("showFilenames", v)
    var picksOfTheDay: Boolean get() = flag("picksOfTheDay", true); set(v) = setFlag("picksOfTheDay", v)
    var hideSpoilers: Boolean get() = flag("hideSpoilers", false); set(v) = setFlag("hideSpoilers", v)
    var groupMovieCollections: Boolean get() = flag("groupMovieCollections", true); set(v) = setFlag("groupMovieCollections", v)
    var collectionDetails: Boolean get() = flag("collectionDetails", true); set(v) = setFlag("collectionDetails", v)
    var showLogos: Boolean get() = flag("showLogos", true); set(v) = setFlag("showLogos", v)
    var showRatings: Boolean get() = flag("showRatings", true); set(v) = setFlag("showRatings", v)

    /** Default order for library grids: 0 title, 1 date added, 2 release date, 3 rating, 4 duration. */
    var sortOrder: Int get() = prefs.getInt("sortOrder", 0); set(v) = prefs.edit().putInt("sortOrder", v).apply()

    private fun int(name: String, default: Int) = prefs.getInt(name, default)
    private fun setInt(name: String, v: Int) = prefs.edit().putInt(name, v).apply()

    var forwardSkip: Int get() = int("forwardSkip", 10); set(v) = setInt("forwardSkip", v)
    var backwardSkip: Int get() = int("backwardSkip", 10); set(v) = setInt("backwardSkip", v)
    /** Single left/right presses: 0 skip by time, 1 jump between chapters. */
    var edgeMode: Int get() = int("edgeMode", 0); set(v) = setInt("edgeMode", v)
    /** 0 ask, 1 always resume, 2 always start over. */
    var resumeMode: Int get() = int("resumeMode", 1); set(v) = setInt("resumeMode", v)
    /** Default playback speed in percent. */
    var defaultSpeed: Int get() = int("defaultSpeed", 100); set(v) = setInt("defaultSpeed", v)
    /** 0 Auto, 1 Limited, 2 Memory only, 3 Legacy (no read-ahead). */
    var cacheMode: Int get() = int("cacheMode", 0); set(v) = setInt("cacheMode", v)
    /** 0 auto, 1 always, 2 never. */
    var deinterlace: Int get() = int("deinterlace", 0); set(v) = setInt("deinterlace", v)
    /** Volume boost in percent, 100–300. */
    var volumeBoost: Int get() = int("volumeBoost", 100); set(v) = setInt("volumeBoost", v)
    var normalizeDownmix: Boolean get() = flag("normalizeDownmix", false); set(v) = setFlag("normalizeDownmix", v)
    /** Distance of subtitles from the bottom edge, in pixels. */
    var subtitlePosition: Int get() = int("subtitlePosition", 0); set(v) = setInt("subtitlePosition", v)
    var subtitleOutline: Int get() = int("subtitleOutline", 4); set(v) = setInt("subtitleOutline", v)
    var subtitleBold: Boolean get() = flag("subtitleBold", false); set(v) = setFlag("subtitleBold", v)
    /** 0 off, 1 show a button, 2 skip after 3 s, 3 skip at once. */
    var preferLocalMetadata: Boolean get() = flag("preferLocalMetadata", true); set(v) = setFlag("preferLocalMetadata", v)
    /** 0 off, 1 repeat this video, 2 repeat the list. */
    var repeatMode: Int get() = int("repeatMode", 0); set(v) = setInt("repeatMode", v)
    var profileId: String
        get() = prefs.getString("profileId", "main")!!
        set(v) = prefs.edit().putString("profileId", v).apply()
    var listView: Boolean get() = flag("listView", false); set(v) = setFlag("listView", v)
    var lightMode: Boolean get() = theme == 2; set(v) { theme = if (v) 2 else 1 }
    /** Page background: 0 black (default), 1 dark grey, 2 light. Older installs that chose light keep it. */
    /** Interface language (a code from AppLanguage.CODES); empty follows the TV. Read at start by AppLanguage. */
    var appLanguage: String get() = prefs.getString("appLanguage", "")!!; set(v) { prefs.edit().putString("appLanguage", v).commit() }

    /** Theme colour: index into ACCENTS (0 orange, the default). */
    var accent: Int get() = prefs.getInt("accent", 0); set(v) = prefs.edit().putInt("accent", v).apply()

    var theme: Int
        get() = prefs.getInt("theme", if (flag("lightMode", false)) 2 else 0)
        set(v) = prefs.edit().putInt("theme", v).apply()
    var autoSelectNext: Boolean get() = flag("autoSelectNext", true); set(v) = setFlag("autoSelectNext", v)
    var skipIntros: Int get() = int("skipIntros", 1); set(v) = setInt("skipIntros", v)
    /** Turn on a forced subtitle track (signs, foreign-language lines) when no other subtitles are shown. */
    /** Play Dolby Vision films in Dolby Vision (Media3) when the TV can; off plays them as HDR10 with libVLC. */
    var dolbyVision: Boolean get() = flag("dolbyVision", true); set(v) = setFlag("dolbyVision", v)
    /** Kodi's "Dolby Vision compatibility mode": profile 7 films are converted to 8.1 while they play. */
    var doviCompat: Boolean get() = flag("doviCompat", false); set(v) = setFlag("doviCompat", v)
    var forcedSubtitles: Boolean get() = flag("forcedSubtitles", true); set(v) = setFlag("forcedSubtitles", v)
}
