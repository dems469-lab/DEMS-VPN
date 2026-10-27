package com.dems.vpn.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dems.vpn.R
import com.dems.vpn.data.Profile
import com.dems.vpn.data.SettingsStore
import com.dems.vpn.service.TunnelDiagnostics
import com.dems.vpn.service.VpnState
import kotlinx.coroutines.launch

private const val ROUTE_HOME = "home"
private const val ROUTE_SERVERS = "servers"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DIAGNOSTIC = "diagnostic"

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
    val latencies by viewModel.latencies.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val speedSample by viewModel.speedSample.collectAsStateWithLifecycle()
    val speedTesting by viewModel.speedTesting.collectAsStateWithLifecycle()
    val deviceKey by viewModel.deviceKey.collectAsStateWithLifecycle()
    val tunnelDiag by TunnelDiagnostics.state.collectAsStateWithLifecycle()

    var unlocked by rememberSaveable { mutableStateOf(false) }
    val pinSet = settings.appLockPin.isNotBlank()

    LaunchedEffect(pinSet) {
        if (!pinSet) unlocked = true
    }

    // Verrouillage automatique après un passage en arrière-plan (> 30 s), pour
    // ne pas gêner la boîte de dialogue de permission VPN qui dure quelques secondes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, pinSet) {
        if (!pinSet) return@DisposableEffect onDispose { }
        var stoppedAt = 0L
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> stoppedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    if (stoppedAt != 0L &&
                        SystemClock.elapsedRealtime() - stoppedAt > 30_000L
                    ) {
                        unlocked = false
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (pinSet && !unlocked) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            LockScreen(
                onVerify = { pin -> SettingsStore.verifyPin(pin) },
                onUnlocked = { unlocked = true }
            )
        }
        return
    }

    val noServerMessage = stringResource(R.string.error_no_server)
    val deletedMessage = stringResource(R.string.delete_ok)

    val notify: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    // Exclusion batterie : proposée une seule fois, à la première connexion.
    val context = LocalContext.current
    var pendingConnect by remember { mutableStateOf<Profile?>(null) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    val proceedConnect: () -> Unit = {
        val profile = pendingConnect
        pendingConnect = null
        showBatteryDialog = false
        SettingsStore.markBatteryPromptShown()
        if (profile != null) onConnect(profile)
    }

    val beginConnect: (Profile) -> Unit = { profile ->
        val power = context.getSystemService(PowerManager::class.java)
        val ignoring = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        if (!ignoring && !SettingsStore.batteryPromptShown()) {
            pendingConnect = profile
            showBatteryDialog = true
        } else {
            onConnect(profile)
        }
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
                    latencies = latencies,
                    onSelect = viewModel::select,
                    onDelete = { profile ->
                        viewModel.delete(profile)
                        notify(deletedMessage)
                    },
                    onTest = viewModel::testLatency,
                    onImport = viewModel::importLink,
                    onMessage = notify,
                    onBack = { route = ROUTE_HOME }
                )

                ROUTE_SETTINGS -> SettingsScreen(
                    settings = settings,
                    coreVersion = viewModel.coreVersion(),
                    appVersion = viewModel.appVersion(),
                    deviceKey = deviceKey,
                    onChange = viewModel::updateSettings,
                    onSetPin = viewModel::setPin,
                    onClearPin = viewModel::clearPin,
                    onRegenerateKey = viewModel::regenerateDeviceKey,
                    onMessage = notify,
                    onOpenDiagnostic = { route = ROUTE_DIAGNOSTIC },
                    onBack = { route = ROUTE_HOME }
                )

                ROUTE_DIAGNOSTIC -> DiagnosticScreen(
                    status = status,
                    tunnel = tunnelDiag,
                    profile = selected,
                    latency = selected?.let { latencies[it.id] },
                    deviceKey = deviceKey,
                    coreVersion = viewModel.coreVersion(),
                    appVersion = viewModel.appVersion(),
                    speedSample = speedSample,
                    speedTesting = speedTesting,
                    onTestLatency = { selected?.let(viewModel::testLatency) },
                    onMeasureSpeed = viewModel::measureSpeed10s,
                    onBack = { route = ROUTE_SETTINGS }
                )

                else -> HomeScreen(
                    status = status,
                    server = selected,
                    coreVersion = viewModel.coreVersion(),
                    latency = selected?.let { latencies[it.id] },
                    searching = searching,
                    onToggle = {
                        when (status.state) {
                            VpnState.CONNECTED,
                            VpnState.CONNECTING,
                            VpnState.VERIFYING,
                            VpnState.STOPPING -> onDisconnect()

                            else -> {
                                val current = selected
                                when {
                                    current == null -> notify(noServerMessage)
                                    searching -> viewModel.cancelSearch(beginConnect)
                                    else -> viewModel.connectSmart(beginConnect)
                                }
                            }
                        }
                    },
                    onOpenServers = { route = ROUTE_SERVERS },
                    onOpenSettings = { route = ROUTE_SETTINGS }
                )
            }
        }
    }

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = proceedConnect,
            title = { Text(stringResource(R.string.battery_title)) },
            text = { Text(stringResource(R.string.battery_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching {
                            batteryLauncher.launch(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:" + context.packageName)
                                )
                            )
                        }
                        proceedConnect()
                    }
                ) {
                    Text(stringResource(R.string.battery_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = proceedConnect) {
                    Text(stringResource(R.string.battery_later))
                }
            }
        )
    }

    BackHandler(enabled = route != ROUTE_HOME) {
        route = if (route == ROUTE_DIAGNOSTIC) ROUTE_SETTINGS else ROUTE_HOME
    }
}
