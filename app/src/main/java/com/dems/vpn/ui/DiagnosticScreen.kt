package com.dems.vpn.ui

import android.util.Base64
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dems.vpn.R
import com.dems.vpn.data.Profile
import com.dems.vpn.service.VpnState
import com.dems.vpn.service.VpnStatus
import kotlinx.coroutines.delay

/**
 * Diagnostic de session, sans journalisation de contenu :
 * état, temps de connexion, latence mesurée par le cœur, débit moyen sur 10 s,
 * nombre de reconnexions et taille réelle de la clé XUDP.
 */
@Composable
fun DiagnosticScreen(
    status: VpnStatus,
    profile: Profile?,
    latency: Long?,
    deviceKey: String,
    coreVersion: String,
    appVersion: String,
    speedSample: SpeedSample?,
    speedTesting: Boolean,
    onTestLatency: () -> Unit,
    onMeasureSpeed: () -> Unit,
    onBack: () -> Unit
) {
    var elapsed by remember { mutableStateOf(0L) }

    LaunchedEffect(status.state, status.since) {
        if (status.state == VpnState.CONNECTED) {
            while (true) {
                elapsed = ((System.currentTimeMillis() - status.since) / 1000L).coerceAtLeast(0L)
                delay(1_000)
            }
        } else {
            elapsed = 0L
        }
    }

    val keyBytes = remember(deviceKey) {
        runCatching {
            Base64.decode(
                deviceKey,
                Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
            ).size
        }.getOrDefault(-1)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.diag_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionTitle(stringResource(R.string.diag_session))
            DiagnosticCard {
                InfoRow(stringResource(R.string.diag_state), stateLabel(status))
                Divider()
                InfoRow(
                    stringResource(R.string.diag_server),
                    profile?.name ?: stringResource(R.string.diag_empty)
                )
                Divider()
                InfoRow(
                    stringResource(R.string.diag_uptime),
                    if (status.state == VpnState.CONNECTED) formatDuration(elapsed)
                    else stringResource(R.string.diag_empty)
                )
                Divider()
                InfoRow(
                    stringResource(R.string.diag_reconnects),
                    status.reconnects.toString()
                )
                Divider()
                InfoRow(
                    stringResource(R.string.diag_core),
                    coreVersion
                )
                Divider()
                InfoRow(
                    stringResource(R.string.diag_app),
                    appVersion
                )
            }

            SectionTitle(stringResource(R.string.diag_measures))
            DiagnosticCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.diag_latency),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = latencyLabel(latency),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = onTestLatency,
                            enabled = profile != null && latency != -2L
                        ) {
                            Text(stringResource(R.string.diag_latency_test))
                        }
                    }
                }
                Divider()
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.diag_speed),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = when {
                                    speedTesting -> stringResource(R.string.diag_speed_running)
                                    speedSample != null -> stringResource(
                                        R.string.diag_speed_result,
                                        formatRate(speedSample.downBps),
                                        formatRate(speedSample.upBps),
                                        speedSample.seconds
                                    )
                                    else -> stringResource(R.string.diag_speed_hint)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = onMeasureSpeed,
                            enabled = !speedTesting
                        ) {
                            Text(stringResource(R.string.diag_speed_test))
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.diag_key))
            DiagnosticCard {
                InfoRow(
                    stringResource(R.string.diag_key_size),
                    if (keyBytes == 32) stringResource(R.string.diag_key_ok, keyBytes)
                    else stringResource(R.string.diag_key_bad, keyBytes)
                )
            }

            Text(
                text = stringResource(R.string.diag_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun stateLabel(status: VpnStatus): String = when (status.state) {
    VpnState.CONNECTED -> stringResource(R.string.status_connected)
    VpnState.CONNECTING -> stringResource(R.string.status_connecting)
    VpnState.STOPPING -> stringResource(R.string.status_stopping)
    VpnState.ERROR -> stringResource(R.string.status_error)
    VpnState.STOPPED -> stringResource(R.string.status_disconnected)
}

@Composable
private fun latencyLabel(latency: Long?): String = when {
    latency == null -> stringResource(R.string.diag_not_measured)
    latency == -2L -> stringResource(R.string.latency_testing)
    latency < 0 -> stringResource(R.string.latency_failed)
    else -> stringResource(R.string.latency_ms, latency)
}

@Composable
private fun DiagnosticCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}

private fun formatRate(bytesPerSecond: Long): String {
    val units = arrayOf("o", "Ko", "Mo", "Go", "To")
    var value = bytesPerSecond.coerceAtLeast(0L).toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    val text = if (index == 0) "${value.toInt()} ${units[index]}"
    else "%.1f %s".format(value, units[index])
    return "$text/s"
}
