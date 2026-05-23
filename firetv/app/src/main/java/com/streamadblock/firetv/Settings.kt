package com.streamadblock.firetv

import android.content.Context
import androidx.core.content.edit

/**
 * Persisted user preferences.
 */
object Settings {
    private const val PREFS = "settings_prefs"
    private const val K_MODE = "mode"
    private const val K_UPSTREAM = "upstream_dns"
    private const val K_AUTOSTART = "auto_start"

    const val MODE_VPN = "vpn"
    const val MODE_DNS_CLIENT = "dns_client"

    /** Which blocking mode is active. */
    fun getMode(context: Context): String =
        prefs(context).getString(K_MODE, MODE_VPN) ?: MODE_VPN

    fun setMode(context: Context, mode: String) {
        prefs(context).edit { putString(K_MODE, mode) }
    }

    /** Upstream DNS server (used in VPN mode for non-blocked queries,
     *  shown in DNS Client mode for user to manually configure). */
    fun getUpstreamDns(context: Context): String =
        prefs(context).getString(K_UPSTREAM, "8.8.8.8") ?: "8.8.8.8"

    fun setUpstreamDns(context: Context, host: String) {
        prefs(context).edit { putString(K_UPSTREAM, host) }
    }

    fun isAutoStart(context: Context): Boolean =
        prefs(context).getBoolean(K_AUTOSTART, false)

    fun setAutoStart(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(K_AUTOSTART, value) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
