package com.sshvpn.service

import android.net.VpnService
import com.sshvpn.model.ConnectionProfile
import com.sshvpn.model.ErrorCode
import com.sshvpn.model.VpnState
import com.sshvpn.repository.VpnStateRepository
import com.sshvpn.ssh.SSHManager
import com.sshvpn.ssh.SshConnectionException
import com.sshvpn.tun.TunBridge
import com.sshvpn.tun.TunConfig
import com.sshvpn.tun.TunCreationException
import com.sshvpn.tun.TunManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private const val TAG = "Service/TunnelManager"

/**
 * Orchestrates full VPN tunnel lifecycle:
 * 1. Create TUN interface with full routing
 * 2. Connect SSH + start SOCKS5
 * 3. Start packet bridge (pure-Kotlin tun2socks)
 * 4. Route ALL device traffic through tunnel
 */
class TunnelManager @Inject constructor(
    private val sshManager: SSHManager,
    private val vpnStateRepo: VpnStateRepository
) {
    private var tunManager: TunManager? = null
    private var tunBridge: TunBridge? = null
    private var bridgeJob: Job? = null

    /**
     * Full connect sequence — routes all device traffic through SSH tunnel.
     */
    suspend fun connect(
        vpnService: VpnService,
        profile: ConnectionProfile,
        scope: CoroutineScope
    ) {
        try {
            // ── Step 1: Create TUN interface ──────────────────────────────
            vpnStateRepo.emit(VpnState.CreatingTun)
            val tm = TunManager(vpnService).also { tunManager = it }

            val excludedApps = profile.splitTunnelApps
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val tunConfig = TunConfig.fromProfile(
                dns1         = profile.dns1,
                dns2         = profile.dns2,
                socksPort    = profile.localSocksPort,
                excludedApps = excludedApps,
                upstreamDns1 = "8.8.8.8",
                upstreamDns2 = "8.8.4.4"
            )
            val tunFd = tm.createTun(tunConfig)
            Timber.tag(TAG).i("✅ TUN interface created")

            // ── Step 2: SSH + SOCKS5 ──────────────────────────────────────
            vpnStateRepo.emit(VpnState.ResolvingServer(profile.host))
            vpnStateRepo.emit(VpnState.ConnectingTcp(profile.host, profile.port))
            vpnStateRepo.emit(VpnState.SshAuthenticating)

            val socksPort = sshManager.connect(profile)
            vpnStateRepo.emit(VpnState.SocksStarted(socksPort))
            Timber.tag(TAG).i("✅ SSH SOCKS5 ready on port \$socksPort")

            // ── Step 3: Pure-Kotlin tun2socks bridge ──────────────────────
            vpnStateRepo.emit(VpnState.Tun2SocksRunning)
            val bridge = TunBridge().also { tunBridge = it }

            bridgeJob = scope.launch {
                try {
                    bridge.start(
                        tunFd       = tunFd,
                        socksPort   = socksPort,
                        mtu         = tunConfig.mtu,
                        scope       = scope,
                        upstreamDns1 = tunConfig.upstreamDns1,
                        upstreamDns2 = tunConfig.upstreamDns2
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Packet bridge crashed")
                    vpnStateRepo.emit(
                        VpnState.Error(
                            message = "Packet bridge stopped: \${e.message}",
                            cause   = e,
                            code    = ErrorCode.TUN2SOCKS_FAILED
                        )
                    )
                    disconnect()
                }
            }

            // ── Step 4: Connected ─────────────────────────────────────────
            vpnStateRepo.emit(VpnState.Connected(profile))
            Timber.tag(TAG).i("✅ Tunnel FULLY connected — ALL device traffic routed through SSH")

        } catch (e: TunCreationException) {
            Timber.tag(TAG).e(e, "TUN creation failed")
            vpnStateRepo.emit(VpnState.Error(e.message ?: "TUN failed", e, ErrorCode.TUN_CREATION_FAILED))
            disconnect()
            throw e

        } catch (e: SshConnectionException) {
            Timber.tag(TAG).e(e, "SSH failed: \${e.code}")
            vpnStateRepo.emit(VpnState.Error(e.message ?: "SSH error", e, e.code))
            disconnect()
            throw e

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Unexpected tunnel error")
            vpnStateRepo.emit(VpnState.Error(e.message ?: "Unknown error", e))
            disconnect()
            throw e
        }
    }

    /** Cleanly tear down everything. Safe to call multiple times. */
    fun disconnect() {
        Timber.tag(TAG).i("Disconnecting tunnel")
        runCatching { tunBridge?.stop() }
        runCatching { bridgeJob?.cancel() }
        runCatching { sshManager.disconnect() }
        runCatching { tunManager?.closeTun() }
        tunBridge  = null
        bridgeJob  = null
        tunManager = null
    }

    fun isConnected(): Boolean =
        sshManager.isConnected() && (tunBridge?.isRunning() == true)
}
