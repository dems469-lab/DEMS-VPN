package com.dems.vpn.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

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
            mtu = prefs.getInt("mtu", 1500).coerceIn(1280, 9000),
            ipv6 = prefs.getBoolean("ipv6", false),
            blockQuic = prefs.getBoolean("blockQuic", false)
        )
    }

    fun current(): VpnSettings = _settings.value

    fun update(settings: VpnSettings) {
        val sanitized = settings.copy(
            dns = settings.dns.trim().ifBlank { "1.1.1.1" },
            mtu = settings.mtu.coerceIn(1280, 9000)
        )
        _settings.value = sanitized
        prefs.edit()
            .putString("dns", sanitized.dns)
            .putInt("mtu", sanitized.mtu)
            .putBoolean("ipv6", sanitized.ipv6)
            .putBoolean("blockQuic", sanitized.blockQuic)
            .apply()
    }

    /** Clé XUDP stable pour le cœur Xray. */
    fun deviceKey(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString("xudp_key", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString("xudp_key", generated).apply()
        return generated
    }
}
