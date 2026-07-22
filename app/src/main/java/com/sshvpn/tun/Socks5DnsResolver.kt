package com.sshvpn.tun

import timber.log.Timber
import java.io.*
import java.net.*

private const val TAG = "TUN/DNS"

/**
 * Resolves DNS queries via SOCKS5 TCP tunnel.
 * Supports DNS-over-TCP (RFC 1035 §4.2.2).
 */
object Socks5DnsResolver {

    fun resolve(
        dnsQuery: ByteArray,
        dnsServerIp: Int,
        socksPort: Int,
        timeoutMs: Int = 5_000
    ): ByteArray? {
        return try {
            Socket().use { sock ->
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)

                val din  = DataInputStream(sock.getInputStream())
                val dout = sock.getOutputStream()

                // ── SOCKS5 auth negotiation ────────────────────────────────
                dout.write(byteArrayOf(0x05, 0x01, 0x00))
                dout.flush()
                
                val authVersion = din.read()
                val authMethod = din.read()
                if (authVersion != 5 || authMethod == 0xFF) {
                    Timber.tag(TAG).d("SOCKS auth failed")
                    return null
                }

                // ── SOCKS5 CONNECT to DNS server ───────────────────────────
                val addrBytes = IpPacketUtils.intToBytes(dnsServerIp)
                val req = ByteArray(10)
                req[0] = 0x05
                req[1] = 0x01           // CONNECT
                req[2] = 0x00           // reserved
                req[3] = 0x01           // IPv4
                System.arraycopy(addrBytes, 0, req, 4, 4)
                req[8] = 0
                req[9] = 53             // DNS port
                dout.write(req)
                dout.flush()

                val reply = ByteArray(10)
                din.readFully(reply)
                if (reply[1] != 0x00.toByte()) {
                    Timber.tag(TAG).d("SOCKS CONNECT failed: \${reply[1]}")
                    return null
                }

                // ── Send DNS-over-TCP query ────────────────────────────────
                val lenBuf = ByteArray(2)
                lenBuf[0] = ((dnsQuery.size shr 8) and 0xFF).toByte()
                lenBuf[1] = (dnsQuery.size and 0xFF).toByte()
                dout.write(lenBuf)
                dout.write(dnsQuery)
                dout.flush()

                // ── Read DNS response ──────────────────────────────────────
                val respLen = ((din.read() and 0xFF) shl 8) or (din.read() and 0xFF)
                if (respLen <= 0 || respLen > 65535) {
                    Timber.tag(TAG).d("Invalid DNS response length: $respLen")
                    return null
                }
                
                val response = ByteArray(respLen)
                din.readFully(response)
                response
            }
        } catch (e: SocketTimeoutException) {
            Timber.tag(TAG).d("DNS resolve timeout")
            null
        } catch (e: Exception) {
            Timber.tag(TAG).d("DNS resolve failed: \${e.message}")
            null
        }
    }
}
