package com.streamadblock.firetv.vpn

import java.nio.ByteBuffer

/**
 * Helpers for parsing raw IP/TCP packet bytes and building TCP response packets.
 * Used by TcpProxy for SNI-based HTTPS blocking.
 */
object TcpPacket {

    data class Header(
        val srcPort: Int,
        val dstPort: Int,
        val seqNum: Long,
        val ackNum: Long,
        val dataOffset: Int,   // in 32-bit words
        val flags: Int,
        val windowSize: Int
    ) {
        val isSyn: Boolean get() = (flags and 0x02) != 0
        val isAck: Boolean get() = (flags and 0x10) != 0
        val isFin: Boolean get() = (flags and 0x01) != 0
        val isRst: Boolean get() = (flags and 0x04) != 0
        val tcpHeaderBytes: Int get() = dataOffset * 4
    }

    fun parseHeader(pkt: ByteArray, tcpOff: Int): Header? {
        if (tcpOff + 20 > pkt.size) return null
        return Header(
            srcPort    = getU16(pkt, tcpOff),
            dstPort    = getU16(pkt, tcpOff + 2),
            seqNum     = getU32(pkt, tcpOff + 4),
            ackNum     = getU32(pkt, tcpOff + 8),
            dataOffset = (pkt[tcpOff + 12].toInt() ushr 4) and 0x0F,
            flags      = pkt[tcpOff + 13].toInt() and 0xFF,
            windowSize = getU16(pkt, tcpOff + 14)
        )
    }

    fun srcIp(pkt: ByteArray): Int = ByteBuffer.wrap(pkt, 12, 4).int
    fun dstIp(pkt: ByteArray): Int = ByteBuffer.wrap(pkt, 16, 4).int

    /**
     * Build a complete IPv4 + TCP packet with optional payload.
     * flags: 0x02=SYN, 0x10=ACK, 0x12=SYN+ACK, 0x14=RST+ACK, 0x11=FIN+ACK, 0x18=PSH+ACK
     */
    fun buildPacket(
        srcIp: Int, srcPort: Int,
        dstIp: Int, dstPort: Int,
        seqNum: Long, ackNum: Long,
        flags: Int,
        windowSize: Int = 65535,
        data: ByteArray = ByteArray(0)
    ): ByteArray {
        val tcpLen   = 20 + data.size
        val totalLen = 20 + tcpLen
        val buf = ByteArray(totalLen)

        // ── IPv4 header ──────────────────────────────────────────────────
        buf[0]  = 0x45.toByte()          // version=4, IHL=5
        buf[1]  = 0                      // DSCP/ECN
        put16(buf, 2, totalLen)
        put16(buf, 4, 0)                 // Identification
        buf[6]  = 0x40.toByte()          // Don't fragment
        buf[7]  = 0                      // Fragment offset
        buf[8]  = 64                     // TTL
        buf[9]  = 6                      // Protocol = TCP
        put16(buf, 10, 0)               // Checksum placeholder
        ByteBuffer.wrap(buf, 12, 4).putInt(srcIp)
        ByteBuffer.wrap(buf, 16, 4).putInt(dstIp)
        put16(buf, 10, ipChecksum(buf, 0, 20))

        // ── TCP header ───────────────────────────────────────────────────
        val t = 20   // TCP starts at byte 20
        put16(buf, t,     srcPort)
        put16(buf, t + 2, dstPort)
        put32(buf, t + 4, seqNum)
        put32(buf, t + 8, ackNum)
        buf[t + 12] = (5 shl 4).toByte()   // data offset = 5 words = 20 bytes
        buf[t + 13] = flags.toByte()
        put16(buf, t + 14, windowSize)
        put16(buf, t + 16, 0)               // checksum placeholder
        put16(buf, t + 18, 0)               // urgent pointer

        if (data.isNotEmpty()) System.arraycopy(data, 0, buf, 40, data.size)

        put16(buf, t + 16, tcpChecksum(srcIp, dstIp, buf, t, tcpLen))
        return buf
    }

    // ── Checksum helpers ─────────────────────────────────────────────────

    private fun ipChecksum(buf: ByteArray, off: Int, len: Int): Int =
        ones_complement_sum(buf, off, len)

    private fun tcpChecksum(srcIp: Int, dstIp: Int, buf: ByteArray, tcpOff: Int, tcpLen: Int): Int {
        // Pseudo-header: src(4) + dst(4) + zero(1) + proto=6(1) + tcp_length(2)
        val pseudo = ByteArray(12 + tcpLen)
        ByteBuffer.wrap(pseudo, 0, 4).putInt(srcIp)
        ByteBuffer.wrap(pseudo, 4, 4).putInt(dstIp)
        pseudo[8] = 0; pseudo[9] = 6
        put16(pseudo, 10, tcpLen)
        System.arraycopy(buf, tcpOff, pseudo, 12, tcpLen)
        return ones_complement_sum(pseudo, 0, pseudo.size)
    }

    private fun ones_complement_sum(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if ((len and 1) != 0) sum += (buf[off + len - 1].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    // ── Raw byte helpers ─────────────────────────────────────────────────

    private fun getU16(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun getU32(b: ByteArray, i: Int) =
        ((b[i].toLong() and 0xFF) shl 24) or
        ((b[i + 1].toLong() and 0xFF) shl 16) or
        ((b[i + 2].toLong() and 0xFF) shl 8) or
        (b[i + 3].toLong() and 0xFF)

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte()
    }

    private fun put32(b: ByteArray, i: Int, v: Long) {
        b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte()
        b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
    }
}
