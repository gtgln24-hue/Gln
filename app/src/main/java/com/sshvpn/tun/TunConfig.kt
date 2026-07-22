package com.sshvpn.tun

/**
 * Immutable configuration for the TUN (VPN) interface.
 * All IP address strings are in dotted-decimal notation.
 */
data class TunConfig(
    /** Virtual IP assigned to the phone-side TUN interface */
    val virtualAddress: String = "10.0.0.2",
    val virtualAddressPrefix: Int = 24,

    /** MTU — 1500 for LAN; 1400 for SSH with encapsulation overhead */
    val mtu: Int = 1400,

    /** Primary and secondary DNS servers — MUST be routed explicitly */
    val dns1: String = "1.1.1.1",
    val dns2: String = "8.8.8.8",

    /** Upstream DNS server IPs to use when app queries VPN gateway DNS */
    val upstreamDns1: String = "8.8.8.8",      // Google DNS
    val upstreamDns2: String = "8.8.4.4",      // Google DNS secondary

    /** IPv4 route to capture — 0.0.0.0/0 captures everything */
    val ipv4Route: String = "0.0.0.0",
    val ipv4RoutePrefix: Int = 0,

    /** Enable IPv6 routing */
    val enableIpv6: Boolean = false,
    val ipv6Route: String = "::",
    val ipv6RoutePrefix: Int = 0,

    /** Session / search domain for DNS */
    val searchDomain: String = "vpn",

    /** Local SOCKS5 port produced by SSH dynamic forwarding */
    val socksBridgePort: Int = 10808,

    /** Package names excluded from VPN (split-tunnelling, comma-separated) */
    val excludedApps: List<String> = emptyList()
) {
    companion object {
        fun fromProfile(
            dns1: String,
            dns2: String,
            socksPort: Int,
            excludedApps: List<String> = emptyList(),
            upstreamDns1: String = "8.8.8.8",
            upstreamDns2: String = "8.8.4.4"
        ) = TunConfig(
            dns1 = dns1,
            dns2 = dns2,
            upstreamDns1 = upstreamDns1,
            upstreamDns2 = upstreamDns2,
            socksBridgePort = socksPort,
            excludedApps = excludedApps
        )
    }
}
