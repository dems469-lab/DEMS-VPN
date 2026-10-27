package com.dems.vpn

import android.app.Application
import com.dems.vpn.core.XrayCore
import com.dems.vpn.data.ProfileStore
import com.dems.vpn.data.SettingsStore

class DemsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProfileStore.init(this)
        SettingsStore.init(this)
        XrayCore.init(this)
    }
}
