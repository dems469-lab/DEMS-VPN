package com.dems.vpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dems.vpn.R
import com.dems.vpn.data.DNS_PRESETS
import com.dems.vpn.data.LOG_LEVELS
import com.dems.vpn.data.MTU_PRESETS
import com.dems.vpn.data.TCP_BUFFER_PRESETS
import com.dems.vpn.data.TLS_VERSIONS
import com.dems.vpn.data.UDP_BUFFER_PRESETS
import com.dems.vpn.data.VpnSettings

@Composable
fun SettingsScreen(
    settings: VpnSettings,
    coreVersion: String,
    appVersion: String,
    deviceKey: String,
    onChange: (VpnSettings) -> Unit,
    onSetPin: (String) -> Unit,
    onClearPin: () -> Unit,
    onRegenerateKey: () -> Unit,
    onMessage: (String) -> Unit,
    onOpenDiagnostic: () -> Unit,
    onBack: () -> Unit
) {
    var dns by remember { mutableStateOf(settings.dns) }
    var mtu by remember { mutableStateOf(settings.mtu.toString()) }
    var socksPort by remember { mutableStateOf(settings.socksPort.toString()) }
    var httpPort by remember { mutableStateOf(settings.httpPort.toString()) }
    var testUrl by remember { mutableStateOf(settings.testUrl) }

    var showApps by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    var showRegenerate by remember { mutableStateOf(false) }

    val pinSaved = stringResource(R.string.settings_pin_saved)
    val pinRemoved = stringResource(R.string.settings_pin_removed)
    val bypassNone = stringResource(R.string.settings_bypass_none)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.settings_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ------------------------------------------------------ Réseau
            SectionTitle(stringResource(R.string.settings_network))
            SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = dns,
                        onValueChange = { value ->
                            dns = value.take(128)
                            onChange(settings.copy(dns = dns))
                        },
                        label = { Text(stringResource(R.string.settings_dns)) },
                        placeholder = { Text(stringResource(R.string.settings_dns_hint)) },
                        supportingText = { Text(stringResource(R.string.settings_dns_help)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_dns_presets),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    ChipRow(
                        options = DNS_PRESETS,
                        selected = settings.dns,
                        label = { it },
                        onSelect = { value ->
                            dns = value
                            onChange(settings.copy(dns = value))
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = mtu,
                        onValueChange = { value ->
                            mtu = value.filter { it.isDigit() }.take(4)
                            mtu.toIntOrNull()?.takeIf { it in 1280..9000 }
                                ?.let { onChange(settings.copy(mtu = it)) }
                        },
                        label = { Text(stringResource(R.string.settings_mtu)) },
                        placeholder = { Text(stringResource(R.string.settings_mtu_hint)) },
                        supportingText = { Text(stringResource(R.string.settings_mtu_desc)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    ChipRow(
                        options = MTU_PRESETS.map { it.toString() },
                        selected = mtu,
                        label = { it },
                        onSelect = { value ->
                            mtu = value
                            value.toIntOrNull()?.let { onChange(settings.copy(mtu = it)) }
                        }
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                SwitchRow(
                    title = stringResource(R.string.settings_fake_dns),
                    description = stringResource(R.string.settings_fake_dns_desc),
                    checked = settings.fakeDns,
                    onCheckedChange = { onChange(settings.copy(fakeDns = it)) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                SwitchRow(
                    title = stringResource(R.string.settings_ipv6),
                    description = stringResource(R.string.settings_ipv6_desc),
                    checked = settings.ipv6,
                    onCheckedChange = { onChange(settings.copy(ipv6 = it)) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                SwitchRow(
                    title = stringResource(R.string.settings_quic),
                    description = stringResource(R.string.settings_quic_desc),
                    checked = settings.blockQuic,
                    onCheckedChange = { onChange(settings.copy(blockQuic = it)) }
                )
            }

            // ----------------------------------------------------- Routage
            SectionTitle(stringResource(R.string.settings_routing))
            SettingsCard {
                SwitchRow(
                    title = stringResource(R.string.settings_sniffing),
                    description = stringResource(R.string.settings_sniffing_desc),
                    checked = settings.sniffing,
                    onCheckedChange = { onChange(settings.copy(sniffing = it)) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                SwitchRow(
                    title = stringResource(R.string.settings_route_only),
                    description = stringResource(R.string.settings_route_only_desc),
                    checked = settings.routeOnly,
                    onCheckedChange = { onChange(settings.copy(routeOnly = it)) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_split_direction),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    // Les libellés sont résolus ici : ChipRow.label n'est pas @Composable.
                    val excludeLabel = stringResource(R.string.settings_split_exclude)
                    val includeLabel = stringResource(R.string.settings_split_include)
                    ChipRow(
                        options = listOf("exclude", "include"),
                        selected = if (settings.splitInclude) "include" else "exclude",
                        label = { mode -> if (mode == "include") includeLabel else excludeLabel },
                        onSelect = { mode -> onChange(settings.copy(splitInclude = mode == "include")) }
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showApps = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (settings.splitInclude) {
                                stringResource(R.string.settings_bypass_title_include)
                            } else {
                                stringResource(R.string.settings_bypass_title_exclude)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.settings_bypass_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (settings.bypassApps.isEmpty()) bypassNone
                            else stringResource(
                                R.string.settings_bypass_count,
                                settings.bypassApps.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.settings_choose_apps),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                SwitchRow(
                    title = stringResource(R.string.settings_lan),
                    description = stringResource(R.string.settings_lan_desc),
                    checked = settings.allowLan,
                    onCheckedChange = { onChange(settings.copy(allowLan = it)) }
                )
                if (settings.allowLan) {
                    Text(
                        text = stringResource(R.string.settings_lan_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                    )
                }
            }

            // ------------------------------------------- Anti-censure
            SectionTitle(stringResource(R.string.settings_stealth))
            SettingsCard {
                SwitchRow(
                    title = stringResource(R.string.settings_final_mask),
                    description = stringResource(R.string.settings_final_mask_desc),
                    checked = settings.finalMask,
                    onCheckedChange = { onChange(settings.copy(finalMask = it)) }
                )
            }

            // ------------------------------------ Moteur de décision
            SectionTitle(stringResource(R.string.settings_engine))
            SettingsCard {
                SwitchRow(
                    title = stringResource(R.string.settings_auto_bench),
                    description = stringResource(R.string.settings_auto_bench_desc),
                    checked = settings.autoBench,
                    onCheckedChange = { onChange(settings.copy(autoBench = it)) }
                )
            }

            // ---------------------------------------------- Vitesse
            SectionTitle(stringResource(R.string.settings_speed))
            SettingsCard {
                SwitchRow(
                    title = stringResource(R.string.settings_mux),
                    description = stringResource(R.string.settings_mux_desc),
                    checked = settings.mux,
                    onCheckedChange = { onChange(settings.copy(mux = it)) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_buffer_preset),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    val stableSelected = settings.tcpBufferKb == 32 && settings.udpBufferKb == 256
                    val fastSelected = settings.tcpBufferKb == 128 && settings.udpBufferKb == 1024
                    val currentPreset = when {
                        stableSelected -> "stable"
                        fastSelected -> "fast"
                        else -> "custom"
                    }
                    val stableLabel = stringResource(R.string.settings_buffer_stable)
                    val fastLabel = stringResource(R.string.settings_buffer_fast)
                    ChipRow(
                        options = listOf("stable", "fast"),
                        selected = currentPreset,
                        label = { mode -> if (mode == "stable") stableLabel else fastLabel },
                        onSelect = { mode ->
                            if (mode == "stable") {
                                onChange(settings.copy(tcpBufferKb = 32, udpBufferKb = 256))
                            } else {
                                onChange(settings.copy(tcpBufferKb = 128, udpBufferKb = 1024))
                            }
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_buffer_preset_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ------------------------------------------- Stabilité
            SectionTitle(stringResource(R.string.settings_stability))
            SettingsCard {
                SwitchRow(
                    title = stringResource(R.string.settings_reconnect),
                    description = stringResource(R.string.settings_reconnect_desc),
                    checked = settings.autoReconnect,
                    onCheckedChange = { onChange(settings.copy(autoReconnect = it)) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                SwitchRow(
                    title = stringResource(R.string.settings_wakelock),
                    description = stringResource(R.string.settings_wakelock_desc),
                    checked = settings.wakeLock,
                    onCheckedChange = { onChange(settings.copy(wakeLock = it)) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_tcp_buffer),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(6.dp))
                    ChipRow(
                        options = TCP_BUFFER_PRESETS.map { it.toString() },
                        selected = settings.tcpBufferKb.toString(),
                        label = { it },
                        onSelect = { value ->
                            value.toIntOrNull()?.let { onChange(settings.copy(tcpBufferKb = it)) }
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.settings_udp_buffer),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(6.dp))
                    ChipRow(
                        options = UDP_BUFFER_PRESETS.map { it.toString() },
                        selected = settings.udpBufferKb.toString(),
                        label = { it },
                        onSelect = { value ->
                            value.toIntOrNull()?.let { onChange(settings.copy(udpBufferKb = it)) }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_buffer_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ------------------------------------------------------- Ports
            SectionTitle(stringResource(R.string.settings_ports))
            SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = socksPort,
                            onValueChange = { value ->
                                socksPort = value.filter { it.isDigit() }.take(5)
                                socksPort.toIntOrNull()?.takeIf { it in 1024..65535 }
                                    ?.let { onChange(settings.copy(socksPort = it)) }
                            },
                            label = { Text(stringResource(R.string.settings_socks_port)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = httpPort,
                            onValueChange = { value ->
                                httpPort = value.filter { it.isDigit() }.take(5)
                                httpPort.toIntOrNull()?.takeIf { it in 1024..65535 }
                                    ?.let { onChange(settings.copy(httpPort = it)) }
                            },
                            label = { Text(stringResource(R.string.settings_http_port)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                SwitchRow(
                    title = stringResource(R.string.settings_http_proxy),
                    description = stringResource(R.string.settings_http_proxy_desc),
                    checked = settings.httpProxyEnabled,
                    onCheckedChange = { onChange(settings.copy(httpProxyEnabled = it)) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                SwitchRow(
                    title = stringResource(R.string.settings_socks_udp),
                    description = stringResource(R.string.settings_socks_udp_desc),
                    checked = settings.socksUdp,
                    onCheckedChange = { onChange(settings.copy(socksUdp = it)) }
                )
                Text(
                    text = stringResource(R.string.settings_ports_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                )
            }

            // ---------------------------------------------------- Sécurité
            SectionTitle(stringResource(R.string.settings_security))
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPin = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_app_lock),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.settings_app_lock_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (settings.appLockPin.isBlank()) {
                            stringResource(R.string.settings_app_lock_disabled)
                        } else {
                            stringResource(R.string.settings_app_lock_enabled)
                        },
                        color = if (settings.appLockPin.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_tls),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.settings_tls_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    ChipRow(
                        options = TLS_VERSIONS,
                        selected = settings.tlsMinVersion,
                        label = { it },
                        onSelect = { onChange(settings.copy(tlsMinVersion = it)) }
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_device_id),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.settings_device_id_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = deviceKey,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(
                        onClick = { showRegenerate = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text(stringResource(R.string.settings_regenerate))
                    }
                }
            }

            // ------------------------------------------------- Diagnostics
            SectionTitle(stringResource(R.string.settings_diagnostics))
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenDiagnostic() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_open_diagnostic),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.settings_open_diagnostic_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "›",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor())
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = testUrl,
                        onValueChange = { value ->
                            testUrl = value.take(200)
                            onChange(settings.copy(testUrl = testUrl))
                        },
                        label = { Text(stringResource(R.string.settings_test_url)) },
                        placeholder = { Text(stringResource(R.string.settings_test_url_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.settings_log_level),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(6.dp))
                    ChipRow(
                        options = LOG_LEVELS,
                        selected = settings.logLevel,
                        label = { it },
                        onSelect = { onChange(settings.copy(logLevel = it)) }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_log_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------------------------------------------------- À propos
            SectionTitle(stringResource(R.string.settings_about))
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AboutRow(stringResource(R.string.settings_version), appVersion)
                    AboutRow(stringResource(R.string.settings_core), coreVersion)
                    AboutRow(
                        stringResource(R.string.settings_license),
                        stringResource(R.string.settings_license_value)
                    )
                }
            }

            Text(
                text = stringResource(R.string.settings_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showApps) {
        AppPickerDialog(
            selected = settings.bypassApps,
            onDismiss = { showApps = false },
            onConfirm = { apps -> onChange(settings.copy(bypassApps = apps)) }
        )
    }

    if (showPin) {
        PinDialog(
            hasPin = settings.appLockPin.isNotBlank(),
            onDismiss = { showPin = false },
            onSave = { pin ->
                onSetPin(pin)
                onMessage(pinSaved)
                showPin = false
            },
            onRemove = {
                onClearPin()
                onMessage(pinRemoved)
                showPin = false
            }
        )
    }

    if (showRegenerate) {
        AlertDialog(
            onDismissRequest = { showRegenerate = false },
            title = { Text(stringResource(R.string.settings_regenerate_title)) },
            text = { Text(stringResource(R.string.settings_regenerate_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    onRegenerateKey()
                    showRegenerate = false
                }) {
                    Text(stringResource(R.string.settings_regenerate))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerate = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
private fun ChipRow(
    options: List<String>,
    selected: String,
    label: (String) -> String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) }
            )
        }
    }
}

@Composable
private fun dividerColor() = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PinDialog(
    hasPin: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val errorMessage = stringResource(R.string.settings_pin_error)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter { c -> c.isDigit() }.take(8)
                        error = null
                    },
                    label = { Text(stringResource(R.string.settings_pin_new)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = {
                        confirm = it.filter { c -> c.isDigit() }.take(8)
                        error = null
                    },
                    label = { Text(stringResource(R.string.settings_pin_confirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pin.length in 4..8 && pin == confirm) {
                    onSave(pin)
                } else {
                    error = "invalid"
                }
            }) {
                Text(stringResource(R.string.settings_pin_set))
            }
        },
        dismissButton = {
            Row {
                if (hasPin) {
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.settings_pin_remove))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}
