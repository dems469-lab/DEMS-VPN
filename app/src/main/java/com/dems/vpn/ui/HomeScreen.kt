package com.dems.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dems.vpn.R
import com.dems.vpn.data.Profile
import com.dems.vpn.service.VpnState
import com.dems.vpn.service.VpnStatus
import com.dems.vpn.ui.theme.BrandCyan
import com.dems.vpn.ui.theme.BrandIndigo
import com.dems.vpn.ui.theme.BrandMint
import com.dems.vpn.ui.theme.BrandViolet
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    status: VpnStatus,
    server: Profile?,
    coreVersion: String,
    latency: Long?,
    searching: Boolean,
    onToggle: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val connected = status.state == VpnState.CONNECTED
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        BrandHeader(onOpenSettings)
        Spacer(Modifier.height(22.dp))
        ConnectCard(status, server, elapsed, connected, searching, onToggle)
        Spacer(Modifier.height(16.dp))
        ServerCard(server, latency, onOpenServers)
        if (server == null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenServers, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_server))
            }
        }
        Spacer(Modifier.height(12.dp))
        InfoCard(coreVersion)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BrandHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(listOf(BrandIndigo, BrandViolet, BrandCyan))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_stat_dems),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
        }
    }
}

@Composable
private fun ConnectCard(
    status: VpnStatus,
    server: Profile?,
    elapsed: Long,
    connected: Boolean,
    searching: Boolean,
    onToggle: () -> Unit
) {
    val busy = status.state == VpnState.CONNECTING || status.state == VpnState.STOPPING
    val statusLabel = stringResource(
        when (status.state) {
            VpnState.CONNECTED -> R.string.status_connected
            VpnState.CONNECTING -> R.string.status_connecting
            VpnState.STOPPING -> R.string.status_stopping
            VpnState.ERROR -> R.string.status_error
            VpnState.STOPPED -> R.string.status_disconnected
        }
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.linearGradient(listOf(BrandIndigo, BrandViolet, BrandCyan))
            )
            .padding(horizontal = 22.dp, vertical = 26.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (connected) BrandMint else Color.White.copy(alpha = 0.65f))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusLabel,
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(Modifier.height(22.dp))

            Box(
                modifier = Modifier
                    .size(148.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .clip(CircleShape)
                        .background(
                            if (connected || busy) Color.White
                            else Color.White.copy(alpha = 0.22f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(38.dp),
                            color = BrandIndigo,
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_power),
                            contentDescription = null,
                            tint = if (connected) BrandIndigo else Color.White,
                            modifier = Modifier.size(46.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = server?.name ?: stringResource(R.string.no_server),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    connected -> formatDuration(elapsed)
                    searching -> stringResource(R.string.auto_bench_searching)
                    status.state == VpnState.CONNECTING -> stringResource(R.string.status_connecting)
                    status.state == VpnState.STOPPING -> stringResource(R.string.status_stopping)
                    else -> stringResource(R.string.hint_tap_connect)
                },
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodySmall
            )

            if (connected) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TrafficValue(
                        label = stringResource(R.string.label_download),
                        rate = formatRate(status.downloadRate),
                        total = formatBytes(status.downloadTotal)
                    )
                    TrafficValue(
                        label = stringResource(R.string.label_upload),
                        rate = formatRate(status.uploadRate),
                        total = formatBytes(status.uploadTotal)
                    )
                }
            }

            if (status.state == VpnState.ERROR && !status.message.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = status.message.orEmpty(),
                    color = Color(0xFFFFD9D9),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ServerCard(server: Profile?, latency: Long?, onOpenServers: () -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenServers)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (server != null) {
                ProtocolBadge(server.protocol)
            } else {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.label_server),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = server?.name ?: stringResource(R.string.no_server),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (server != null) {
                    // -2L = test en cours (sentinelle MainViewModel.LATENCY_RUNNING)
                    val latencyLabel = if (latency == null) {
                        server.endpoint
                    } else if (latency == -2L) {
                        server.endpoint + " · " + stringResource(R.string.latency_testing)
                    } else if (latency < 0) {
                        server.endpoint + " · " + stringResource(R.string.latency_failed)
                    } else {
                        server.endpoint + " · " + stringResource(R.string.latency_ms, latency)
                    }
                    Text(
                        text = latencyLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoCard(coreVersion: String) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InfoRow(stringResource(R.string.core_label), coreVersion)
            InfoRow(stringResource(R.string.label_mode), stringResource(R.string.mode_value))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
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

@Composable
private fun TrafficValue(label: String, rate: String, total: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = rate,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = total,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun formatBytes(bytes: Long): String {
    val units = arrayOf("o", "Ko", "Mo", "Go", "To")
    var value = bytes.coerceAtLeast(0L).toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) {
        "${value.toInt()} ${units[index]}"
    } else {
        "%.1f %s".format(value, units[index])
    }
}

private fun formatRate(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"
