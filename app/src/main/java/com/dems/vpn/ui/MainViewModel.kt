package com.dems.vpn.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dems.vpn.BuildConfig
import com.dems.vpn.core.XrayCore
import com.dems.vpn.data.Profile
import com.dems.vpn.data.ProfileParser
import com.dems.vpn.data.ProfileStore
import com.dems.vpn.data.SettingsStore
import com.dems.vpn.data.VpnSettings
import com.dems.vpn.service.VpnController
import com.dems.vpn.service.VpnStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class ImportResult { OK, INVALID, DUPLICATE }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val profiles: StateFlow<List<Profile>> = ProfileStore.profiles
    val settings: StateFlow<VpnSettings> = SettingsStore.settings
    val status: StateFlow<VpnStatus> = VpnController.status

    val selected: StateFlow<Profile?> =
        combine(ProfileStore.profiles, ProfileStore.selectedId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val coreVersion: String by lazy { XrayCore.version() }

    fun coreVersion(): String = coreVersion

    fun appVersion(): String = BuildConfig.VERSION_NAME

    fun importLink(text: String): ImportResult {
        val profile = ProfileParser.parse(text) ?: return ImportResult.INVALID
        if (ProfileStore.isDuplicate(profile)) return ImportResult.DUPLICATE
        ProfileStore.add(profile)
        return ImportResult.OK
    }

    fun delete(profile: Profile) {
        ProfileStore.remove(profile.id)
    }

    fun select(profile: Profile) {
        ProfileStore.select(profile.id)
    }

    fun updateSettings(settings: VpnSettings) {
        SettingsStore.update(settings)
    }
}
