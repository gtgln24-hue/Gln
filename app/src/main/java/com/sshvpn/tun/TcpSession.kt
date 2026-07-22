package com.sshvpn.tun

import com.sshvpn.tun.IpPacketUtils.FLAG_ACK
import com.sshvpn.tun.IpPacketUtils.FLAG_FIN
import com.sshvpn.tun.IpPacketUtils.FLAG_PSH
import com.sshvpn.tun.IpPacketUtils.FLAG_RST
import com.sshvpn.tun.IpPacketUtils.FLAG_SYN
import com.sshvpn.tun.IpPacketUtils.buildTcpPacket
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.*
import java.net.*

private const val TAG      = "TUN/TcpSession"
private const val MSS      = 1360             // max payload per segment
private const val WIN_SIZE = 65535
private const val SO_TIMEOUT_MS = 15_000     // REDUCED from 30s to avoid battery drain

/**
 * Tracks a single TCP flow with SOCKS5 tunneling.
 * 
 * State machine: AWAITING_SOCKS → ESTABLISHED → CLOSING → CLOSED
 */
class TcpSession(
    val key: FlowKey,
    clientIsn: Int,
    private val dstHost: String,
    private val dstPort: Int,
    private val socksPort: Int,
    private val tunWriter: TunWriter
) {
    // ── Sequence tracking ──────────────────────────────────────────────────────
    private val serverIsn    = 0x00_A5_B3_C1.toInt()
    @Volatile var serverSeq = serverIsn
    @Volatile var clientAck = clientIsn + 1

    // ── State ──────────────────────────────────────────────────────────────────
    enum class State { AWAITING_SOCKS, ESTABLISHED, CLOSING, CLOSED }
    @Volatile var state = State.AWAITING_SOCKS

    private var socket: Socket? = null
    private var socksIn:  DataInputStream?  = null
    private var socksOut: OutputStream? = null
    private var relayJob: Job? = null

    // ── SYN+ACK ────────────────────────────────────────────────────────────────

    fun sendSynAck() {
        val pkt = buildTcpPacket(
            srcIp   = key.dstIp,  dstIp   = key.srcIp,
            srcPort = key.dstPort, dstPort = key.srcPort,
            seq     = serverIsn,  ack     = clientAck,
            flags   = FLAG_SYN or FLAG_ACK,
            window  = WIN_SIZE
        )
        tunWriter.write(pkt)
        serverSeq = serverIsn + 1
    }

    // ── Data handling ──────────────────────────────────────────────────────────

    fun handleData(payload: ByteArray, newClientSeq: Int) {
        clientAck = newClientSeq + payload.size
        sendAck()
        if (state != State.ESTABLISHED) return
        try {
            socksOut?.write(payload)
            socksOut?.flush()
        } catch (e: IOException) {
            Timber.tag(TAG).w("SOCKS write failed: $dstHost:$dstPort — \${e.message}")
            sendRst()
            close()
        }
    }

    fun handleClientFin(seqNum: Int) {
        clientAck = seqNum + 1
        sendTcp(FLAG_FIN or FLAG_ACK, ByteArray(0))
        serverSeq++
        close()
    }

    // ── SOCKS5 connect with proper auth negotiation ─────────────────────────

    suspend fun startSocks5() = withContext(Dispatchers.IO) {
        try {
            val sock = Socket()
            sock.soTimeout = SO_TIMEOUT_MS
            sock.connect(InetSocketAddress("127.0.0.1", socksPort), SO_TIMEOUT_MS)
            socket  = sock
            val din  = DataInputStream(sock.getInputStream())
            val dout = sock.getOutputStream()
            socksIn  = din
            socksOut = dout

            // ── SOCKS5 authentication negotiation ──────────────────────────
            // Send: version=5, nmethods=1, method=NO_AUTH
            dout.write(byteArrayOf(0x05, 0x01, 0x00))
            dout.flush()

            val authVersion = din.read()
            val authMethod = din.read()

            if (authVersion != 5) {
                Timber.tag(TAG).w("Invalid SOCKS version: $authVersion")
                drop()
                return@withContext
            }

            when (authMethod.toByte()) {
                0x00.toByte() -> {
                    // NO_AUTH — proceed
                    Timber.tag(TAG).d("SOCKS5 using NO_AUTH method")
                }
                0xFF.toByte() -> {
                    // No acceptable auth method
                    Timber.tag(TAG).w("SOCKS5 server rejected all auth methods")
                    drop()
                    return@withContext
                }
                else -> {
                    // Other auth method — not supported for now
                    Timber.tag(TAG).w("SOCKS5 auth method $authMethod not supported")
                    drop()
                    return@withContext
                }
            }

            // ── SOCKS5 CONNECT request ────────────────────────────────────
            val addrBytes = try {
                InetAddress.getByName(dstHost).address
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to resolve $dstHost: \${e.message}")
                drop()
                return@withContext
            }

            val req = ByteArray(10).also { b ->
                b[0] = 0x05                          // version
                b[1] = 0x01                          // CONNECT command
                b[2] = 0x00                          // reserved
                b[3] = 0x01                          // address type: IPv4
                System.arraycopy(addrBytes, 0, b, 4, 4)
                b[8] = ((dstPort shr 8) and 0xFF).toByte()
                b[9] = (dstPort and 0xFF).toByte()
            }
            dout.write(req)
            dout.flush()

            // ── Read CONNECT response ──────────────────────────────────────
            val reply = ByteArray(10)
            try {
                din.readFully(reply)
            } catch (e: EOFException) {
                Timber.tag(TAG).w("SOCKS5 connection closed unexpectedly")
                drop()
                return@withContext
            }

            if (reply[0] != 0x05.toByte()) {
                Timber.tag(TAG).w("Invalid SOCKS5 response version: \${reply[0]}")
                drop()
                return@withContext
            }

            if (reply[1] != 0x00.toByte()) {
                val errCode = reply[1].toInt() and 0xFF
                val errMsg = when (errCode) {
                    0x01 -> "General SOCKS server failure"
                    0x02 -> "Connection not allowed by ruleset"
                    0x03 -> "Network unreachable"
                    0x04 -> "Host unreachable"
                    0x05 -> "Connection refused"
                    0x06 -> "TTL expired"
                    0x07 -> "Command not supported"
                    0x08 -> "Address type not supported"
                    else -> "Unknown error ($errCode)"
                }
                Timber.tag(TAG).w("SOCKS5 CONNECT failed: $errMsg")
                drop()
                return@withContext
            }

            // ✅ Connection established
            state = State.ESTABLISHED
            Timber.tag(TAG).d("✅ SOCKS5 ESTABLISHED → $dstHost:$dstPort")

            // ── Start relay coroutine ──────────────────────────────────────
            relayJob = coroutineScope {
                launch { relayRemoteToTun() }
            }

        } catch (e: SocketTimeoutException) {
            Timber.tag(TAG).w("SOCKS5 connection timeout to $dstHost:$dstPort")
            drop()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "SOCKS5 connect failed to $dstHost:$dstPort")
            drop()
        }
    }

    // ── Relay incoming data back to TUN ────────────────────────────────────────

    private fun relayRemoteToTun() {
        try {
            val buf = ByteArray(MSS)
            while (state == State.ESTABLISHED) {
                val n = socksIn?.read(buf) ?: break
                if (n < 0) {
                    // EOF from remote
                    if (state == State.ESTABLISHED) {
                        Timber.tag(TAG).d("Remote closed connection: $dstHost:$dstPort")
                        state = State.CLOSING
                        sendTcp(FLAG_FIN or FLAG_ACK, ByteArray(0))
                        serverSeq++
                    }
                    break
                }
                if (n > 0) {
                    val chunk = buf.copyOf(n)
                    sendTcp(FLAG_PSH or FLAG_ACK, chunk)
                    serverSeq += n
                }
            }
        } catch (e: SocketTimeoutException) {
            Timber.tag(TAG).d("SOCKS read timeout: $dstHost:$dstPort")
        } catch (e: IOException) {
            if (state == State.ESTABLISHED) {
                Timber.tag(TAG).d("SOCKS I/O error: $dstHost:$dstPort — \${e.message}")
            }
        } finally {
            close()
        }
    }

    // ── TCP packet helpers ─────────────────────────────────────────────────────

    private fun sendTcp(flags: Int, data: ByteArray) {
        val pkt = buildTcpPacket(
            srcIp   = key.dstIp,  dstIp   = key.srcIp,
            srcPort = key.dstPort, dstPort = key.srcPort,
            seq     = serverSeq,  ack     = clientAck,
            flags   = flags,      window  = WIN_SIZE,
            data    = data
        )
        tunWriter.write(pkt)
    }

    private fun sendAck()  = sendTcp(FLAG_ACK, ByteArray(0))

    private fun sendRst() {
        val pkt = buildTcpPacket(
            srcIp   = key.dstIp,  dstIp   = key.srcIp,
            srcPort = key.dstPort, dstPort = key.srcPort,
            seq     = serverSeq,  ack     = clientAck,
            flags   = FLAG_RST or FLAG_ACK
        )
        tunWriter.write(pkt)
    }

    private fun drop() {
        sendRst()
        close()
    }

    fun close() {
        state = State.CLOSED
        relayJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        socksIn = null
        socksOut = null
    }
}

/** TCP flow 5-tuple identifier */
data class FlowKey(
    val srcIp:   Int,
    val srcPort: Int,
    val dstIp:   Int,
    val dstPort: Int
)

/** Abstraction for writing packets to TUN */
fun interface TunWriter {
    fun write(packet: ByteArray)
}
