package dev.tmulator.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.tmulator.BuildConfig
import dev.tmulator.data.ListenerStatus
import dev.tmulator.data.PrinterSettings
import dev.tmulator.data.RuntimeState
import dev.tmulator.escpos.PrinterProfile
import kotlinx.coroutines.delay
import java.util.Locale

private enum class BtPending { ENABLE, DISCOVER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: PrinterSettings,
    runtime: RuntimeState,
    viewModel: PrinterViewModel,
    onSnackbar: (String) -> Unit,
) {
    val context = LocalContext.current
    val bluetoothManager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager }
    val adapter = remember { bluetoothManager?.adapter }
    var pendingAction by remember { mutableStateOf<BtPending?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val seconds = result.resultCode
        pendingAction = null
        if (seconds > 0) {
            val until = System.currentTimeMillis() + seconds * 1000L
            viewModel.setDiscoverableUntil(until)
            onSnackbar("Discoverable for ${seconds / 60} min ${seconds % 60} sec")
        } else {
            onSnackbar("Discoverable mode was cancelled")
        }
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val action = pendingAction
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.setBluetoothEnabled(true)
            if (action == BtPending.DISCOVER) {
                runCatching { discoverableLauncher.launch(discoverableIntent()) }
                    .onFailure { pendingAction = null; onSnackbar("Could not request discoverability: ${it.message}") }
            } else {
                pendingAction = null
            }
        } else {
            pendingAction = null
            onSnackbar("Bluetooth was not enabled")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.all { it }
        val action = pendingAction
        if (!granted) {
            pendingAction = null
            onSnackbar("Bluetooth permissions are required for SPP emulation")
        } else {
            val bt = adapter
            if (bt == null) {
                pendingAction = null
                onSnackbar("This device does not support Bluetooth")
            } else {
                when (action) {
                    BtPending.ENABLE -> {
                        if (!runCatching { bt.isEnabled }.getOrDefault(false)) {
                            runCatching { enableBtLauncher.launch(enableBluetoothIntent()) }
                                .onFailure { pendingAction = null; onSnackbar("Could not request Bluetooth: ${it.message}") }
                        } else {
                            pendingAction = null
                            viewModel.setBluetoothEnabled(true)
                        }
                    }
                    BtPending.DISCOVER -> {
                        if (!runCatching { bt.isEnabled }.getOrDefault(false)) {
                            runCatching { enableBtLauncher.launch(enableBluetoothIntent()) }
                                .onFailure { pendingAction = null; onSnackbar("Could not request Bluetooth: ${it.message}") }
                        } else {
                            runCatching { discoverableLauncher.launch(discoverableIntent()) }
                                .onFailure { pendingAction = null; onSnackbar("Could not request discoverability: ${it.message}") }
                        }
                    }
                    null -> Unit
                }
            }
        }
    }

    var portText by remember(settings.tcpPort) { mutableStateOf(settings.tcpPort.toString()) }
    var nameText by remember(settings.printerName) { mutableStateOf(settings.printerName) }
    var profileExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(runtime.bluetooth.discoverableUntil) {
        val until = runtime.bluetooth.discoverableUntil ?: return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            if (now >= until) {
                viewModel.setDiscoverableUntil(null)
                break
            }
            delay(1000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(
                    "Local emulation only. Changes take effect immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "Network printer") {
                SettingSwitchRow(
                    title = "Wi-Fi / Ethernet emulation",
                    subtitle = "Listen for raw ESC/POS jobs on TCP",
                    checked = settings.networkEnabled,
                    onCheckedChange = viewModel::setNetworkEnabled,
                    leading = { SectionIcon(Icons.Rounded.Wifi) },
                )
                DividerLine()
                OutlinedTextField(
                    value = portText,
                    onValueChange = { value ->
                        if (value.length <= 5 && value.all { it.isDigit() }) portText = value
                    },
                    label = { Text("TCP port") },
                    supportingText = {
                        val port = portText.toIntOrNull()
                        Text(
                            if (port == null || port !in 1..65535) "Enter a port from 1–65535"
                            else if (port < 1024) "Ports below 1024 may require special privileges"
                            else "Default raw printing port is 9100",
                        )
                    },
                    isError = portText.toIntOrNull()?.let { it !in 1..65535 } ?: true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val port = portText.toIntOrNull()
                            if (port != null && port in 1..65535) {
                                viewModel.setTcpPort(port)
                                onSnackbar("TCP port updated to $port")
                            } else {
                                onSnackbar("Invalid TCP port")
                            }
                        },
                        enabled = portText.toIntOrNull()?.let { it in 1..65535 } == true && portText.toInt() != settings.tcpPort,
                    ) { Text("Apply port") }
                    if (runtime.network.status == ListenerStatus.ERROR) {
                        TextButton(onClick = viewModel::restartNetwork) {
                            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Restart")
                        }
                    }
                }
                DividerLine()
                SettingSwitchRow(
                    title = "Epson ENPC discovery",
                    subtitle = "Answer Epson SDK discovery on UDP 3289",
                    checked = settings.epsonDiscoveryEnabled,
                    onCheckedChange = viewModel::setEpsonDiscovery,
                    enabled = settings.networkEnabled,
                    leading = { SectionIcon(Icons.Rounded.SettingsInputAntenna) },
                )
                if (runtime.network.status == ListenerStatus.ERROR) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        runtime.network.message ?: "Network listener failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "ENPC is approximate. Raw TCP 9100 is the reliable path; Epson ePOS-Print XML / AirPrint are not implemented.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
            }
        }

        item {
            SectionCard(title = "Bluetooth") {
                SettingSwitchRow(
                    title = "Bluetooth SPP emulation",
                    subtitle = "Best-effort serial printer endpoint",
                    checked = settings.bluetoothEnabled,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            viewModel.setBluetoothEnabled(false)
                        } else if (!hasBluetoothPermissions(context)) {
                            pendingAction = BtPending.ENABLE
                            permissionLauncher.launch(bluetoothPermissions())
                        } else if (adapter == null) {
                            onSnackbar("This device does not support Bluetooth")
                        } else if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
                            pendingAction = BtPending.ENABLE
                            runCatching { enableBtLauncher.launch(enableBluetoothIntent()) }
                                .onFailure { pendingAction = null; onSnackbar("Could not request Bluetooth: ${it.message}") }
                        } else {
                            viewModel.setBluetoothEnabled(true)
                        }
                    },
                    leading = { SectionIcon(Icons.Rounded.BluetoothSearching) },
                )
                if (settings.bluetoothEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(bluetoothColor(runtime.bluetooth), pulsing = runtime.bluetooth.status == ListenerStatus.STARTING)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            bluetoothStatusText(runtime.bluetooth),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            color = bluetoothColor(runtime.bluetooth),
                        )
                        runtime.bluetooth.deviceName?.let { name ->
                            Text(
                                " · $name",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (runtime.bluetooth.status == ListenerStatus.ERROR) {
                        Text(
                            runtime.bluetooth.message ?: "Bluetooth SPP is unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermissions(context)) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = StatusAmber, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Nearby-device permission is required", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            pendingAction = BtPending.ENABLE
                            permissionLauncher.launch(bluetoothPermissions())
                        }) { Text("Grant") }
                    }
                }
                DividerLine()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Make discoverable", style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                        val remaining = runtime.bluetooth.discoverableUntil?.let { ((it - now) / 1000).coerceAtLeast(0) }
                        Text(
                            if (remaining != null && remaining > 0) {
                                "Visible for ${remaining / 60}:${String.format(Locale.US, "%02d", remaining % 60)}"
                            } else {
                                "Usually 5 minutes; Android controls the visibility window"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (remaining != null && remaining > 0) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            if (!settings.bluetoothEnabled) {
                                onSnackbar("Enable Bluetooth SPP first")
                            } else if (!hasBluetoothPermissions(context)) {
                                pendingAction = BtPending.DISCOVER
                                permissionLauncher.launch(bluetoothPermissions())
                            } else if (adapter == null) {
                                onSnackbar("This device does not support Bluetooth")
                            } else if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
                                pendingAction = BtPending.DISCOVER
                                runCatching { enableBtLauncher.launch(enableBluetoothIntent()) }
                                    .onFailure { pendingAction = null; onSnackbar("Could not request Bluetooth: ${it.message}") }
                            } else {
                                runCatching { discoverableLauncher.launch(discoverableIntent()) }
                                    .onFailure { onSnackbar("Could not request discoverability: ${it.message}") }
                            }
                        },
                        enabled = settings.bluetoothEnabled,
                    ) {
                        Text("Discover")
                    }
                }
                DividerLine()
                SettingSwitchRow(
                    title = "Use printer name for Bluetooth",
                    subtitle = "Temporarily renames this Android device; restored when disabled",
                    checked = settings.renameBluetoothDevice,
                    onCheckedChange = viewModel::setRenameBluetooth,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "iPadOS cannot use SPP to talk to a non-MFi device, and Android cannot emulate Epson's MFi/BLE services. Use Wi-Fi for iPad POS apps; SPP is useful for Android/Windows clients.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
            }
        }

        item {
            SectionCard(title = "Printer identity") {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it.take(64) },
                    label = { Text("Printer / Bluetooth name") },
                    supportingText = { Text("Example: TM-m30, Front Counter, Receipt Printer") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        if (nameText.isBlank()) {
                            onSnackbar("Printer name cannot be empty")
                        } else {
                            viewModel.setPrinterName(nameText)
                            onSnackbar("Printer identity updated")
                        }
                    },
                    enabled = nameText.trim() != settings.printerName && nameText.isNotBlank(),
                ) { Text("Apply name") }
                DividerLine()
                ExposedDropdownMenuBox(
                    expanded = profileExpanded,
                    onExpandedChange = { profileExpanded = !profileExpanded },
                ) {
                    OutlinedTextField(
                        value = settings.profile.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Emulated model / profile") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = profileExpanded,
                        onDismissRequest = { profileExpanded = false },
                    ) {
                        PrinterProfile.entries.forEach { profile ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(profile.displayName)
                                        Text(
                                            "${profile.paperMillimetres} mm · ${profile.paperWidthDots} dots${if (profile.supportsEpsonExtensions) " · Epson extensions" else ""}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    profileExpanded = false
                                    viewModel.setProfile(profile.name)
                                    onSnackbar("Profile set to ${profile.displayName}")
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Paper width and character metrics apply to new print jobs. Status and ID replies use the selected profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "Output & power") {
                SettingSwitchRow(
                    title = "Auto-scroll receipts",
                    subtitle = "Follow live data; new receipts always start at the top",
                    checked = settings.autoScroll,
                    onCheckedChange = viewModel::setAutoScroll,
                )
                DividerLine()
                SettingSwitchRow(
                    title = "Keep awake while listening",
                    subtitle = "Partial wake lock improves background reliability; uses more battery",
                    checked = settings.keepAwake,
                    onCheckedChange = viewModel::setKeepAwake,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Android/OEM battery optimization can still stop background sockets. A foreground notification and wake lock reduce that risk.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
            }
        }

        item {
            SectionCard(title = "Diagnostics") {
                SettingSwitchRow(
                    title = "Verbose command log",
                    subtitle = "Log every ESC/POS command (new jobs only)",
                    checked = settings.verboseLogging,
                    onCheckedChange = viewModel::setVerboseLogging,
                )
                DividerLine()
                SettingSwitchRow(
                    title = "Simulate paper out",
                    subtitle = "Status replies report an empty paper roll",
                    checked = settings.paperOutSimulated,
                    onCheckedChange = viewModel::setPaperOut,
                )
                DividerLine()
                SettingSwitchRow(
                    title = "Simulate cover open",
                    subtitle = "Status replies report an open printer cover",
                    checked = settings.coverOpenSimulated,
                    onCheckedChange = viewModel::setCoverOpen,
                )
                DividerLine()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Reset virtual printer",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        )
                        Text(
                            "Clear the current receipt and reset alignment/style state",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        viewModel.resetPrinter()
                        onSnackbar("Virtual printer reset")
                    }) { Text("Reset") }
                }
                if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    DividerLine()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.NotificationsOff, null, tint = StatusAmber, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Notifications are disabled; the foreground printer notification may be hidden.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                                )
                            }
                        }) { Text("Open") }
                    }
                }
            }
        }

        item {
            SectionCard(title = "About") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Print, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("TMulator ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "A local ESC/POS thermal receipt printer emulator. No cloud, no accounts, no physical printer. Receipts are rendered to a 203-dpi-style bitmap and stored only on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Not cycle-accurate. Unsupported commands are skipped and logged. Advanced page mode, macros, user-defined characters and proprietary MFi/ePOS XML flows are not emulated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun SectionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
}

private fun bluetoothPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        emptyArray()
    }
}

private fun enableBluetoothIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

private fun discoverableIntent(): Intent =
    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)

private fun hasBluetoothPermissions(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return bluetoothPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
