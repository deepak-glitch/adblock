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
    private const val K_AUTO_UPDATE = "auto_update_lists"
    private const val K_LAST_LIST_UPDATE = "last_list_update"
    private const val K_DEEP_BLOCKING = "deep_blocking"

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

    /** Whether downloaded filter lists refresh automatically (daily). */
    fun isAutoUpdate(context: Context): Boolean =
        prefs(context).getBoolean(K_AUTO_UPDATE, true)

    fun setAutoUpdate(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(K_AUTO_UPDATE, value) }
    }

    /** Epoch-millis of the last successful filter-list download (0 = never). */
    fun getLastListUpdate(context: Context): Long =
        prefs(context).getLong(K_LAST_LIST_UPDATE, 0L)

    fun setLastListUpdate(context: Context, value: Long) {
        prefs(context).edit { putLong(K_LAST_LIST_UPDATE, value) }
    }

    /**
     * Deep Blocking (experimental): route all traffic through the tun and run
     * the SNI/HTTPS proxy as a second blocking layer. Off by default — it can
     * affect streaming performance and does NOT remove in-stream (SSAI) ads.
     */
    fun isDeepBlocking(context: Context): Boolean =
        prefs(context).getBoolean(K_DEEP_BLOCKING, false)

    fun setDeepBlocking(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(K_DEEP_BLOCKING, value) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
