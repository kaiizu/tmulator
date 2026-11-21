package dev.tmulator.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tmulator.data.AppLogEntry
import dev.tmulator.data.RuntimeLogLevel
import dev.tmulator.data.RuntimeState
import dev.tmulator.util.ShareUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogFilter { ALL, WARN, ERROR }

@Composable
fun DebugScreen(
    runtime: RuntimeState,
    onTest: () -> Unit,
    onSnackbar: (String) -> Unit,
    clearLogs: () -> Unit,
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    val filtered = remember(runtime.logs, filter) {
        when (filter) {
            LogFilter.ALL -> runtime.logs
            LogFilter.WARN -> runtime.logs.filter { it.level == RuntimeLogLevel.WARN || it.level == RuntimeLogLevel.ERROR }
            LogFilter.ERROR -> runtime.logs.filter { it.level == RuntimeLogLevel.ERROR }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Diagnostics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Connections, commands, status and errors",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onTest) {
                Icon(Icons.Rounded.Print, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Test")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionCard(title = "Live state") {
                    KeyValueRow("Foreground service", if (runtime.serviceRunning) "Running" else "Stopped", valueColor = if (runtime.serviceRunning) StatusGreen else StatusGrey)
                    KeyValueRow(
                        "Network",
                        when {
                            !runtime.network.enabled -> "Disabled"
                            runtime.network.status == dev.tmulator.data.ListenerStatus.ERROR -> "Error"
                            else -> "${runtime.network.status.name.lowercase().replaceFirstChar { it.uppercase() }} · TCP ${runtime.network.port}"
                        },
                        valueColor = networkColor(runtime.network),
                    )
                    KeyValueRow("Network clients", runtime.network.clients.toString())
                    KeyValueRow("Last client", runtime.network.lastClient ?: "—", mono = true)
                    KeyValueRow(
                        "Epson discovery",
                        when (runtime.network.discoveryStatus) {
                            dev.tmulator.data.ListenerStatus.LISTENING -> "Listening · UDP 3289"
                            dev.tmulator.data.ListenerStatus.ERROR -> "Error"
                            else -> if (runtime.network.enabled) "Off" else "Disabled"
                        },
                        valueColor = when (runtime.network.discoveryStatus) {
                            dev.tmulator.data.ListenerStatus.LISTENING -> StatusGreen
                            dev.tmulator.data.ListenerStatus.ERROR -> StatusRed
                            else -> StatusGrey
                        },
                    )
                    KeyValueRow("Bluetooth", bluetoothStatusText(runtime.bluetooth), valueColor = bluetoothColor(runtime.bluetooth))
                    KeyValueRow("Bluetooth name", runtime.bluetooth.deviceName ?: "—")
                    KeyValueRow("Local IP", runtime.localIps.joinToString(", ").ifBlank { "No Wi-Fi IPv4 address" }, mono = true)
                    DividerLine()
                    val job = runtime.activeJob
                    KeyValueRow("Active job", job?.let { "${it.source.name} · ${it.remote}" } ?: "None", mono = job != null)
                    KeyValueRow("Received", job?.let { formatBytes(it.bytes) } ?: "—")
                    KeyValueRow("Commands", job?.commands?.toString() ?: "—")
                    KeyValueRow("Unsupported", job?.unsupported?.toString() ?: "—", valueColor = if ((job?.unsupported ?: 0) > 0) StatusAmber else MaterialTheme.colorScheme.onSurface)
                    KeyValueRow("Rendered blocks", job?.blocks?.toString() ?: "—")
                    KeyValueRow("Cut", if (job?.hasCut == true) "Yes" else "No")
                    if (runtime.network.status == dev.tmulator.data.ListenerStatus.ERROR) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            runtime.network.message.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (runtime.bluetooth.status == dev.tmulator.data.ListenerStatus.ERROR) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            runtime.bluetooth.message.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(selected = filter == LogFilter.ALL, onClick = { filter = LogFilter.ALL }, label = { Text("All") })
                    FilterChip(selected = filter == LogFilter.WARN, onClick = { filter = LogFilter.WARN }, label = { Text("Warnings") })
                    FilterChip(selected = filter == LogFilter.ERROR, onClick = { filter = LogFilter.ERROR }, label = { Text("Errors") })
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        if (runtime.logs.isEmpty()) return@TextButton
                        val text = runtime.logs.joinToString("\n") { entry ->
                            "${logTime(entry.time)} [${entry.source}] ${entry.level.name}: ${entry.message}${entry.detail?.let { " · $it" } ?: ""}"
                        }
                        ShareUtils.copyText(context, "TMulator log", text)
                        onSnackbar("Log copied")
                    }) {
                        Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }
                    TextButton(onClick = clearLogs) {
                        Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(15.dp))
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No log entries yet. Send a job to see connection and command activity.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(filtered, key = { it.id }) { entry ->
                    LogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: AppLogEntry) {
    val color = logColor(entry.level)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .size(7.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        logTime(entry.time),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(7.dp))
                    MonthChip(entry.source, color)
                    Spacer(Modifier.weight(1f))
                    Text(
                        entry.level.name,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
                        color = color,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                if (!entry.detail.isNullOrBlank()) {
                    Text(
                        entry.detail,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(5.dp), color = color.copy(alpha = 0.1f)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
            color = color,
        )
    }
}

private fun logTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(millis))
