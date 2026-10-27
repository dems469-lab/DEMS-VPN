package com.dems.vpn.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

object SettingsStore {

    private const val PREFS = "dems_settings"
    private lateinit var prefs: SharedPreferences

    private val _settings = MutableStateFlow(VpnSettings())
    val settings: StateFlow<VpnSettings> = _settings.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = VpnSettings(
            dns = prefs.getString("dns", "1.1.1.1")?.ifBlank { "1.1.1.1" } ?: "1.1.1.1",
            fakeDns = prefs.getBoolean("fake_dns", false),
            mtu = prefs.getInt("mtu", 1500).coerceIn(1280, 9000),
            ipv6 = prefs.getBoolean("ipv6", false),
            blockQuic = prefs.getBoolean("blockQuic", false),
            sniffing = prefs.getBoolean("sniffing", true),
            routeOnly = prefs.getBoolean("route_only", false),
            bypassApps = prefs.getStringSet("bypass_apps", emptySet())?.toSet() ?: emptySet(),
            splitInclude = prefs.getBoolean("split_include", false),
            finalMask = prefs.getBoolean("final_mask", false),
            allowLan = prefs.getBoolean("allow_lan", false),
            autoReconnect = prefs.getBoolean("auto_reconnect", true),
            wakeLock = prefs.getBoolean("wake_lock", true),
            tcpBufferKb = prefs.getInt("tcp_buffer_kb", 64).coerceIn(8, 1024),
            udpBufferKb = prefs.getInt("udp_buffer_kb", 512).coerceIn(64, 8192),
            socksPort = prefs.getInt("socks_port", 10808).coerceIn(1024, 65535),
            httpPort = prefs.getInt("http_port", 10809).coerceIn(1024, 65535),
            socksUdp = prefs.getBoolean("socks_udp", true),
            logLevel = prefs.getString("log_level", "warning") ?: "warning",
            tlsMinVersion = prefs.getString("tls_min", "auto") ?: "auto",
            appLockPin = prefs.getString("app_lock_pin", "") ?: "",
            mux = prefs.getBoolean("mux", false),
            testUrl = prefs.getString("test_url", "https://www.gstatic.com/generate_204")
                ?: "https://www.gstatic.com/generate_204",
            autoBench = prefs.getBoolean("auto_bench", false)
        )
    }

    fun current(): VpnSettings = _settings.value

    fun update(settings: VpnSettings) {
        val socksPort = settings.socksPort.coerceIn(1024, 65535)
        val httpPort = settings.httpPort.coerceIn(1024, 65535).let { port ->
            if (port != socksPort) port
            else if (socksPort < 65535) socksPort + 1 else socksPort - 1
        }
        val sanitized = settings.copy(
            dns = settings.dns.trim(),
            mtu = settings.mtu.coerceIn(1280, 9000),
            socksPort = socksPort,
            httpPort = httpPort,
            logLevel = settings.logLevel.takeIf { it in LOG_LEVELS } ?: "warning",
            tlsMinVersion = settings.tlsMinVersion.takeIf { it in TLS_VERSIONS } ?: "auto",
            testUrl = settings.effectiveTestUrl,
            tcpBufferKb = settings.tcpBufferKb.coerceIn(8, 1024),
            udpBufferKb = settings.udpBufferKb.coerceIn(64, 8192)
        )
        _settings.value = sanitized
        prefs.edit()
            .putString("dns", sanitized.dns)
            .putBoolean("fake_dns", sanitized.fakeDns)
            .putInt("mtu", sanitized.mtu)
            .putBoolean("ipv6", sanitized.ipv6)
            .putBoolean("blockQuic", sanitized.blockQuic)
            .putBoolean("sniffing", sanitized.sniffing)
            .putBoolean("route_only", sanitized.routeOnly)
            .putStringSet("bypass_apps", HashSet(sanitized.bypassApps))
            .putBoolean("split_include", sanitized.splitInclude)
            .putBoolean("final_mask", sanitized.finalMask)
            .putBoolean("allow_lan", sanitized.allowLan)
            .putBoolean("auto_reconnect", sanitized.autoReconnect)
            .putBoolean("wake_lock", sanitized.wakeLock)
            .putInt("tcp_buffer_kb", sanitized.tcpBufferKb)
            .putInt("udp_buffer_kb", sanitized.udpBufferKb)
            .putInt("socks_port", sanitized.socksPort)
            .putInt("http_port", sanitized.httpPort)
            .putBoolean("socks_udp", sanitized.socksUdp)
            .putString("log_level", sanitized.logLevel)
            .putString("tls_min", sanitized.tlsMinVersion)
            .putString("app_lock_pin", sanitized.appLockPin)
            .putBoolean("mux", sanitized.mux)
            .putString("test_url", sanitized.testUrl)
            .putBoolean("auto_bench", sanitized.autoBench)
            .apply()
    }

    // ------------------------------------------------------- Clé XUDP / ID

    /**
     * Clé XUDP stable du cœur Xray. Le cœur décode cette valeur en base64 et
     * exige **32 octets** : on encode donc 32 octets aléatoires, pas 32
     * caractères hexadécimaux.
     */
    fun deviceKey(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString("xudp_key", null)
        if (!existing.isNullOrBlank() && decodedLength(existing) == 32) return existing
        val generated = newDeviceKey()
        prefs.edit().putString("xudp_key", generated).apply()
        return generated
    }

    fun regenerateDeviceKey(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val generated = newDeviceKey()
        prefs.edit().putString("xudp_key", generated).apply()
        return generated
    }

    private fun newDeviceKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
        )
    }

    private fun decodedLength(value: String): Int = runCatching {
        Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING).size
    }.getOrDefault(-1)

    // --------------------------------------------------- État de session

    /**
     * Vrai si l'utilisateur était connecté au moment de la mort du processus.
     * Sert à START_STICKY : ne pas ressusciter un VPN que l'utilisateur avait
     * arrêté, mais reprendre une session interrompue.
     */
    fun wasConnected(): Boolean = prefs.getBoolean("was_connected", false)

    fun setWasConnected(value: Boolean) {
        prefs.edit().putBoolean("was_connected", value).apply()
    }

    /** L'écran d'exclusion batterie n'est proposé qu'une seule fois. */
    fun batteryPromptShown(): Boolean = prefs.getBoolean("battery_prompt_shown", false)

    fun markBatteryPromptShown() {
        prefs.edit().putBoolean("battery_prompt_shown", true).apply()
    }

    // ------------------------------------------------------------ Code PIN

    fun setPin(pin: String) {
        update(_settings.value.copy(appLockPin = hashPin(pin)))
    }

    fun clearPin() {
        update(_settings.value.copy(appLockPin = ""))
    }

    fun verifyPin(pin: String): Boolean {
        val stored = _settings.value.appLockPin
        return stored.isNotBlank() && stored == hashPin(pin)
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("dems-vpn-lock".toByteArray(Charsets.UTF_8))
        val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Utilisé pour le sel de session (SOCKS) : identifiant court et aléatoire. */
    fun sessionToken(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }
}
