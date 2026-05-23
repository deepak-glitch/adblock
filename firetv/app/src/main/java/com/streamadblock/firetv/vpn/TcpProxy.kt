package com.streamadblock.firetv.vpn

import android.net.VpnService
import android.util.Log
import com.streamadblock.firetv.BlocklistManager
import com.streamadblock.firetv.Stats
import kotlinx.coroutines.*
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transparent TCP proxy that runs inside the VPN tun.
 *
 * For each TCP connection we see in the tun we:
 *   1. Accept the SYN by connecting a protect()ed socket to the real destination.
 *   2. For HTTPS (port 443): read the TLS ClientHello, extract the SNI,
 *      and RST the connection if the hostname is in the blocklist.
 *   3. Relay data bidirectionally for all non-blocked connections.
 *
 * This catches ad servers that bypass DNS (hardcoded IPs, DNS-over-HTTPS,
 * pre-cached DNS entries) and gives us a second layer of defence on top of
 * the DNS blocker.
 */
class TcpProxy(
    private val vpnService: VpnService,
    private val output: FileOutputStream,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TcpProxy"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS    = 30_000
    }

    // Monotonically increasing initial sequence number for our side
    private val isnCounter = AtomicInteger(0x1000)

    // Active sessions keyed by (srcIp, srcPort) — enough to be unique per device
    private val sessions = ConcurrentHashMap<Long, Session>()

    // ── Public API ────────────────────────────────────────────────────────

    fun handlePacket(pkt: ByteArray, ihl: Int) {
        val hdr = TcpPacket.parseHeader(pkt, ihl) ?: return
        val srcIp  = TcpPacket.srcIp(pkt)
        val dstIp  = TcpPacket.dstIp(pkt)
        val key    = sessionKey(srcIp, hdr.srcPort)

        when {
            hdr.isSyn && !hdr.isAck -> {
                // New outbound connection — connect upstream + handshake
                scope.launch {
                    openSession(srcIp, hdr.srcPort, dstIp, hdr.dstPort, hdr.seqNum)
                }
            }
            hdr.isRst || hdr.isFin -> {
                sessions.remove(key)?.close()
            }
            else -> {
                val sess = sessions[key] ?: return
                val dataOff = ihl + hdr.tcpHeaderBytes
                val dataLen = pkt.size - dataOff
                if (dataLen > 0) {
                    val payload = pkt.copyOfRange(dataOff, dataOff + dataLen)
                    scope.launch { sess.onClientData(payload, dataLen) }
                }
                // Pure ACK → nothing to do
            }
        }
    }

    fun cleanup() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    // ── Session lifecycle ─────────────────────────────────────────────────

    private suspend fun openSession(
        srcIp: Int, srcPort: Int,
        dstIp: Int, dstPort: Int,
        clientIsn: Long
    ) {
        try {
            val sock = Socket().also { vpnService.protect(it) }
            val dstAddr = InetAddress.getByAddress(
                ByteBuffer.allocate(4).putInt(dstIp).array()
            )
            withContext(Dispatchers.IO) {
                sock.connect(InetSocketAddress(dstAddr, dstPort), CONNECT_TIMEOUT_MS)
                sock.soTimeout = READ_TIMEOUT_MS
            }

            val sess = Session(
                srcIp = srcIp, srcPort = srcPort,
                dstIp = dstIp, dstPort = dstPort,
                socket = sock,
                clientNextSeq = (clientIsn + 1) and 0xFFFFFFFFL,
                ourIsn = isnCounter.addAndGet(1).toLong()
            )
            sessions[sessionKey(srcIp, srcPort)] = sess
            sess.sendSynAck()
            scope.launch { sess.relayRemoteToTun() }

        } catch (e: Exception) {
            Log.w(TAG, "openSession $dstPort: ${e.message}")
            sendRstFor(dstIp, dstPort, srcIp, srcPort, ackNum = (clientIsn + 1) and 0xFFFFFFFFL)
        }
    }

    private fun sendRstFor(
        srcIp: Int, srcPort: Int,
        dstIp: Int, dstPort: Int,
        seqNum: Long = 0L, ackNum: Long = 0L
    ) {
        val pkt = TcpPacket.buildPacket(
            srcIp = srcIp, srcPort = srcPort,
            dstIp = dstIp, dstPort = dstPort,
            seqNum = seqNum, ackNum = ackNum,
            flags = 0x14  // RST + ACK
        )
        writeTun(pkt)
    }

    private fun writeTun(pkt: ByteArray) {
        try { synchronized(output) { output.write(pkt) } }
        catch (e: Exception) { /* tun closed */ }
    }

    // ── SNI extraction from TLS ClientHello ──────────────────────────────

    /**
     * Parse the SNI hostname from a TLS 1.x ClientHello.
     * Returns null if the data isn't a ClientHello or has no SNI extension.
     *
     * Wire format (RFC 5246 §7.4.1.2 + RFC 6066 §3):
     *   Record:     [0x16][version 2B][length 2B]
     *   Handshake:  [0x01][length 3B][legacy_version 2B][random 32B]
     *               [session_id_len 1B][session_id NB]
     *               [cipher_suites_len 2B][cipher_suites NB]
     *               [compression_len 1B][compression NB]
     *               [extensions_len 2B] extension*
     *   Extension:  [type 2B][length 2B][data NB]
     *   SNI (0x00): [list_len 2B][name_type 1B][name_len 2B][hostname NB]
     */
    fun extractSni(data: ByteArray): String? {
        if (data.size < 5) return null
        if (data[0] != 0x16.toByte()) return null   // TLS Handshake record
        if (data[5] != 0x01.toByte()) return null   // ClientHello

        var p = 43  // Skip: record header(5) + handshake header(4) + version(2) + random(32)

        // Session ID
        if (p >= data.size) return null
        p += 1 + (data[p].toInt() and 0xFF)

        // Cipher suites
        if (p + 2 > data.size) return null
        p += 2 + (((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF))

        // Compression methods
        if (p >= data.size) return null
        p += 1 + (data[p].toInt() and 0xFF)

        // Extensions
        if (p + 2 > data.size) return null
        p += 2  // skip extensions total length

        while (p + 4 <= data.size) {
            val type = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
            val len  = ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
            p += 4
            if (type == 0x0000) {   // SNI extension
                // [server_name_list_len 2][name_type 1][name_len 2][name NB]
                if (p + 5 > data.size) return null
                val nameLen = ((data[p + 3].toInt() and 0xFF) shl 8) or (data[p + 4].toInt() and 0xFF)
                if (p + 5 + nameLen > data.size) return null
                return String(data, p + 5, nameLen, Charsets.US_ASCII)
            }
            p += len
        }
        return null
    }

    // ── Session key ───────────────────────────────────────────────────────

    private fun sessionKey(srcIp: Int, srcPort: Int): Long =
        (srcIp.toLong() shl 20) or srcPort.toLong()

    // ── Inner Session ─────────────────────────────────────────────────────

    inner class Session(
        val srcIp: Int, val srcPort: Int,
        val dstIp: Int, val dstPort: Int,
        val socket: Socket,
        var clientNextSeq: Long,
        val ourIsn: Long
    ) {
        @Volatile var ourSeq: Long = ourIsn
        @Volatile var sniDone = false
        @Volatile var alive   = true

        // ── Handshake ─────────────────────────────────────────────────────

        fun sendSynAck() {
            val pkt = TcpPacket.buildPacket(
                srcIp = dstIp, srcPort = dstPort,
                dstIp = srcIp, dstPort = srcPort,
                seqNum = ourSeq, ackNum = clientNextSeq,
                flags = 0x12  // SYN + ACK
            )
            ourSeq = (ourSeq + 1) and 0xFFFFFFFFL
            writeTun(pkt)
        }

        // ── Client → remote ───────────────────────────────────────────────

        suspend fun onClientData(payload: ByteArray, dataLen: Int) {
            if (!alive) return

            // SNI check on first HTTPS packet
            if (!sniDone && dstPort == 443) {
                sniDone = true
                val sni = extractSni(payload)
                if (sni != null && BlocklistManager.isBlocked(sni)) {
                    Log.d(TAG, "SNI blocked: $sni")
                    Stats.recordBlocked()
                    block()
                    return
                }
            }

            // Forward to remote server
            try {
                withContext(Dispatchers.IO) {
                    socket.outputStream.write(payload)
                    socket.outputStream.flush()
                }
                clientNextSeq = (clientNextSeq + dataLen) and 0xFFFFFFFFL
                sendAck()
            } catch (e: Exception) {
                close()
            }
        }

        // ── Remote → client (runs in its own coroutine) ───────────────────

        suspend fun relayRemoteToTun() {
            try {
                val buf = ByteArray(8192)
                while (alive) {
                    val n = withContext(Dispatchers.IO) {
                        try { socket.inputStream.read(buf) } catch (e: Exception) { -1 }
                    }
                    if (n <= 0) break
                    sendData(buf.copyOf(n))
                }
            } finally {
                if (alive) sendFin()
                close()
            }
        }

        // ── Packet builders ───────────────────────────────────────────────

        private fun sendAck() = writeTun(TcpPacket.buildPacket(
            srcIp = dstIp, srcPort = dstPort,
            dstIp = srcIp, dstPort = srcPort,
            seqNum = ourSeq, ackNum = clientNextSeq,
            flags = 0x10  // ACK
        ))

        private fun sendData(data: ByteArray) {
            writeTun(TcpPacket.buildPacket(
                srcIp = dstIp, srcPort = dstPort,
                dstIp = srcIp, dstPort = srcPort,
                seqNum = ourSeq, ackNum = clientNextSeq,
                flags = 0x18,  // PSH + ACK
                data = data
            ))
            ourSeq = (ourSeq + data.size) and 0xFFFFFFFFL
        }

        private fun sendFin() = writeTun(TcpPacket.buildPacket(
            srcIp = dstIp, srcPort = dstPort,
            dstIp = srcIp, dstPort = srcPort,
            seqNum = ourSeq, ackNum = clientNextSeq,
            flags = 0x11  // FIN + ACK
        ))

        private fun block() {
            writeTun(TcpPacket.buildPacket(
                srcIp = dstIp, srcPort = dstPort,
                dstIp = srcIp, dstPort = srcPort,
                seqNum = ourSeq, ackNum = clientNextSeq,
                flags = 0x14  // RST + ACK
            ))
            close()
        }

        fun close() {
            alive = false
            sessions.remove(sessionKey(srcIp, srcPort))
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
