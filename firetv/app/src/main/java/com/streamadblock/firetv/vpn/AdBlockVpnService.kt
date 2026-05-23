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
 * How it works:
 *   1. Establish a VPN with a small private IP range (10.111.0.x).
 *   2. We become Android's "system" DNS resolver — all DNS goes through us.
 *   3. Read DNS query packets from the VPN tun, parse the question name.
 *   4. If domain is in blocklist → craft NXDOMAIN response, write it back.
 *   5. Otherwise → forward the raw packet to upstream DNS (8.8.8.8) and
 *      relay the answer back into the tun.
 *
 * All non-DNS traffic from apps is excluded from the VPN routes so it
 * uses the normal network — we only intercept DNS.
 */
class AdBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "AdBlockVpn"
        const val ACTION_START = "com.streamadblock.firetv.START_VPN"
        const val ACTION_STOP  = "com.streamadblock.firetv.STOP_VPN"

        private const val NOTIF_ID = 4242
        private const val CHANNEL_ID = "adblock_running"

        private const val VPN_ADDRESS = "10.111.0.1"
        private const val VPN_PREFIX = 24
        // The fake DNS server inside the tun — Android apps query this IP
        private const val VPN_DNS = "10.111.0.2"

        @Volatile var isRunning: Boolean = false
            private set
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startVpn()
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
            // Route only DNS packets through the VPN (UDP port 53 traffic).
            // We achieve this by routing the fake DNS IP only.
            .addRoute(VPN_DNS, 32)
            .setMtu(1500)

        // Don't intercept our own app
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) { /* ignored */ }

        tunInterface = builder.establish()
        if (tunInterface == null) {
            Log.e(TAG, "Failed to establish VPN")
            stopSelf()
            return
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

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    /**
     * Main loop — read packets from the tun, decide block vs forward.
     */
    private suspend fun tunLoop() {
        val tun = tunInterface ?: return
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val buf = ByteArray(32768)

        // One upstream socket reused for all forwards
        val upstreamHost = Settings.getUpstreamDns(this)
        val upstreamAddr = InetAddress.getByName(upstreamHost)
        val forwardSocket = DatagramSocket().apply { protect(this) }

        while (isRunning) {
            try {
                val len = withContext(Dispatchers.IO) { input.read(buf) }
                if (len <= 0) {
                    delay(10)
                    continue
                }

                val packet = buf.copyOf(len)
                handlePacket(packet, len, output, forwardSocket, upstreamAddr)
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "tunLoop error: ${e.message}")
                delay(50)
            }
        }
        try { forwardSocket.close() } catch (_: Exception) {}
    }

    /**
     * Parse the IP packet to find UDP DNS payload.
     * If blocked → respond inline. Otherwise → forward upstream and relay reply.
     */
    private suspend fun handlePacket(
        packet: ByteArray,
        len: Int,
        output: FileOutputStream,
        forwardSocket: DatagramSocket,
        upstreamAddr: InetAddress
    ) {
        // Only handle IPv4 + UDP + dst port 53
        if (packet.size < 28) return
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || ihl > len) return
        val proto = packet[9].toInt() and 0xFF
        if (proto != 17) return  // UDP

        // UDP header starts at ihl
        val udpStart = ihl
        if (udpStart + 8 > len) return
        val srcPort = ((packet[udpStart].toInt() and 0xFF) shl 8) or (packet[udpStart + 1].toInt() and 0xFF)
        val dstPort = ((packet[udpStart + 2].toInt() and 0xFF) shl 8) or (packet[udpStart + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val dnsStart = udpStart + 8
        val dnsLen = len - dnsStart
        if (dnsLen < 12) return

        val dnsPayload = packet.copyOfRange(dnsStart, dnsStart + dnsLen)
        val name = DnsPacket.extractQName(dnsPayload) ?: return

        if (BlocklistManager.isBlocked(name)) {
            Stats.recordBlocked()
            val reply = DnsPacket.buildNxDomainResponse(dnsPayload) ?: return
            val ipReply = IpPacket.buildUdpResponse(packet, ihl, srcPort, reply)
            try { output.write(ipReply) } catch (e: Exception) { Log.w(TAG, "write blocked reply: ${e.message}") }
            return
        }

        // Forward to real upstream
        Stats.recordAllowed()
        try {
            val send = DatagramPacket(dnsPayload, dnsPayload.size, upstreamAddr, 53)
            forwardSocket.send(send)
            val recvBuf = ByteArray(4096)
            val recv = DatagramPacket(recvBuf, recvBuf.size)
            forwardSocket.soTimeout = 3000
            forwardSocket.receive(recv)
            val replyDns = recvBuf.copyOf(recv.length)
            val ipReply = IpPacket.buildUdpResponse(packet, ihl, srcPort, replyDns)
            output.write(ipReply)
        } catch (e: Exception) {
            // On upstream failure, send SERVFAIL
            val servfail = DnsPacket.buildServFailResponse(dnsPayload) ?: return
            val ipReply = IpPacket.buildUdpResponse(packet, ihl, srcPort, servfail)
            try { output.write(ipReply) } catch (_: Exception) {}
        }
    }

    // ── Notification ─────────────────────────────────────────────────────

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Stream AdBlock",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while the ad blocker is running"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AdBlockVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(getString(R.string.notif_running_text,
                BlocklistManager.blockedCount()))
            .setContentIntent(tapIntent)
            .addAction(0, getString(R.string.stop), stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
