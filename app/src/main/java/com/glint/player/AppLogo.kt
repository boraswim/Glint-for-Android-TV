package com.glint.player

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The logo the TV's app row shows: the orange Glint logo on black or on white. Each is its own launcher
 * entry in the manifest (.LogoBlack, .LogoWhite) with its icon and banner; exactly one is turned on.
 */
object AppLogo {
    private fun entry(context: Context, white: Boolean) = ComponentName(context, "com.glint.player." + if (white) "LogoWhite" else "LogoBlack")

    fun isWhite(context: Context) =
        context.packageManager.getComponentEnabledSetting(entry(context, true)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Turns on the chosen entry before turning off the other, so the app is never missing from the launcher. */
    fun set(context: Context, white: Boolean) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(entry(context, white), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(entry(context, !white), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}
