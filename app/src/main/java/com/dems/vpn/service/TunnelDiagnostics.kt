package com.dems.vpn.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Diagnostic temps réel du tunnel, alimenté uniquement par des observations
 * réelles du service VPN. Aucune valeur n'est inventée : une donnée non encore
 * observée reste `null`.
 */
object TunnelDiagnostics {

    /**
     * Phase la plus avancée atteinte par la dernière sonde traversant le tunnel
     * complet (port local → négociation SOCKS → relais → réponse HTTP).
     * `AUTH` ne prouve que le port local, `RELAY` que le relais répond, `OK`
     * que le serveur a bien transporté une réponse HTTP.
     */
    enum class Probe { UNKNOWN, PORT_CLOSED, AUTH, RELAY, HTTP, OK }

    data class Snapshot(
        /** Cœur Xray vivant selon son contrôleur. */
        val coreRunning: Boolean = false,
        /** tun2socks a démarré sans erreur (API hev sans lecture inverse). */
        val hevRunning: Boolean? = null,
        /** Port SOCKS local joignable lors de la dernière sonde. */
        val socksPortOpen: Boolean? = null,
        /** Octets cumulés de l'outbound "proxy" (depuis ce démarrage). */
        val proxyBytesUp: Long = 0L,
        val proxyBytesDown: Long = 0L,
        /** Résultat de la dernière sonde. */
        val probe: Probe = Probe.UNKNOWN,
        /** Durée mesurée (ms) de la dernière sonde OK, 0 si non applicable. */
        val probeMs: Long = 0L,
        /** Horodatage (epoch ms) de la dernière sonde, 0 si aucune. */
        val probeAt: Long = 0L,
        /** Réseau sous-jacent observé (Wi-Fi, Cellulaire…). */
        val underlying: String = "",
        /**
         * Chemin TUN → tun2socks prouvé uniquement par une croissance des
         * compteurs d'octets de l'interface TUN (trafic applicatif réel).
         * La sonde SOCKS traverse la boucle locale et CE TUN pas : `null`
         * signifie « jamais observé ».
         */
        val tunPathVerified: Boolean? = null,
        /** Octets cumulés (rx+tx) lus sur l'interface TUN, 0 si inconnu. */
        val tunBytes: Long = 0L
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Mise à jour atomique du cliché par le service. */
    fun update(transform: Snapshot.() -> Snapshot) {
        _state.value = _state.value.transform()
    }

    /** Réinitialisation complète (arrêt du tunnel). */
    fun reset() {
        _state.value = Snapshot()
    }
}
