package com.dems.vpn.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnState { STOPPED, CONNECTING, CONNECTED, STOPPING, ERROR }

data class VpnStatus(
    val state: VpnState = VpnState.STOPPED,
    val serverName: String = "",
    val since: Long = 0L,
    val message: String? = null,
    val uploadTotal: Long = 0L,
    val downloadTotal: Long = 0L,
    val uploadRate: Long = 0L,
    val downloadRate: Long = 0L,
    /** Nombre de reconnexions depuis la connexion initiale. */
    val reconnects: Int = 0
) {
    val busy: Boolean
        get() = state == VpnState.CONNECTING || state == VpnState.CONNECTED || state == VpnState.STOPPING
}

/** État partagé entre le service VPN et l'interface (même processus). */
object VpnController {

    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    fun connecting(serverName: String, resetSession: Boolean = false) {
        val keep = if (resetSession) 0 else _status.value.reconnects
        _status.value = VpnStatus(VpnState.CONNECTING, serverName, reconnects = keep)
    }

    fun connected(serverName: String) {
        _status.value = VpnStatus(
            VpnState.CONNECTED,
            serverName,
            System.currentTimeMillis(),
            reconnects = _status.value.reconnects
        )
    }

    /** Une tentative de reconnexion démarre : incrémente le compteur de session. */
    fun reconnectAttempt() {
        val current = _status.value
        if (current.state == VpnState.STOPPED) return
        _status.value = current.copy(reconnects = current.reconnects + 1)
    }

    fun stopping() {
        _status.value = _status.value.copy(state = VpnState.STOPPING, message = null)
    }

    fun stopped() {
        _status.value = VpnStatus()
    }

    fun traffic(uploadTotal: Long, downloadTotal: Long, uploadRate: Long, downloadRate: Long) {
        val current = _status.value
        if (current.state != VpnState.CONNECTED) return
        _status.value = current.copy(
            uploadTotal = uploadTotal,
            downloadTotal = downloadTotal,
            uploadRate = uploadRate,
            downloadRate = downloadRate
        )
    }

    fun error(message: String, serverName: String = _status.value.serverName) {
        _status.value = VpnStatus(VpnState.ERROR, serverName, 0L, message)
    }
}
