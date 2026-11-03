package com.glint.player

import java.util.Locale

/** ISO 639-2 bibliographic codes ("ger") to the two-letter ones Locale knows. */
internal val BIBLIOGRAPHIC = mapOf(
    "ger" to "de", "fre" to "fr", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "rum" to "ro", "per" to "fa",
    "slo" to "sk", "alb" to "sq", "arm" to "hy", "ice" to "is", "mac" to "mk", "may" to "ms", "wel" to "cy", "geo" to "ka",
    "baq" to "eu", "bur" to "my",
)

/**
 * The language code a sidecar subtitle's name gives ("Film.2013.tr.srt" → "tr", "…eng.srt" → "eng", "…Turkish.srt"
 * → "tr"), or null when it names none. Only what follows the video's own name counts when it starts with it, so a
 * title word ("Ada", a language code too) isn't taken for one.
 */
internal fun sidecarLanguageCode(name: String, videoBase: String? = null): String? {
    val words = mapOf("turkish" to "tr", "turkce" to "tr", "türkçe" to "tr", "english" to "en", "german" to "de", "french" to "fr", "spanish" to "es", "italian" to "it")
    var stem = name.substringBeforeLast('.').lowercase(Locale.ROOT)
    val base = videoBase?.lowercase(Locale.ROOT)
    if (!base.isNullOrEmpty() && stem.startsWith(base)) stem = stem.removePrefix(base)
    val tokens = stem.split('.', '_', '-', ' ', '[', ']', '(', ')').filter { it.isNotBlank() }
    for (t in tokens.asReversed()) {
        val code = words[t] ?: t.takeIf { it.length in 2..3 && it.all(Char::isLetter) } ?: continue
        if (isLanguageCode(code)) return code
    }
    return null
}

/** Whether Locale knows this two- or three-letter code as a language (its name isn't just the code back). */
internal fun isLanguageCode(code: String): Boolean {
    val c = code.lowercase(Locale.ROOT)
    val two = BIBLIOGRAPHIC[c] ?: if (c.length == 3) Locale.getAvailableLocales().firstOrNull { runCatching { it.isO3Language }.getOrNull() == c }?.language ?: c else c
    val name = Locale(two).getDisplayLanguage(Locale.ENGLISH)
    return name.isNotBlank() && !name.equals(c, true) && !name.equals(two, true)
}
