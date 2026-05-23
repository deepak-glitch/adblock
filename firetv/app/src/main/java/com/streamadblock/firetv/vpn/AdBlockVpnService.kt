package com.streamadblock.firetv.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamadblock.firetv.BlocklistManager
import com.streamadblock.firetv.MainActivity
import com.streamadblock.firetv.R
import com.streamadblock.firetv.Stats
import com.streamadblock.firetv.Settings
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Local-VPN ad blocker.
 *
 * Only DNS traffic (UDP port 53) is routed through the tun — all other
 * traffic flows normally over the real network so streaming is never
 * interrupted.
 *
 * For each DNS query:
 *   - Blocked domain → NXDOMAIN reply (inline, no upstream contact)
 *   - Allowed domain → forwarded to upstream (8.8.8.8) and relayed back
 */
class AdBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "AdBlockVpn"
        const val ACTION_START = "com.streamadblock.firetv.START_VPN"
        const val ACTION_STOP  = "com.streamadblock.firetv.STOP_VPN"

        private const val NOTIF_ID   = 4242
        private const val CHANNEL_ID = "adblock_running"

        private const val VPN_ADDRESS = "10.111.0.1"
        private const val VPN_PREFIX  = 24
        private const val VPN_DNS     = "10.111.0.2"   // fake DNS inside the tun

        @Volatile var isRunning: Boolean = false
            private set
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Service lifecycle ─────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); stopSelf(); return START_NOT_STICKY }
            else        -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        BlocklistManager.loadAll(this)
        Stats.load(this)

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(VPN_ADDRESS, VPN_PREFIX)
            .addDnsServer(VPN_DNS)
            // Route ONLY the fake DNS server IP through the tun.
            // All other traffic (TCP, UDP video streams, etc.) flows
            // through the real network — nothing is intercepted or broken.
            .addRoute(VPN_DNS, 32)
            .setMtu(1500)

        // Exclude ourselves — prevents recursive routing of our upstream sockets
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        tunInterface = builder.establish()
        if (tunInterface == null) {
            Log.e(TAG, "Failed to establish VPN"); stopSelf(); return
        }

        isRunning = true
        startForeground(NOTIF_ID, buildNotification())
        Log.i(TAG, "VPN started — blocklist=${BlocklistManager.blockedCount()} domains")

        scope.launch { tunLoop() }
    }

    private fun stopVpn() {
        if (!isRunning) return
        isRunning = false
        try { tunInterface?.close() } catch (_: Exception) {}
        tunInterface = null
        scope.coroutineContext.cancelChildren()
        Stats.save(this)
        Log.i(TAG, "VPN stopped")
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { stopVpn(); super.onDestroy() }

    // ── Main packet loop ──────────────────────────────────────────────────

    private suspend fun tunLoop() {
        val tun    = tunInterface ?: return
        val input  = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val buf    = ByteArray(32768)

        val upstreamHost = Settings.getUpstreamDns(this)
        val upstreamAddr = InetAddress.getByName(upstreamHost)
        val dnsSocket    = DatagramSocket().apply { protect(this) }

        while (isRunning) {
            try {
                val len = withContext(Dispatchers.IO) { input.read(buf) }
                if (len <= 0) { delay(10); continue }

                val pkt = buf.copyOf(len)
                handlePacket(pkt, len, output, dnsSocket, upstreamAddr)
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "tunLoop: ${e.message}")
                delay(50)
            }
        }

        try { dnsSocket.close() } catch (_: Exception) {}
    }

    // ── Per-packet dispatch ───────────────────────────────────────────────

    private suspend fun handlePacket(
        pkt: ByteArray, len: Int,
        output: FileOutputStream,
        dnsSocket: DatagramSocket,
        upstreamAddr: InetAddress
    ) {
        // Only IPv4 + UDP + dst port 53
        if (pkt.size < 28) return
        val version = (pkt[0].toInt() ushr 4) and 0x0F
        if (version != 4) return

        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (ihl < 20 || ihl > len) return
        if (pkt[9].toInt() and 0xFF != 17) return  // UDP only

        val udpStart = ihl
        if (udpStart + 8 > len) return
        val srcPort = ((pkt[udpStart].toInt() and 0xFF) shl 8) or (pkt[udpStart + 1].toInt() and 0xFF)
        val dstPort = ((pkt[udpStart + 2].toInt() and 0xFF) shl 8) or (pkt[udpStart + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val dnsStart = udpStart + 8
        val dnsLen   = len - dnsStart
        if (dnsLen < 12) return

        val dns  = pkt.copyOfRange(dnsStart, dnsStart + dnsLen)
        val name = DnsPacket.extractQName(dns) ?: return

        if (BlocklistManager.isBlocked(name)) {
            Stats.recordBlocked()
            val reply   = DnsPacket.buildNxDomainResponse(dns) ?: return
            val ipReply = IpPacket.buildUdpResponse(pkt, ihl, srcPort, reply)
            try { output.write(ipReply) } catch (e: Exception) { Log.w(TAG, "write: ${e.message}") }
            return
        }

        Stats.recordAllowed()
        try {
            dnsSocket.send(DatagramPacket(dns, dns.size, upstreamAddr, 53))
            val rb = ByteArray(4096)
            val rp = DatagramPacket(rb, rb.size)
            dnsSocket.soTimeout = 3000
            dnsSocket.receive(rp)
            output.write(IpPacket.buildUdpResponse(pkt, ihl, srcPort, rb.copyOf(rp.length)))
        } catch (e: Exception) {
            val sf = DnsPacket.buildServFailResponse(dns) ?: return
            try { output.write(IpPacket.buildUdpResponse(pkt, ihl, srcPort, sf)) } catch (_: Exception) {}
        }
    }

    // ── Notification ──────────────────────────────────────────────────────

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Stream AdBlock", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while the ad blocker is running"; setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val tap  = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, AdBlockVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(getString(R.string.notif_running_text, BlocklistManager.blockedCount()))
            .setContentIntent(tap)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
