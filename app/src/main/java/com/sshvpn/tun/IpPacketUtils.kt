package com.sshvpn.tun

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Stateless helpers for reading/writing raw IPv4 and TCP/UDP packet fields.
 * All offsets are in bytes from the start of the supplied ByteArray / ByteBuffer.
 */
object IpPacketUtils {

    // ── IPv4 header field offsets ──────────────────────────────────────────────
    const val IP_VER_IHL  = 0
    const val IP_TOTAL_LEN = 2
    const val IP_ID = 4
    const val IP_FLAGS_FRAG = 6
    const val IP_TTL = 8
    const val IP_PROTO    = 9
    const val IP_CHECKSUM = 10
    const val IP_SRC      = 12
    const val IP_DST      = 16
    const val IP_HEADER_MIN = 20

    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    // ── TCP header field offsets (relative to TCP segment start) ──────────────
    const val TCP_SRC_PORT = 0
    const val TCP_DST_PORT = 2
    const val TCP_SEQ      = 4
    const val TCP_ACK      = 8
    const val TCP_DATA_OFF = 12   // high nibble × 4 = header length
    const val TCP_FLAGS    = 13
    const val TCP_WINDOW   = 14
    const val TCP_CHECKSUM = 16

    const val FLAG_FIN = 0x01
    const val FLAG_SYN = 0x02
    const val FLAG_RST = 0x04
    const val FLAG_PSH = 0x08
    const val FLAG_ACK = 0x10

    // ── UDP header field offsets ───────────────────────────────────────────────
    const val UDP_SRC_PORT = 0
    const val UDP_DST_PORT = 2
    const val UDP_LEN      = 4
    const val UDP_CHECKSUM = 6

    // ── Readers ────────────────────────────────────────────────────────────────

    fun getU8(buf: ByteArray, off: Int): Int = buf[off].toInt() and 0xFF
    fun getU16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
    fun getI32(buf: ByteArray, off: Int): Int =
        (getU8(buf, off) shl 24) or (getU8(buf, off + 1) shl 16) or
        (getU8(buf, off + 2) shl 8) or getU8(buf, off + 3)

    fun putU8(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
    }

    fun putU16(buf: ByteArray, off: Int, v: Int) {
        buf[off]     = ((v shr 8) and 0xFF).toByte()
        buf[off + 1] = (v and 0xFF).toByte()
    }

    fun putI32(buf: ByteArray, off: Int, v: Int) {
        buf[off]     = ((v shr 24) and 0xFF).toByte()
        buf[off + 1] = ((v shr 16) and 0xFF).toByte()
        buf[off + 2] = ((v shr  8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    // ── Internet checksum (RFC 1071) ───────────────────────────────────────────

    fun checksum(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        val end = off + len
        while (i < end - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    /** TCP checksum using pseudo-header (RFC 793). */
    fun tcpChecksum(
        buf: ByteArray, tcpOff: Int, tcpLen: Int,
        srcIp: Int, dstIp: Int
    ): Int {
        val pseudo = ByteArray(12 + tcpLen)
        putI32(pseudo, 0, srcIp)
        putI32(pseudo, 4, dstIp)
        pseudo[8] = 0
        pseudo[9] = PROTO_TCP.toByte()
        putU16(pseudo, 10, tcpLen)
        System.arraycopy(buf, tcpOff, pseudo, 12, tcpLen)
        return checksum(pseudo, 0, pseudo.size)
    }

    /** UDP checksum using pseudo-header (RFC 768). */
    fun udpChecksum(
        buf: ByteArray, udpOff: Int, udpLen: Int,
        srcIp: Int, dstIp: Int
    ): Int {
        val pseudo = ByteArray(12 + udpLen)
        putI32(pseudo, 0, srcIp)
        putI32(pseudo, 4, dstIp)
        pseudo[8] = 0
        pseudo[9] = PROTO_UDP.toByte()
        putU16(pseudo, 10, udpLen)
        System.arraycopy(buf, udpOff, pseudo, 12, udpLen)
        return checksum(pseudo, 0, pseudo.size)
    }

    // ── Packet builders ────────────────────────────────────────────────────────

    /**
     * Build a complete IPv4 + TCP packet into a new ByteArray.
     */
    fun buildTcpPacket(
        srcIp: Int, dstIp: Int,
        srcPort: Int, dstPort: Int,
        seq: Int, ack: Int,
        flags: Int, window: Int = 65535,
        data: ByteArray = ByteArray(0)
    ): ByteArray {
        val ipLen  = 20
        val tcpLen = 20
        val total  = ipLen + tcpLen + data.size
        val buf    = ByteArray(total)

        // ── IPv4 header ─────────────────────────────────────────────────────
        buf[0] = 0x45.toByte()            // version=4, IHL=5
        buf[1] = 0                        // DSCP
        putU16(buf, 2, total)
        putU16(buf, 4, 0x4321)            // identification (arbitrary)
        putU16(buf, 6, 0x4000)            // DF flag, fragment offset=0
        buf[8] = 64                       // TTL
        buf[9] = PROTO_TCP.toByte()
        putU16(buf, IP_CHECKSUM, 0)       // placeholder
        putI32(buf, IP_SRC, srcIp)
        putI32(buf, IP_DST, dstIp)
        val ipCs = checksum(buf, 0, ipLen)
        putU16(buf, IP_CHECKSUM, ipCs)

        // ── TCP header ───────────────────────────────────────────────────────
        putU16(buf, ipLen + TCP_SRC_PORT, srcPort)
        putU16(buf, ipLen + TCP_DST_PORT, dstPort)
        putI32(buf, ipLen + TCP_SEQ, seq)
        putI32(buf, ipLen + TCP_ACK, ack)
        buf[ipLen + TCP_DATA_OFF] = 0x50.toByte()   // data offset = 5 (20 bytes)
        buf[ipLen + TCP_FLAGS]    = flags.toByte()
        putU16(buf, ipLen + TCP_WINDOW, window)
        putU16(buf, ipLen + TCP_CHECKSUM, 0)  // placeholder
        if (data.isNotEmpty()) System.arraycopy(data, 0, buf, ipLen + tcpLen, data.size)
        val tcpCs = tcpChecksum(buf, ipLen, tcpLen + data.size, srcIp, dstIp)
        putU16(buf, ipLen + TCP_CHECKSUM, tcpCs)

        return buf
    }

    /**
     * Build a complete IPv4 + UDP packet.
     */
    fun buildUdpPacket(
        srcIp: Int, dstIp: Int,
        srcPort: Int, dstPort: Int,
        data: ByteArray
    ): ByteArray {
        val udpLen = 8 + data.size
        val total  = 20 + udpLen
        val buf    = ByteArray(total)

        buf[0] = 0x45.toByte()
        putU16(buf, 2, total)
        putU16(buf, 4, 0x4322)
        putU16(buf, 6, 0x4000)
        buf[8] = 64
        buf[9] = PROTO_UDP.toByte()
        putU16(buf, IP_CHECKSUM, 0)
        putI32(buf, IP_SRC, srcIp)
        putI32(buf, IP_DST, dstIp)
        val ipCs = checksum(buf, 0, 20)
        putU16(buf, IP_CHECKSUM, ipCs)

        putU16(buf, 20 + UDP_SRC_PORT, srcPort)
        putU16(buf, 20 + UDP_DST_PORT, dstPort)
        putU16(buf, 20 + UDP_LEN, udpLen)
        putU16(buf, 20 + UDP_CHECKSUM, 0)  // placeholder
        System.arraycopy(data, 0, buf, 28, data.size)
        val udpCs = udpChecksum(buf, 20, udpLen, srcIp, dstIp)
        putU16(buf, 20 + UDP_CHECKSUM, udpCs)

        return buf
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    fun intToBytes(ip: Int): ByteArray = byteArrayOf(
        ((ip shr 24) and 0xFF).toByte(),
        ((ip shr 16) and 0xFF).toByte(),
        ((ip shr  8) and 0xFF).toByte(),
        (ip and 0xFF).toByte()
    )

    fun intToIpString(ip: Int): String =
        "\${(ip ushr 24) and 0xFF}.\${(ip ushr 16) and 0xFF}.\${(ip ushr 8) and 0xFF}.\${ip and 0xFF}"

    fun ipStringToInt(ip: String): Int {
        val parts = ip.split(".")
        return (parts[0].toInt() shl 24) or (parts[1].toInt() shl 16) or
               (parts[2].toInt() shl 8)  or parts[3].toInt()
    }
}
