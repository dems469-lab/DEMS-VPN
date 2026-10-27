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
        private const val RECONNECT_DEBOUNCE_MS = 1_500L
        private const val RECONNECT_MIN_UPTIME_MS = 5_000L

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

    private fun startVpn(profileId: String) {
        val profile = ProfileStore.find(profileId)
        if (profile == null) {
            fail(getString(R.string.error_profile))
            return
        }
        val settings = SettingsStore.current()

        if (VpnService.prepare(this) != null) {
            fail(getString(R.string.error_vpn_permission))
            return
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

        if (settings.ipv6) {
            builder.addAddress(CLIENT_IPV6, 126)
            builder.addRoute("::", 0)
            builder.addDnsServer(DNS_IPV6)
        }

        applySplitTunneling(builder, settings)

        val descriptor = try {
            builder.establish()
        } catch (t: Throwable) {
            Log.e(TAG, "establish()", t)
            null
        }
        if (descriptor == null) {
            fail(getString(R.string.error_tun))
            return
        }
        tun = descriptor

        if (stopRequested) {
            cleanup()
            return
        }

        acquireWakeLock(settings)

        val startResult = XrayCore.start(this, config)
        if (startResult.isFailure) {
            fail("Xray : ${startResult.exceptionOrNull()?.message ?: "échec du démarrage"}")
            return
        }

        if (stopRequested) {
            cleanup()
            return
        }

        if (!startTun2Socks(descriptor, settings, socksUser, socksPass)) {
            fail(getString(R.string.error_tun2socks))
            return
        }

        if (stopRequested) {
            cleanup()
            return
        }

        running = true
        connectedAt = SystemClock.elapsedRealtime()
        currentProfileId = profileId
        currentProfileName = profile.name
        VpnController.connected(profile.name)
        updateNotification(getString(R.string.notification_connected, profile.name))

        registerNetworkCallback()
        startStatsLoop()
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
            if (settings.ipv6) appendLine("  ipv6: '$CLIENT_IPV6'")
            appendLine("socks5:")
            appendLine("  port: ${settings.socksPort}")
            appendLine("  address: 127.0.0.1")
            // hev n'accepte que 'udp' ou 'tcp'. Quand l'UDP est désactivé,
            // c'est l'inbound Xray (udp=false) qui refuse les associations.
            appendLine("  udp: 'udp'")
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
                // Ignore l'événement déclenché juste après l'enregistrement.
                if (SystemClock.elapsedRealtime() - connectedAt < RECONNECT_MIN_UPTIME_MS) return
                scheduleReconnect("réseau disponible")
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure {
                Log.w(TAG, "Suivi réseau indisponible", it)
                networkCallback = null
            }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    /**
     * Après un changement de réseau (Wi-Fi ↔ 4G), les sockets du cœur sont
     * souvent mortes : on relance proprement tout le tunnel.
     */
    private fun scheduleReconnect(reason: String) {
        if (!running || reconnecting) return
        if (!SettingsStore.current().autoReconnect) return
        val profileId = currentProfileId ?: return
        reconnecting = true
        scope?.launch {
            try {
                delay(RECONNECT_DEBOUNCE_MS)
                if (!running) return@launch
                Log.i(TAG, "Reconnexion ($reason)")
                VpnController.reconnectAttempt()
                VpnController.connecting(currentProfileName)
                stopPipeline()
                startVpn(profileId)
            } catch (t: Throwable) {
                Log.e(TAG, "Reconnexion impossible", t)
                fail(t.message ?: getString(R.string.error_tun))
            } finally {
                reconnecting = false
            }
        }
    }

    // ---------------------------------------------------------------- Arrêt

    private fun stopVpn() {
        stopRequested = true
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
        runCatching { TProxyService.TProxyStopService() }
        XrayCore.stop()
        closeTun()
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
        if (!lock.isHeld) {
            runCatching { lock.acquire() }
                .onFailure { Log.w(TAG, "WakeLock refusé", it) }
        }
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
