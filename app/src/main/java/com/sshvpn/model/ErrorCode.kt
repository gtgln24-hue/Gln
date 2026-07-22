package com.sshvpn.model

/**
 * Exhaustive error codes for VPN connection failures.
 * Maps to user-friendly error messages in the UI.
 */
enum class ErrorCode {
    /** Unknown or unclassified error */
    UNKNOWN,

    /** User denied VPN permission when prompted */
    VPN_PERMISSION_DENIED,

    /** Failed to create TUN interface (kernel/Android limitation) */
    TUN_CREATION_FAILED,

    /** SSH host DNS resolution failed */
    DNS_RESOLUTION_FAILED,

    /** Failed to establish TCP connection to SSH host */
    TCP_CONNECTION_FAILED,

    /** SSH authentication (password/key) failed */
    SSH_AUTH_FAILED,

    /** SSH connection timed out (30s default) */
    SSH_TIMEOUT,

    /** SOCKS5 port binding failed (port already in use) */
    SOCKS_BIND_FAILED,

    /** tun2socks packet bridge crashed or disconnected */
    TUN2SOCKS_FAILED,

    /** Network is unreachable (no internet) */
    NETWORK_UNREACHABLE,

    /** TCP connection reset by peer (network dropped) */
    CONNECTION_RESET;

    /**
     * User-friendly error message for this code.
     */
    fun toDisplayMessage(): String = when (this) {
        UNKNOWN -> "An unknown error occurred"
        VPN_PERMISSION_DENIED -> "VPN permission was denied. Please grant permission to use VPN."
        TUN_CREATION_FAILED -> "Failed to create VPN tunnel. Try restarting your device."
        DNS_RESOLUTION_FAILED -> "Could not resolve SSH server hostname. Check your internet and server address."
        TCP_CONNECTION_FAILED -> "Failed to connect to SSH server. Check your network connection and server address:port."
        SSH_AUTH_FAILED -> "SSH authentication failed. Check your username and password/private key."
        SSH_TIMEOUT -> "SSH connection timed out. Your network may be slow or the server unreachable."
        SOCKS_BIND_FAILED -> "Failed to start SOCKS proxy. Another app may be using the port."
        TUN2SOCKS_FAILED -> "Packet bridge crashed. Please disconnect and try again."
        NETWORK_UNREACHABLE -> "Network unreachable. Check your internet connection."
        CONNECTION_RESET -> "Connection was reset. Your network may have changed or server went offline."
    }

    /**
     * Whether this error is recoverable by retry.
     */
    fun isRecoverable(): Boolean = when (this) {
        VPN_PERMISSION_DENIED,
        TUN_CREATION_FAILED,
        SOCKS_BIND_FAILED -> false  // User action needed

        DNS_RESOLUTION_FAILED,
        TCP_CONNECTION_FAILED,
        SSH_AUTH_FAILED,
        SSH_TIMEOUT,
        NETWORK_UNREACHABLE,
        CONNECTION_RESET,
        TUN2SOCKS_FAILED,
        UNKNOWN -> true  // Can retry
    }
}
