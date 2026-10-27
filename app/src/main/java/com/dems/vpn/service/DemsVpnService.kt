package com.dems.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dems.vpn.MainActivity
import com.dems.vpn.R
import com.dems.vpn.core.XrayConfigBuilder
import com.dems.vpn.core.XrayCore
import com.dems.vpn.data.ProfileStore
import com.dems.vpn.data.SettingsStore
import com.dems.vpn.data.VpnSettings
import com.dems.vpn.data.numericDns
import hev.htproxy.TProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Service VPN : crée l'interface TUN, démarre Xray (proxy local) puis
 * hev-socks5-tunnel qui relie le TUN au proxy.
 */
class DemsVpnService : VpnService() {

    companion object {
        private const val TAG = "DemsVpnService"

        const val ACTION_START = "com.dems.vpn.action.START"
        const val ACTION_STOP = "com.dems.vpn.action.STOP"
        const val EXTRA_PROFILE_ID = "com.dems.vpn.extra.PROFILE_ID"

        private const val CHANNEL_ID = "dems_vpn_status"
        private const val NOTIFICATION_ID = 1001

        private const val CLIENT_IPV4 = "10.10.10.2"
        private const val CLIENT_IPV6 = "fd00::2"
        private const val DNS_IPV6 = "2606:4700:4700::1111"

        private const val STATS_INTERVAL_MS = 2_000L
        private const val RECONNECT_MIN_UPTIME_MS = 5_000L
        private const val RECONNECT_MAX_ATTEMPTS = 8
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
        private const val STABLE_RESET_MS = 60_000L

        private const val WATCHDOG_INTERVAL_MS = 20_000L
        private const val VERIFY_ATTEMPTS = 2
        private const val VERIFY_RETRY_DELAY_MS = 2_000L
        private const val WATCHDOG_MAX_FAILURES = 2
        private const val PROBE_TIMEOUT_MS = 5_000
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        fun start(context: Context, profileId: String?) {
            val intent = Intent(context, DemsVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DemsVpnService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Impossible d'arrêter le service", it) }
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var running = false
    private var starting = false
    @Volatile private var stopRequested = false
    @Volatile private var reconnecting = false
    private var connectedAt = 0L

    private var scope: CoroutineScope? = null
    private var statsJob: Job? = null
    private var watchdogJob: Job? = null
    private var watchdogFailures = 0
    @Volatile private var backoffAttempt = 0

    // Identifiants du proxy local, conservés pour la sonde du watchdog.
    private var activeSocksUser: String = ""
    private var activeSocksPass: String = ""
    private var activeSocksPort: Int = 10808
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var currentProfileId: String? = null
    private var currentProfileName: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        // Redémarrage système (START_STICKY, intent null) : ne relancer que si
        // l'utilisateur était connecté au moment de la mort du processus.
        if (intent == null && !SettingsStore.wasConnected()) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notification_connecting))
            )
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // Déjà connecté (ou connexion en cours) : ne rien relancer.
        if (running || starting || reconnecting) return START_STICKY

        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
            ?: ProfileStore.selectedId.value
        if (profileId == null) {
            VpnController.error(getString(R.string.error_no_server))
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_connecting))
        )

        val profile = ProfileStore.find(profileId)
        if (profile == null) {
            fail(getString(R.string.error_profile))
            return START_NOT_STICKY
        }

        starting = true
        stopRequested = false
        currentProfileId = profileId
        currentProfileName = profile.name
        VpnController.connecting(profile.name, resetSession = true)
        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        jobScope.launch {
            try {
                startVpn(profileId)
            } catch (t: Throwable) {
                Log.e(TAG, "Démarrage interrompu", t)
                if (!running) fail(t.message ?: getString(R.string.error_tun))
            } finally {
                starting = false
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "Permission VPN révoquée")
        stopVpn()
    }

    override fun onDestroy() {
        cleanup()
        // Une erreur doit rester visible dans l'interface après l'arrêt du service.
        if (VpnController.status.value.state != VpnState.ERROR) {
            VpnController.stopped()
        }
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    // ------------------------------------------------------------- Démarrage

    private suspend fun startVpn(profileId: String, retrying: Boolean = false): Boolean {
        val profile = ProfileStore.find(profileId)
        if (profile == null) return abort(retrying, R.string.error_profile)
        val settings = SettingsStore.current()

        if (VpnService.prepare(this) != null) {
            return abort(retrying, R.string.error_vpn_permission)
        }

        val socksUser = SettingsStore.sessionToken()
        val socksPass = SettingsStore.sessionToken()
        val config = XrayConfigBuilder.build(
            profile = profile,
            settings = settings,
            socksPort = settings.socksPort,
            httpPort = settings.httpPort,
            socksUser = socksUser,
            socksPass = socksPass
        )

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(settings.mtu)
            .addAddress(CLIENT_IPV4, 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(settings.numericDns())

        // IPv6 est toujours attaché au TUN, même désactivé : la route ::/0
        // capture tout le trafic v6 pour qu'aucun paquet ne puisse sortir en
        // direct. Quand l'option est désactivée, Xray le bloque explicitement.
        builder.addAddress(CLIENT_IPV6, 126)
        builder.addRoute("::", 0)
        if (settings.ipv6) builder.addDnsServer(DNS_IPV6)

        applySplitTunneling(builder, settings)

        val descriptor = try {
            builder.establish()
        } catch (t: Throwable) {
            Log.e(TAG, "establish()", t)
            null
        }
        if (descriptor == null) return abort(retrying, R.string.error_tun)
        tun = descriptor

        if (stopRequested) {
            cleanup()
            return false
        }

        activeSocksUser = socksUser
        activeSocksPass = socksPass
        activeSocksPort = settings.socksPort

        acquireWakeLock(settings)

        val startResult = XrayCore.start(this, config)
        if (startResult.isFailure) {
            return abortMessage(
                retrying,
                "Xray : ${startResult.exceptionOrNull()?.message ?: "échec du démarrage"}"
            )
        }

        if (stopRequested) {
            cleanup()
            return false
        }

        if (!startTun2Socks(descriptor, settings, socksUser, socksPass)) {
            return abort(retrying, R.string.error_tun2socks)
        }

        if (stopRequested) {
            cleanup()
            return false
        }

        running = true
        currentProfileId = profileId
        currentProfileName = profile.name
        TunnelDiagnostics.update {
            copy(
                coreRunning = XrayCore.isRunning(),
                hevRunning = true,
                probe = TunnelDiagnostics.Probe.UNKNOWN,
                probeAt = 0L
            )
        }

        // « Connecté » n'est affiché qu'après une preuve réelle : une requête
        // HTTP complète à travers TUN → hev → Xray → serveur (le CONNECT par
        // domaine impose en plus une résolution DNS par le cœur).
        VpnController.verifying(profile.name)
        updateNotification(getString(R.string.status_verifying))

        val verification = verifyTunnel()
        if (stopRequested) {
            cleanup()
            return false
        }
        if (!verification.ok) {
            return abortMessage(
                retrying,
                getString(R.string.error_tunnel_no_traffic) + " (${verification.cause})"
            )
        }

        connectedAt = SystemClock.elapsedRealtime()
        SettingsStore.setWasConnected(true)
        VpnController.connected(profile.name)
        updateNotification(getString(R.string.notification_connected, profile.name))

        registerNetworkCallback()
        startStatsLoop()
        startWatchdog()
        return true
    }

    /**
     * Échec pendant une reconnexion : on ne coupe pas le service, on laisse la
     * boucle de retry (backoff) relancer une tentative. En connexion initiale,
     * l'erreur est remontée à l'utilisateur.
     */
    private fun abort(retrying: Boolean, messageRes: Int): Boolean =
        abortMessage(retrying, getString(messageRes))

    private fun abortMessage(retrying: Boolean, message: String): Boolean {
        if (retrying) {
            Log.w(TAG, "Tentative de reconnexion échouée : $message")
            runCatching { stopPipeline() }
        } else {
            fail(message)
        }
        return false
    }

    /**
     * Split tunneling dans les deux sens :
     * - exclusion (défaut) : les apps choisies contournent le VPN ;
     * - inclusion : seules les apps choisies passent par le VPN.
     *
     * Le processus de l'application (Xray) est toujours exclu, sinon son
     * propre trafic repartirait dans l'interface TUN (boucle).
     */
    private fun applySplitTunneling(builder: Builder, settings: VpnSettings) {
        val targets = settings.bypassApps.filter { it != packageName }

        if (settings.splitInclude && targets.isNotEmpty()) {
            targets.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Application autorisée introuvable : $pkg", e)
                }
            }
            return
        }

        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Impossible d'exclure le paquet du tunnel", e)
        }
        if (!settings.splitInclude) {
            targets.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Application contournée introuvable : $pkg", e)
                }
            }
        }
    }

    private fun startTun2Socks(
        descriptor: ParcelFileDescriptor,
        settings: VpnSettings,
        user: String,
        pass: String
    ): Boolean {
        val config = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${settings.mtu}")
            appendLine("  ipv4: $CLIENT_IPV4")
            // hev doit connaître l'adresse v6 source dès lors que le TUN porte
            // ::/0 (sinon il rejette les paquets au lieu de les remettre à Xray).
            appendLine("  ipv6: '$CLIENT_IPV6'")
            appendLine("socks5:")
            appendLine("  port: ${settings.socksPort}")
            appendLine("  address: 127.0.0.1")
            // hev ne connaît que deux modes de relais UDP : 'udp' (standard
            // SOCKS5) et 'tcp' (encapsulation propriétaire, réservée au
            // hev-socks5-SERVER : Xray ne l'interprète pas et tout l'UDP --
            // DNS inclus -- mourait). 'udp' est donc le seul mode utilisable ;
            // si l'UDP est désactivé, c'est l'inbound Xray (udp=false) qui
            // refuse les associations, par flux, sans toucher au TCP.
            appendLine("  udp: 'udp'")
            if (!settings.socksUdp) {
                Log.i(TAG, "UDP SOCKS désactivé : les associations UDP seront refusées par Xray")
            }
            appendLine("  username: '${yamlEscape(user)}'")
            appendLine("  password: '${yamlEscape(pass)}'")
            appendLine("misc:")
            appendLine("  log-level: ${
                when (settings.logLevel) {
                    "debug" -> "debug"
                    "info" -> "info"
                    else -> "warn"
                }
            }")
            appendLine("  tcp-buffer-size: ${settings.tcpBufferKb * 1024}")
            appendLine("  task-stack-size: ${settings.tcpBufferKb * 1024 + 20480}")
            appendLine("  udp-recv-buffer-size: ${settings.udpBufferKb * 1024}")
        }

        return try {
            val file = File(filesDir, "hev-socks5-tunnel.yaml")
            file.writeText(config)
            val started = TProxyService.TProxyStartService(file.absolutePath, descriptor.fd)
            if (!started) Log.e(TAG, "TProxyStartService a renvoyé false")
            started
        } catch (t: Throwable) {
            Log.e(TAG, "tun2socks", t)
            false
        }
    }

    // ------------------------------------------------------- Stats & réseau

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope?.launch {
            var lastUpload = 0L
            var lastDownload = 0L
            var uploadTotal = 0L
            var downloadTotal = 0L
            var lastTick = SystemClock.elapsedRealtime()

            while (isActive && running) {
                delay(STATS_INTERVAL_MS)
                if (!running) break

                val counters = XrayCore.traffic()
                uploadTotal += counters.uplink
                downloadTotal += counters.downlink

                val now = SystemClock.elapsedRealtime()
                val seconds = ((now - lastTick).coerceAtLeast(1L)) / 1000.0
                val uploadRate = ((uploadTotal - lastUpload) / seconds).toLong().coerceAtLeast(0L)
                val downloadRate = ((downloadTotal - lastDownload) / seconds).toLong().coerceAtLeast(0L)

                lastUpload = uploadTotal
                lastDownload = downloadTotal
                lastTick = now

                VpnController.traffic(uploadTotal, downloadTotal, uploadRate, downloadRate)
                TunnelDiagnostics.update {
                    copy(proxyBytesUp = uploadTotal, proxyBytesDown = downloadTotal)
                }
                updateNotification(
                    getString(
                        R.string.notification_traffic,
                        formatBytes(downloadRate),
                        formatBytes(uploadRate)
                    )
                )
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                bindUnderlying(network)
                reportUnderlying(manager.getNetworkCapabilities(network))
                // Ignore l'événement déclenché juste après l'enregistrement.
                if (SystemClock.elapsedRealtime() - connectedAt < RECONNECT_MIN_UPTIME_MS) return
                scheduleReconnect("réseau disponible")
            }

            override fun onLost(network: Network) {
                if (SystemClock.elapsedRealtime() - connectedAt < RECONNECT_MIN_UPTIME_MS) return
                scheduleReconnect("réseau perdu")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                reportUnderlying(capabilities)
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    scheduleReconnect("réseau sans Internet")
                } else {
                    bindUnderlying(network)
                }
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure {
                Log.w(TAG, "Suivi réseau indisponible", it)
                networkCallback = null
            }
    }

    /** Diagnostic : nom du réseau sous-jacent observé (aucune supposition). */
    private fun reportUnderlying(capabilities: NetworkCapabilities?) {
        val names = mutableListOf<String>()
        if (capabilities != null) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) names.add("Wi-Fi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) names.add("Cellulaire")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) names.add("Ethernet")
            if (names.isEmpty()) names.add("autre")
        }
        TunnelDiagnostics.update { copy(underlying = names.joinToString(" + ")) }
    }

    /** Lie les sockets du tunnel au réseau courant (Wi-Fi ou 4G). */
    private fun bindUnderlying(network: Network) {
        runCatching { setUnderlyingNetworks(arrayOf(network)) }
            .onFailure { Log.w(TAG, "setUnderlyingNetworks refusé", it) }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    /**
     * Après un changement de réseau (Wi-Fi ↔ 4G), les sockets du cœur sont
     * souvent mortes : on relance le tunnel avec un backoff exponentiel
     * (1, 2, 4, 8… 30 s), 8 tentatives maximum. Un échec ne coupe plus le
     * service : la boucle réessaie. Le compteur repart à zéro après 60 s
     * de connexion stable.
     */
    private fun scheduleReconnect(reason: String) {
        if (!running || reconnecting) return
        if (!SettingsStore.current().autoReconnect) return
        val profileId = currentProfileId ?: return
        reconnecting = true
        scope?.launch {
            try {
                while (!stopRequested) {
                    backoffAttempt++
                    if (backoffAttempt > RECONNECT_MAX_ATTEMPTS) {
                        fail(getString(R.string.error_reconnect_failed))
                        return@launch
                    }
                    VpnController.reconnectAttempt()
                    VpnController.connecting(currentProfileName)
                    updateNotification(getString(R.string.status_reconnecting))
                    Log.i(TAG, "Reconnexion #$backoffAttempt ($reason)")
                    stopPipeline()

                    val ok = runCatching { startVpn(profileId, retrying = true) }
                        .getOrDefault(false)
                    if (ok) {
                        Log.i(TAG, "Reconnexion réussie (#$backoffAttempt)")
                        return@launch
                    }

                    val wait = backoffDelayMs(backoffAttempt)
                    Log.w(TAG, "Échec, nouvelle tentative dans ${wait / 1000} s")
                    delay(wait)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Reconnexion impossible", t)
                fail(t.message ?: getString(R.string.error_tun))
            } finally {
                reconnecting = false
            }
        }
    }

    private fun backoffDelayMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        return (1_000L shl shift).coerceAtMost(RECONNECT_MAX_DELAY_MS)
    }

    // ------------------------------------------------------- Watchdog santé

    /**
     * Toutes les 20 s : le cœur tourne-t-il, et le tunnel transporte-t-il
     * vraiment du trafic ? La sonde passe par le proxy SOCKS local (donc par
     * Xray et le serveur) : un simple `isRunning` ne suffit pas, le cœur peut
     * être vivant avec des sockets mortes après un changement de réseau.
     * Deux échecs consécutifs déclenchent une reconnexion.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogFailures = 0
        watchdogJob = scope?.launch {
            while (!stopRequested) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!running) continue

                renewWakeLock()
                if (SystemClock.elapsedRealtime() - connectedAt >= STABLE_RESET_MS) {
                    backoffAttempt = 0
                }

                val core = XrayCore.isRunning()
                val outcome = if (core) probeTunnel() else null
                if (outcome != null) {
                    TunnelDiagnostics.update {
                        copy(
                            coreRunning = true,
                            socksPortOpen = outcome.portOpen,
                            probe = outcome.phase,
                            probeAt = System.currentTimeMillis()
                        )
                    }
                } else {
                    TunnelDiagnostics.update { copy(coreRunning = false) }
                }
                val healthy = outcome?.phase == TunnelDiagnostics.Probe.OK
                if (healthy) {
                    watchdogFailures = 0
                    continue
                }

                watchdogFailures++
                Log.w(
                    TAG,
                    "Watchdog : tunnel non réactif ($watchdogFailures/$WATCHDOG_MAX_FAILURES)"
                )
                if (watchdogFailures >= WATCHDOG_MAX_FAILURES) {
                    watchdogFailures = 0
                    scheduleReconnect("watchdog")
                }
            }
        }
    }

    /**
     * Vérification réelle du tunnel : au plus VERIFY_ATTEMPTS sondes espacées
     * de VERIFY_RETRY_DELAY_MS. Renvoie la cause la plus précise possible en
     * cas d'échec (cœur arrêté, port local fermé, négociation, relais, HTTP).
     *
     * Le DNS est couvert : le CONNECT est envoyé par domaine, le cœur doit le
     * résoudre lui-même (réponse SOCKS uniquement après résolution + relais).
     */
    private suspend fun verifyTunnel(): Verification {
        var last: ProbeOutcome? = null
        repeat(VERIFY_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(VERIFY_RETRY_DELAY_MS)
            if (stopRequested || !running) {
                return Verification(false, getString(R.string.tunnel_cause_aborted))
            }
            val outcome = probeTunnel()
            last = outcome
            TunnelDiagnostics.update {
                copy(
                    coreRunning = XrayCore.isRunning(),
                    socksPortOpen = outcome.portOpen,
                    probe = outcome.phase,
                    probeAt = System.currentTimeMillis()
                )
            }
            if (outcome.phase == TunnelDiagnostics.Probe.OK) return Verification(true, "")
        }
        if (!XrayCore.isRunning()) return Verification(false, getString(R.string.tunnel_cause_core))
        return when (last?.phase) {
            TunnelDiagnostics.Probe.PORT_CLOSED ->
                Verification(false, getString(R.string.tunnel_cause_port))
            TunnelDiagnostics.Probe.AUTH ->
                Verification(false, getString(R.string.tunnel_cause_auth))
            TunnelDiagnostics.Probe.RELAY ->
                Verification(false, getString(R.string.tunnel_cause_relay))
            TunnelDiagnostics.Probe.HTTP ->
                Verification(false, getString(R.string.tunnel_cause_http))
            else -> Verification(false, getString(R.string.tunnel_cause_timeout))
        }
    }

    private data class Verification(val ok: Boolean, val cause: String)

    private data class ProbeOutcome(
        val portOpen: Boolean,
        val phase: TunnelDiagnostics.Probe
    )

    /**
     * Requête HTTP réelle à travers le SOCKS local (auth incluse), sans passer
     * par le réseau de l'application : preuve que TUN → hev → Xray → serveur
     * fonctionne. Renvoie la phase la plus avancée atteinte (204 attendu).
     */
    private fun probeTunnel(): ProbeOutcome = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", activeSocksPort), PROBE_TIMEOUT_MS)
            socket.soTimeout = PROBE_TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            // Négociation : sans auth + utilisateur/mot de passe.
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            out.flush()
            val greeting = ByteArray(2)
            if (!readFully(input, greeting) || greeting[0].toInt() != 0x05) {
                return@use ProbeOutcome(true, TunnelDiagnostics.Probe.AUTH)
            }

            when (greeting[1].toInt()) {
                0x02 -> {
                    val user = activeSocksUser.toByteArray()
                    val pass = activeSocksPass.toByteArray()
                    out.write(
                        byteArrayOf(0x01, user.size.toByte(), *user, pass.size.toByte(), *pass)
                    )
                    out.flush()
                    val auth = ByteArray(2)
                    if (!readFully(input, auth) || auth[1].toInt() != 0x00) {
                        return@use ProbeOutcome(true, TunnelDiagnostics.Probe.AUTH)
                    }
                }
                0x00 -> Unit
                else -> return@use ProbeOutcome(true, TunnelDiagnostics.Probe.AUTH)
            }

            // CONNECT connectivitycheck.gstatic.com:80 (résolution par le proxy).
            val host = "connectivitycheck.gstatic.com"
            val hostBytes = host.toByteArray()
            out.write(
                byteArrayOf(
                    0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte(), *hostBytes, 0x00, 0x50
                )
            )
            out.flush()

            val reply = ByteArray(4)
            if (!readFully(input, reply) || reply[1].toInt() != 0x00) {
                return@use ProbeOutcome(true, TunnelDiagnostics.Probe.RELAY)
            }

            val atyp = reply[3].toInt() and 0xFF
            val addrLen = when (atyp) {
                0x01 -> 4
                0x03 -> {
                    val length = input.read()
                    if (length < 0) return@use ProbeOutcome(true, TunnelDiagnostics.Probe.RELAY)
                    length
                }
                0x04 -> 16
                else -> return@use ProbeOutcome(true, TunnelDiagnostics.Probe.RELAY)
            }
            if (!readFully(input, ByteArray(addrLen + 2))) {
                return@use ProbeOutcome(true, TunnelDiagnostics.Probe.RELAY)
            }

            val request = "GET /generate_204 HTTP/1.1\r\nHost: $host\r\n" +
                "Connection: close\r\n\r\n"
            out.write(request.toByteArray())
            out.flush()

            val status = input.bufferedReader().readLine()
                ?: return@use ProbeOutcome(true, TunnelDiagnostics.Probe.HTTP)
            if (status.startsWith("HTTP/1.") || status.startsWith("HTTP/2")) {
                ProbeOutcome(true, TunnelDiagnostics.Probe.OK)
            } else {
                ProbeOutcome(true, TunnelDiagnostics.Probe.HTTP)
            }
        }
    } catch (t: Throwable) {
        Log.d(TAG, "Sonde tunnel : ${t.message}")
        ProbeOutcome(false, TunnelDiagnostics.Probe.PORT_CLOSED)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) return false
            read += count
        }
        return true
    }

    // ---------------------------------------------------------------- Arrêt

    private fun stopVpn() {
        stopRequested = true
        SettingsStore.setWasConnected(false)
        backoffAttempt = 0
        VpnController.stopping()
        cleanup()
        VpnController.stopped()
        stopForegroundCompat()
        stopSelf()
    }

    /** Arrête seulement le tunnel (Xray + tun2socks + TUN), sans le service. */
    private fun stopPipeline() {
        running = false
        statsJob?.cancel()
        statsJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        watchdogFailures = 0
        runCatching { TProxyService.TProxyStopService() }
        XrayCore.stop()
        closeTun()
        TunnelDiagnostics.update {
            copy(
                coreRunning = false,
                hevRunning = false,
                socksPortOpen = null,
                probe = TunnelDiagnostics.Probe.UNKNOWN,
                probeAt = 0L,
                proxyBytesUp = 0L,
                proxyBytesDown = 0L
            )
        }
    }

    private fun cleanup() {
        stopPipeline()
        unregisterNetworkCallback()
        releaseWakeLock()
    }

    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
    }

    private fun fail(message: String) {
        stopRequested = true
        SettingsStore.setWasConnected(false)
        backoffAttempt = 0
        Log.e(TAG, message)
        VpnController.error(message)
        cleanup()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ---------------------------------------------------------- Veille/repos

    private fun acquireWakeLock(settings: VpnSettings) {
        if (!settings.wakeLock) return
        val manager = getSystemService(PowerManager::class.java) ?: return
        val lock = wakeLock ?: manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "dems:vpn"
        ).apply { setReferenceCounted(false) }
        wakeLock = lock
        renewWakeLock()
    }

    /**
     * Le WakeLock est volontairement borné dans le temps et renouvelé par le
     * watchdog : s'il n'est plus rafraîchi (processus figé), Android le rend.
     */
    private fun renewWakeLock() {
        val lock = wakeLock ?: return
        runCatching {
            if (lock.isHeld) lock.release()
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }.onFailure { Log.w(TAG, "WakeLock refusé", it) }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        if (lock.isHeld) runCatching { lock.release() }
    }

    // -------------------------------------------------------- Notifications

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = getString(R.string.tagline)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DemsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dems)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSubText(currentProfileName.takeIf { it.isNotBlank() })
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_power, getString(R.string.notification_disconnect), stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ---------------------------------------------------------------- Outils

    private fun yamlEscape(value: String): String = value.replace("'", "''")

    private fun formatBytes(bytesPerSecond: Long): String {
        val value = bytesPerSecond.coerceAtLeast(0L).toDouble()
        val units = arrayOf("o", "Ko", "Mo", "Go")
        var index = 0
        var scaled = value
        while (scaled >= 1024 && index < units.lastIndex) {
            scaled /= 1024
            index++
        }
        return if (index == 0) {
            "${scaled.toInt()} ${units[index]}/s"
        } else {
            "%.1f %s/s".format(scaled, units[index])
        }
    }
}
