package com.sshvpn.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted SSH connection profile.
 */
@Entity(tableName = "connection_profiles")
data class ConnectionProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String = "My Server",

    // --- Server ---
    val host: String = "",
    val port: Int = 22,

    // --- Auth ---
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",          // encrypted at rest via CryptoUtils
    val privateKey: String = "",        // PEM/OpenSSH private key content (encrypted)
    val privateKeyPassphrase: String = "",

    // --- Tunnel ---
    val localSocksPort: Int = 10808,
    val remoteDnsPort: Int = 53,

    // --- DNS ---
    val dns1: String = "1.1.1.1",
    val dns2: String = "8.8.8.8",

    // --- Routing ---
    val routeAll: Boolean = true,
    val splitTunnelApps: String = "",   // comma-separated package names

    // --- Meta ---
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0L,
    val isDefault: Boolean = false
)

enum class AuthType {
    PASSWORD,
    PRIVATE_KEY
}
