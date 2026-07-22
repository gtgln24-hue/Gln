package com.sshvpn.ssh

import timber.log.Timber
import java.net.InetSocketAddress
import java.net.ServerSocket

private const val TAG = "SSH/SocksProxy"

/**
 * Manages JSch dynamic port-forwarding SOCKS5 proxy.
 * Exposes a local SOCKS5 server that tunnels all connections through SSH.
 */
class SocksProxy(private val session: Session) {

    private var boundPort: Int = -1
    @Volatile private var running = false

    /**
     * Start SOCKS5 dynamic forwarding.
     * 
     * @param preferredPort Local port to bind (0 = let OS choose)
     * @return The actual bound port
     */
    fun start(preferredPort: Int = 10808): Int {
        require(!running) { "SocksProxy already running on port \$boundPort" }

        val port = if (preferredPort > 0) {
            // Try preferred port; if busy, fall back to OS assignment
            try {
                ServerSocket(preferredPort, 1, null).use { it.close() }
                preferredPort
            } catch (_: Exception) {
                Timber.tag(TAG).w("Preferred port \$preferredPort busy, using OS-assigned port")
                0
            }
        } else {
            0
        }

        Timber.tag(TAG).i("Starting SOCKS5 dynamic forwarding on 127.0.0.1:%d", port)

        session.setPortForwardingL(
            /* bind_address */ "127.0.0.1",
            /* lport        */ port,
            /* host         */ "dynamic",
            /* rport        */ 0
        )

        boundPort = port
        running = true
        Timber.tag(TAG).i("✅ SOCKS5 proxy running on 127.0.0.1:%d", boundPort)
        return boundPort
    }

    fun stop() {
        if (!running) return
        try {
            session.delPortForwardingL("127.0.0.1", boundPort)
            Timber.tag(TAG).i("SOCKS5 port forwarding removed on port %d", boundPort)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error removing port forwarding")
        } finally {
            running = false
            boundPort = -1
        }
    }

    fun getBoundPort(): Int = boundPort
    fun isRunning(): Boolean = running
}
