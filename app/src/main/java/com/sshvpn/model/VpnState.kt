package com.sshvpn.model

/**
 * Exhaustive representation of every VPN lifecycle state.
 * Emitted via [com.sshvpn.repository.VpnStateRepository].
 */
sealed class VpnState {

    /** No active connection; nothing is running. */
    object Idle : VpnState()

    /** VPN permission dialog has been shown to the user. */
    object RequestingPermission : VpnState()

    /** VpnService.Builder has been invoked; TUN fd being established. */
    object CreatingTun : VpnState()

    /** DNS resolution of the SSH host underway. */
    data class ResolvingServer(val host: String) : VpnState()

    /** TCP socket connecting to SSH host:port. */
    data class ConnectingTcp(val host: String, val port: Int) : VpnState()

    /** SSH key exchange + authentication in progress. */
    object SshAuthenticating : VpnState()

    /** SOCKS5 local port is bound, waiting for tun2socks to connect. */
    data class SocksStarted(val localPort: Int) : VpnState()

    /** tun2socks is running, bridging TUN traffic into SOCKS. */
    object Tun2SocksRunning : VpnState()

    /** Fully operational — traffic flowing through the tunnel. */
    data class Connected(
        val profile: ConnectionProfile,
        val connectedAt: Long = System.currentTimeMillis(),
        val bytesTx: Long = 0L,
        val bytesRx: Long = 0L,
        val txSpeedBps: Long = 0L,
        val rxSpeedBps: Long = 0L
    ) : VpnState()

    /** User or system initiated clean shutdown. */
    object Disconnecting : VpnState()

    /** Terminal error — see [message] and optional [cause]. */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val code: ErrorCode = ErrorCode.UNKNOWN
    ) : VpnState()
}
