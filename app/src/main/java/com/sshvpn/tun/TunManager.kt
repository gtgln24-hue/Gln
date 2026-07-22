package com.sshvpn.tun

import android.net.VpnService
import android.os.ParcelFileDescriptor
import timber.log.Timber

private const val TAG = "TUN/Manager"

/**
 * Builds and owns the Android TUN interface file descriptor.
 * Routes ALL traffic (IPv4 0.0.0.0/0) plus DNS through VPN.
 */
class TunManager(private val vpnService: VpnService) {

    private var tunFd: ParcelFileDescriptor? = null

    /**
     * Create the VPN TUN interface with full device routing.
     * 
     * Routes:
     * - All IPv4 traffic (0.0.0.0/0)
     * - DNS queries to configured DNS servers
     * - Excludes the VPN app itself (prevent loop)
     *
     * @return The ParcelFileDescriptor for the TUN interface
     * @throws TunCreationException on failure
     */
    fun createTun(config: TunConfig): ParcelFileDescriptor {
        Timber.tag(TAG).i(
            "Creating TUN: addr=%s/%d mtu=%d dns=%s,%s upstream=%s,%s",
            config.virtualAddress, config.virtualAddressPrefix,
            config.mtu, config.dns1, config.dns2,
            config.upstreamDns1, config.upstreamDns2
        )

        val builder = vpnService.Builder()
            .setSession("SSH VPN")
            .setMtu(config.mtu)
            // Set virtual IP for VPN interface
            .addAddress(config.virtualAddress, config.virtualAddressPrefix)
            
            // Add DNS servers that Android will configure on the VPN interface
            .addDnsServer(config.dns1)
            .addDnsServer(config.dns2)
            .addSearchDomain(config.searchDomain)
            
            // CRITICAL: Route ALL IPv4 traffic through the VPN
            .addRoute(config.ipv4Route, config.ipv4RoutePrefix)
            
            // CRITICAL: Explicitly route DNS servers through VPN so queries are tunneled
            // This ensures DNS queries go through SOCKS5 proxy, not system DNS
            .addRoute(config.dns1, 32)
            .addRoute(config.dns2, 32)

        // Optional IPv6
        if (config.enableIpv6) {
            builder.addRoute(config.ipv6Route, config.ipv6RoutePrefix)
        }

        // Exclude VPN app itself to avoid routing loop
        try {
            builder.addDisallowedApplication(vpnService.packageName)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not exclude own package from VPN")
        }

        // Split-tunnelling — exclude requested apps
        for (pkg in config.excludedApps) {
            try {
                builder.addDisallowedApplication(pkg.trim())
            } catch (e: Exception) {
                Timber.tag(TAG).w("Package '%s' not installed, skipping exclusion", pkg)
            }
        }

        // Establish the VPN interface
        val fd = builder.establish()
            ?: throw TunCreationException(
                "VpnService.Builder.establish() returned null — " +
                "VPN permission not granted? Did user revoke VPN access?"
            )

        tunFd = fd
        Timber.tag(TAG).i("✅ TUN interface created successfully, fd=%d", fd.fd)
        return fd
    }

    /** Close the TUN interface. Safe to call multiple times. */
    fun closeTun() {
        try {
            tunFd?.close()
            Timber.tag(TAG).i("TUN interface closed")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error closing TUN fd")
        } finally {
            tunFd = null
        }
    }

    fun isActive(): Boolean = tunFd != null
}

class TunCreationException(message: String, cause: Throwable? = null) : Exception(message, cause)
