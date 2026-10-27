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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dems.vpn.R
import com.dems.vpn.ui.theme.BrandCyan
import com.dems.vpn.data.Profile
import com.dems.vpn.data.SettingsStore
import com.dems.vpn.service.TunnelDiagnostics
import com.dems.vpn.service.VpnState
import kotlinx.coroutines.launch

private const val ROUTE_HOME = "home"
private const val ROUTE_SERVERS = "servers"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DIAGNOSTIC = "diagnostic"

/** Destinations de la navigation basse (et du tiroir). « Journal » pointe
 * vers l'écran Diagnostic existant : aucun historique de journal n'existe. */
private data class NavDest(val route: String, val icon: ImageVector, val labelRes: Int)

private val NAV_DESTS = listOf(
    NavDest(ROUTE_HOME, Icons.Filled.Home, R.string.nav_home),
    NavDest(ROUTE_SERVERS, Icons.Filled.Place, R.string.nav_servers),
    NavDest(ROUTE_DIAGNOSTIC, Icons.Filled.List, R.string.nav_journal),
    NavDest(ROUTE_SETTINGS, Icons.Filled.Settings, R.string.nav_settings)
)

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

    // Contenu central inchangé : navigation haute/basse + tiroir branchés
    // autour des écrans existants.
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(16.dp))
                NAV_DESTS.forEach { dest ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(dest.labelRes)) },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        selected = route == dest.route,
                        onClick = {
                            route = dest.route
                            scope.launch { drawerState.close() }
                        }
                    )
                }
            }
        }
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (route == ROUTE_HOME) AppTopBar { scope.launch { drawerState.open() } }
        },
        bottomBar = { AppBottomNav(route) { dest -> route = dest } }
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
                    onOpenServers = { route = ROUTE_SERVERS }
                )
            }
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

/** Barre de haut (accueil) : menu à gauche, bouclier + titre centrés. */
@Composable
private fun AppTopBar(onMenu: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onMenu) {
            Icon(
                Icons.Filled.Menu,
                contentDescription = stringResource(R.string.nav_menu_open)
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_logo_shield),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.size(48.dp))
    }
}

/** Barre de navigation basse : onglet actif cyan avec soulignement. */
@Composable
private fun AppBottomNav(selected: String, onSelect: (String) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NAV_DESTS.forEach { dest ->
            val active = selected == dest.route
            NavigationBarItem(
                selected = active,
                onClick = { onSelect(dest.route) },
                icon = {
                    Icon(
                        dest.icon,
                        contentDescription = stringResource(dest.labelRes)
                    )
                },
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(dest.labelRes),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                        Box(
                            Modifier
                                .padding(top = 3.dp)
                                .width(if (active) 26.dp else 0.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (active) BrandCyan else Color.Transparent)
                        )
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = BrandCyan,
                    selectedTextColor = BrandCyan,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}
