package com.dems.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Base64
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

/**
 * Service VPN : crée l'interface TUN, démarre Xray (SOCKS local) puis
 * hev-socks5-tunnel qui relie le TUN au SOCKS.
 */
class DemsVpnService : VpnService() {

    companion object {
        private const val TAG = "DemsVpnService"

        const val ACTION_START = "com.dems.vpn.action.START"
        const val ACTION_STOP = "com.dems.vpn.action.STOP"
        const val EXTRA_PROFILE_ID = "com.dems.vpn.extra.PROFILE_ID"

        private const val CHANNEL_ID = "dems_vpn_status"
        private const val NOTIFICATION_ID = 1001

        private const val SOCKS_PORT = 10808
        private const val CLIENT_IPV4 = "10.10.10.2"
        private const val CLIENT_IPV6 = "fd00::2"
        private const val DNS_IPV6 = "2606:4700:4700::1111"

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
    private var scope: CoroutineScope? = null

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
        if (running || starting) return START_STICKY

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
        VpnController.connecting(profile.name)
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

        val socksUser = randomToken()
        val socksPass = randomToken()
        val config = XrayConfigBuilder.build(profile, settings, SOCKS_PORT, socksUser, socksPass)

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

        // Le processus de l'application (donc Xray) ne doit pas repasser dans le tunnel.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Impossible d'exclure le paquet du tunnel", e)
        }

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

        val startResult = XrayCore.start(this, config)
        if (startResult.isFailure) {
            cleanup()
            fail("Xray : ${startResult.exceptionOrNull()?.message ?: "échec du démarrage"}")
            return
        }

        if (stopRequested) {
            cleanup()
            return
        }

        if (!startTun2Socks(descriptor, settings, socksUser, socksPass)) {
            cleanup()
            fail(getString(R.string.error_tun2socks))
            return
        }

        if (stopRequested) {
            cleanup()
            return
        }

        running = true
        VpnController.connected(profile.name)
        updateNotification(getString(R.string.notification_connected, profile.name))
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
            appendLine("  port: $SOCKS_PORT")
            appendLine("  address: 127.0.0.1")
            appendLine("  udp: 'udp'")
            appendLine("  username: '${yamlEscape(user)}'")
            appendLine("  password: '${yamlEscape(pass)}'")
            appendLine("misc:")
            appendLine("  log-level: warn")
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

    // ---------------------------------------------------------------- Arrêt

    private fun stopVpn() {
        stopRequested = true
        VpnController.stopping()
        cleanup()
        VpnController.stopped()
        stopForegroundCompat()
        stopSelf()
    }

    private fun cleanup() {
        running = false
        runCatching { TProxyService.TProxyStopService() }
        XrayCore.stop()
        closeTun()
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

    private fun randomToken(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    private fun yamlEscape(value: String): String = value.replace("'", "''")
}
