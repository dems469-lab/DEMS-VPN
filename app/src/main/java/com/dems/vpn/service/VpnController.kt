package com.dems.vpn.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnState { STOPPED, CONNECTING, CONNECTED, STOPPING, ERROR }

data class VpnStatus(
    val state: VpnState = VpnState.STOPPED,
    val serverName: String = "",
    val since: Long = 0L,
    val message: String? = null
) {
    val busy: Boolean
        get() = state == VpnState.CONNECTING || state == VpnState.CONNECTED || state == VpnState.STOPPING
}

/** État partagé entre le service VPN et l'interface (même processus). */
object VpnController {

    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    fun connecting(serverName: String) {
        _status.value = VpnStatus(VpnState.CONNECTING, serverName)
    }

    fun connected(serverName: String) {
        _status.value = VpnStatus(VpnState.CONNECTED, serverName, System.currentTimeMillis())
    }

    fun stopping() {
        _status.value = _status.value.copy(state = VpnState.STOPPING, message = null)
    }

    fun stopped() {
        _status.value = VpnStatus()
    }

    fun error(message: String, serverName: String = _status.value.serverName) {
        _status.value = VpnStatus(VpnState.ERROR, serverName, 0L, message)
    }
}
