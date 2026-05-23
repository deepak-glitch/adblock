package com.streamadblock.firetv.vpn

/**
 * IP/UDP packet helpers for writing DNS responses back into the VPN tun.
 * Only IPv4 is supported (Fire TV DNS over IPv6 is rare).
 */
object IpPacket {

    /**
     * Build a complete IPv4+UDP packet carrying [dnsPayload] as the
     * response to the original request packet.
     *
     * @param request   the original IP packet bytes (from the tun)
     * @param ihl       IP header length in bytes (e.g. 20)
     * @param origSrcPort  source port of the original request (becomes our dst port)
     * @param dnsPayload the DNS reply bytes
     */
    fun buildUdpResponse(
        request: ByteArray,
        ihl: Int,
        origSrcPort: Int,
        dnsPayload: ByteArray
    ): ByteArray {
        val ipHeaderLen = ihl
        val udpLen = 8 + dnsPayload.size
        val totalLen = ipHeaderLen + udpLen
        val out = ByteArray(totalLen)

        // ── IP header (copy + swap src/dst) ──────────────────────────────
        System.arraycopy(request, 0, out, 0, ipHeaderLen)

        // Total Length (bytes 2-3)
        out[2] = (totalLen ushr 8).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        // Identification — keep request id (offset 4-5)
        // Flags + Fragment offset — clear
        out[6] = 0; out[7] = 0
        // TTL → 64
        out[8] = 64
        // Protocol = UDP
        out[9] = 17
        // Checksum — recompute below
        out[10] = 0; out[11] = 0

        // Swap source/destination IP
        val src = ByteArray(4); System.arraycopy(request, 12, src, 0, 4)
        val dst = ByteArray(4); System.arraycopy(request, 16, dst, 0, 4)
        System.arraycopy(dst, 0, out, 12, 4)  // dst becomes our src
        System.arraycopy(src, 0, out, 16, 4)  // src becomes our dst

        // IP header checksum
        val ipChecksum = checksum(out, 0, ipHeaderLen)
        out[10] = (ipChecksum ushr 8).toByte()
        out[11] = (ipChecksum and 0xFF).toByte()

        // ── UDP header ───────────────────────────────────────────────────
        val udpStart = ipHeaderLen
        // src port = 53, dst port = origSrcPort
        out[udpStart    ] = 0
        out[udpStart + 1] = 53
        out[udpStart + 2] = (origSrcPort ushr 8).toByte()
        out[udpStart + 3] = (origSrcPort and 0xFF).toByte()
        out[udpStart + 4] = (udpLen ushr 8).toByte()
        out[udpStart + 5] = (udpLen and 0xFF).toByte()
        // Checksum placeholder
        out[udpStart + 6] = 0
        out[udpStart + 7] = 0

        // ── DNS payload ──────────────────────────────────────────────────
        System.arraycopy(dnsPayload, 0, out, udpStart + 8, dnsPayload.size)

        // UDP checksum (over pseudo-header + UDP header + payload)
        val udpChecksum = udpChecksum(out, ipHeaderLen, udpLen)
        // 0 means "not computed" which Android accepts, but compute properly
        out[udpStart + 6] = (udpChecksum ushr 8).toByte()
        out[udpStart + 7] = (udpChecksum and 0xFF).toByte()

        return out
    }

    /** Standard 16-bit one's complement checksum. */
    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum: Long = 0
        var i = offset
        val end = offset + length - 1
        while (i < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i == end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return ((sum.inv()) and 0xFFFF).toInt()
    }

    /** UDP checksum including the IPv4 pseudo-header. */
    private fun udpChecksum(packet: ByteArray, udpStart: Int, udpLen: Int): Int {
        var sum: Long = 0
        // Pseudo-header: src IP (4), dst IP (4), zero (1), protocol (1), UDP length (2)
        // After we've swapped, src IP is at offset 12, dst at offset 16
        sum += ((packet[12].toInt() and 0xFF) shl 8) or (packet[13].toInt() and 0xFF)
        sum += ((packet[14].toInt() and 0xFF) shl 8) or (packet[15].toInt() and 0xFF)
        sum += ((packet[16].toInt() and 0xFF) shl 8) or (packet[17].toInt() and 0xFF)
        sum += ((packet[18].toInt() and 0xFF) shl 8) or (packet[19].toInt() and 0xFF)
        sum += 17  // protocol
        sum += udpLen

        // UDP header + payload
        var i = udpStart
        val end = udpStart + udpLen - 1
        while (i < end) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i == end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        var result = ((sum.inv()) and 0xFFFF).toInt()
        // RFC 768: if computed checksum is 0, transmit as all-ones
        if (result == 0) result = 0xFFFF
        return result
    }
}
