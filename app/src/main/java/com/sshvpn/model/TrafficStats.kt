package com.sshvpn.model

data class TrafficStats(
    val bytesTx: Long = 0L,
    val bytesRx: Long = 0L,
    val txSpeedBps: Long = 0L,
    val rxSpeedBps: Long = 0L,
    val uptimeSeconds: Long = 0L
) {
    val totalBytes: Long get() = bytesTx + bytesRx
}
