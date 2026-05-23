package com.streamadblock.firetv.vpn

import java.nio.ByteBuffer

/**
 * Minimal DNS packet utilities — query name parsing + NXDOMAIN/SERVFAIL responses.
 * No external library needed.
 *
 * Wire format (RFC 1035 §4.1):
 *   Header (12 bytes): ID, FLAGS, QDCOUNT, ANCOUNT, NSCOUNT, ARCOUNT
 *   Question: QNAME (length-prefixed labels, terminated by 0), QTYPE (2), QCLASS (2)
 */
object DnsPacket {

    /**
     * Extract the queried domain name from a DNS query packet.
     * Returns null if malformed.
     */
    fun extractQName(payload: ByteArray): String? {
        if (payload.size < 13) return null
        // Skip 12-byte header → start of question
        var i = 12
        val labels = StringBuilder()
        var safetyCounter = 0
        while (i < payload.size && safetyCounter < 64) {
            val len = payload[i].toInt() and 0xFF
            if (len == 0) break
            // Compression pointers (top 2 bits set) shouldn't occur in queries, but guard
            if ((len and 0xC0) == 0xC0) return null
            i++
            if (i + len > payload.size || len > 63) return null
            if (labels.isNotEmpty()) labels.append('.')
            labels.append(String(payload, i, len, Charsets.US_ASCII).lowercase())
            i += len
            safetyCounter++
        }
        return if (labels.isEmpty()) null else labels.toString()
    }

    /**
     * Build a DNS response with RCODE = 3 (NXDOMAIN).
     * Copies header from the request, sets QR=1 + RCODE=3, retains question.
     */
    fun buildNxDomainResponse(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        val response = query.copyOf()
        // Flags: QR=1, OPCODE=copied, AA=0, TC=0, RD=copied, RA=1, RCODE=3
        // Byte 2: QR(1) + OPCODE(4) + AA(1) + TC(1) + RD(1)
        // Byte 3: RA(1) + Z(3) + RCODE(4)
        response[2] = (response[2].toInt() or 0x80).toByte() // set QR
        response[3] = ((response[3].toInt() and 0xF8.toByte().toInt()) or 0x03 or 0x80).toByte()
        // Set RA bit (0x80 in byte 3)
        // Zero out answer/auth/additional counts (only question remains)
        response[6] = 0; response[7] = 0
        response[8] = 0; response[9] = 0
        response[10] = 0; response[11] = 0
        return response
    }

    /**
     * Build a DNS response with RCODE = 2 (SERVFAIL).
     */
    fun buildServFailResponse(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        val response = query.copyOf()
        response[2] = (response[2].toInt() or 0x80).toByte()
        response[3] = ((response[3].toInt() and 0xF8.toByte().toInt()) or 0x02 or 0x80).toByte()
        response[6] = 0; response[7] = 0
        response[8] = 0; response[9] = 0
        response[10] = 0; response[11] = 0
        return response
    }
}
