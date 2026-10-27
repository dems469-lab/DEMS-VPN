package com.dems.vpn.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Stockage local des serveurs (SharedPreferences + JSON).
 * Volontairement simple : pas de base de données pour une base de projet.
 */
object ProfileStore {

    private const val PREFS = "dems_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_SELECTED = "selected_id"

    private lateinit var prefs: SharedPreferences

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _profiles.value = readProfiles()
        val stored = prefs.getString(KEY_SELECTED, null)
        _selectedId.value = stored?.takeIf { id -> _profiles.value.any { it.id == id } }
            ?: _profiles.value.firstOrNull()?.id
    }

    fun find(id: String?): Profile? = _profiles.value.firstOrNull { it.id == id }

    fun isDuplicate(profile: Profile): Boolean {
        if (profile.rawUri.isBlank()) return false
        return _profiles.value.any { it.rawUri == profile.rawUri }
    }

    fun add(profile: Profile) {
        val updated = _profiles.value + profile
        _profiles.value = updated
        if (_selectedId.value == null) {
            _selectedId.value = profile.id
        }
        persist()
    }

    fun remove(id: String) {
        _profiles.value = _profiles.value.filterNot { it.id == id }
        if (_selectedId.value == id) {
            _selectedId.value = _profiles.value.firstOrNull()?.id
        }
        persist()
    }

    fun select(id: String) {
        if (_profiles.value.none { it.id == id }) return
        _selectedId.value = id
        persist()
    }

    private fun readProfiles(): List<Profile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val list = mutableListOf<Profile>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val profile = runCatching { Profile.fromJson(item) }.getOrNull() ?: continue
                if (profile.server.isNotBlank() && profile.port in 1..65535) {
                    list.add(profile)
                }
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        val array = JSONArray()
        _profiles.value.forEach { array.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_PROFILES, array.toString())
            .putString(KEY_SELECTED, _selectedId.value)
            .apply()
    }
}
