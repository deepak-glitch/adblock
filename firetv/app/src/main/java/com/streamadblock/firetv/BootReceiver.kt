package com.streamadblock.firetv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.streamadblock.firetv.vpn.AdBlockVpnService

/**
 * Starts the VPN automatically on device boot — but only if the user has
 * enabled auto-start AND already granted VPN permission before.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.isAutoStart(context)) return
        if (Settings.getMode(context) != Settings.MODE_VPN) return

        // VpnService.prepare returns null when permission was previously granted
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            Log.i("BootReceiver", "VPN permission not granted yet — skipping auto-start")
            return
        }

        val start = Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(start)
        } else {
            context.startService(start)
        }
    }
}
