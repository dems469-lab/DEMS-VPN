package com.dems.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dems.vpn.service.DemsVpnService
import com.dems.vpn.service.VpnController
import com.dems.vpn.ui.DemsVpnApp
import com.dems.vpn.ui.MainViewModel
import com.dems.vpn.ui.theme.DemsVpnTheme

class MainActivity : ComponentActivity() {

    private var pendingProfileId: String? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val profileId = pendingProfileId
        pendingProfileId = null
        if (result.resultCode == Activity.RESULT_OK && profileId != null) {
            DemsVpnService.start(this, profileId)
        } else {
            VpnController.error(getString(R.string.error_vpn_permission))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermission()

        setContent {
            DemsVpnTheme {
                val viewModel: MainViewModel = viewModel()
                DemsVpnApp(
                    viewModel = viewModel,
                    onConnect = { profile -> connect(profile.id) },
                    onDisconnect = { DemsVpnService.stop(this) }
                )
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun connect(profileId: String) {
        val prepareIntent: Intent? = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingProfileId = profileId
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            DemsVpnService.start(this, profileId)
        }
    }
}
