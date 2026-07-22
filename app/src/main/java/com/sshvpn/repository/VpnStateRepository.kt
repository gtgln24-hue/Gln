package com.sshvpn.repository

import com.sshvpn.model.TrafficStats
import com.sshvpn.model.VpnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for [VpnState].
 *
 * Both the UI (via ViewModel) and the VPN service observe / emit through this repository,
 * decoupled from direct Service ↔ Activity communication.
 */
@Singleton
class VpnStateRepository @Inject constructor() {

    private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TrafficStats())
    val stats: StateFlow<TrafficStats> = _stats.asStateFlow()

    suspend fun emit(state: VpnState) {
        _state.emit(state)
    }

    /** Non-suspending emit for use from callbacks / non-coroutine contexts. */
    fun tryEmit(state: VpnState) {
        _state.tryEmit(state)
    }

    suspend fun emitStats(stats: TrafficStats) {
        _stats.emit(stats)
    }

    fun currentState(): VpnState = _state.value
    fun isConnected(): Boolean = _state.value is VpnState.Connected
}
