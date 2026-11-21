package dev.tmulator.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tmulator.data.ListenerStatus
import dev.tmulator.data.PrinterSettings
import dev.tmulator.data.RuntimeState
import dev.tmulator.util.ShareUtils

@Composable
fun HomeScreen(
    settings: PrinterSettings, runtime: RuntimeState, jobCount: Int,
    onClear: () -> Unit, onTest: () -> Unit, onShare: () -> Unit,
    onOpenJobs: () -> Unit, onOpenSettings: () -> Unit,
    onRestartNetwork: () -> Unit, onSnackbar: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 820.dp) {
            Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    NetworkHero(runtime, onRestartNetwork, onOpenSettings, onSnackbar)
                    QuickStats(runtime, jobCount, onOpenSettings, onOpenJobs)
                    BottomActions(settings, runtime, onClear, onTest, onShare)
                }
                PrinterScene(runtime, settings.autoScroll, onTest, settings.networkEnabled, onClear,
                    Modifier.weight(1f).fillMaxHeight().widthIn(max = 640.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(8.dp))
                NetworkHero(runtime, onRestartNetwork, onOpenSettings, onSnackbar)
                Spacer(Modifier.height(8.dp))
                QuickStats(runtime, jobCount, onOpenSettings, onOpenJobs)
                Spacer(Modifier.height(8.dp))
                PrinterScene(runtime, settings.autoScroll, onTest, settings.networkEnabled, onClear,
                    Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                BottomActions(settings, runtime, onClear, onTest, onShare)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun NetworkHero(runtime: RuntimeState, onRestart: () -> Unit, onSettings: () -> Unit, onSnackbar: (String) -> Unit) {
    val network = runtime.network
    val address = runtime.localIps.firstOrNull()
    val context = LocalContext.current
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Wifi, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Network printer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                StatusDot(networkColor(network), size = 6)
                Spacer(Modifier.width(6.dp))
                Text(networkStatusText(network), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when {
                    !network.enabled -> "Network is off"
                    address == null -> "Waiting for Wi-Fi"
                    else -> "$address:${network.port}"
                }, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    if (address != null) {
                        ShareUtils.copyText(context, "Printer address", "$address:${network.port}")
                        onSnackbar("Address copied")
                    }
                }, enabled = address != null && network.enabled, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.ContentCopy, "Copy printer address", Modifier.size(18.dp))
                }
            }
            if (!network.enabled || network.status == ListenerStatus.ERROR) {
                TextButton(onClick = if (!network.enabled) onSettings else onRestart,
                    contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                    Text(if (!network.enabled) "Open settings" else "Retry connection")
                }
                network.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            } else {
                Text(if (network.clients > 0) "Connected from ${network.lastClient ?: "POS"}"
                    else "Ready for your POS · ${if (network.discoveryStatus == ListenerStatus.LISTENING) "Epson discovery on" else "Raw ESC/POS"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QuickStats(runtime: RuntimeState, jobs: Int, onSettings: () -> Unit, onJobs: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).clickable(onClick = onSettings).padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bluetooth, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("Bluetooth", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(bluetoothStatusText(runtime.bluetooth), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.clickable(onClick = onJobs).padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.History, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("$jobs saved jobs", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BottomActions(settings: PrinterSettings, runtime: RuntimeState, onClear: () -> Unit, onTest: () -> Unit, onShare: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onClear, enabled = runtime.hasReceipt, modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Clear")
        }
        Button(onClick = onTest, enabled = settings.networkEnabled, modifier = Modifier.weight(1.3f).height(48.dp),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(Icons.Rounded.Print, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Test print")
        }
        OutlinedButton(onClick = onShare, enabled = runtime.hasReceipt, modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Share")
        }
    }
}
