package com.dems.vpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dems.vpn.R
import com.dems.vpn.data.Profile
import com.dems.vpn.service.VpnState
import kotlinx.coroutines.launch

private const val ROUTE_HOME = "home"
private const val ROUTE_SERVERS = "servers"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun DemsVpnApp(
    viewModel: MainViewModel,
    onConnect: (Profile) -> Unit,
    onDisconnect: () -> Unit
) {
    var route by rememberSaveable { mutableStateOf(ROUTE_HOME) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val noServerMessage = stringResource(R.string.error_no_server)
    val deletedMessage = stringResource(R.string.delete_ok)

    val notify: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (route) {
                ROUTE_SERVERS -> ServersScreen(
                    profiles = profiles,
                    selectedId = selected?.id,
                    onSelect = viewModel::select,
                    onDelete = { profile ->
                        viewModel.delete(profile)
                        notify(deletedMessage)
                    },
                    onImport = viewModel::importLink,
                    onMessage = notify,
                    onBack = { route = ROUTE_HOME }
                )

                ROUTE_SETTINGS -> SettingsScreen(
                    settings = settings,
                    coreVersion = viewModel.coreVersion(),
                    appVersion = viewModel.appVersion(),
                    onChange = viewModel::updateSettings,
                    onBack = { route = ROUTE_HOME }
                )

                else -> HomeScreen(
                    status = status,
                    server = selected,
                    coreVersion = viewModel.coreVersion(),
                    onToggle = {
                        when (status.state) {
                            VpnState.CONNECTED,
                            VpnState.CONNECTING,
                            VpnState.STOPPING -> onDisconnect()

                            else -> {
                                val current = selected
                                if (current == null) notify(noServerMessage) else onConnect(current)
                            }
                        }
                    },
                    onOpenServers = { route = ROUTE_SERVERS },
                    onOpenSettings = { route = ROUTE_SETTINGS }
                )
            }
        }
    }

    BackHandler(enabled = route != ROUTE_HOME) {
        route = ROUTE_HOME
    }
}
