package com.glint.player

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Glint's own interface language, picked in Settings → Language (empty follows the TV). Android 11 has no
 * per-app language, so each activity and the app itself are given resources in the chosen language here.
 */
object AppLanguage {
    /** Languages the interface is translated into, Settings' order after "Same as TV". */
    val CODES = listOf("tr", "en", "de", "es", "it", "fr", "pt", "nl", "pl", "ru")

    /** The TV's own language, as it was before Glint set its pick as the default: "Same as TV" means this. */
    val system: Locale = Locale.getDefault()

    /**
     * Set just before the screen is rebuilt for a new language or appearance: the Settings page (section index) it
     * opens on again, or -1 for Home.
     */
    @Volatile var reopenOnSection = -1

    fun code(context: Context): String =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("appLanguage", "")!!

    private fun locale(context: Context) = code(context).takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it) } ?: system

    /** An activity's [base] with its resources in the chosen language; also the default for dates and numbers. */
    fun wrap(base: Context): Context {
        val locale = locale(base)
        Locale.setDefault(locale)
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
    }

    /**
     * Puts the app's own resources (strings read outside a screen, through App.instance) into the chosen
     * language: at start, and again when the viewer picks another. Changed in place, so it takes at once.
     */
    @Suppress("DEPRECATION")
    fun apply(app: Context) {
        val locale = locale(app)
        Locale.setDefault(locale)
        val res = app.resources
        res.updateConfiguration(Configuration(res.configuration).apply { setLocale(locale) }, res.displayMetrics)
    }
}
