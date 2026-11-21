package dev.tmulator.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

private val Context.printerSettingsStore by preferencesDataStore(name = "printer_settings")

class SettingsRepository(
    private val context: Context,
    scope: CoroutineScope,
) {
    private object Keys {
        val networkEnabled = booleanPreferencesKey("network_enabled")
        val tcpPort = intPreferencesKey("tcp_port")
        val epsonDiscovery = booleanPreferencesKey("epson_discovery")
        val bluetoothEnabled = booleanPreferencesKey("bluetooth_enabled")
        val printerName = stringPreferencesKey("printer_name")
        val profileId = stringPreferencesKey("profile_id")
        val autoScroll = booleanPreferencesKey("auto_scroll")
        val keepAwake = booleanPreferencesKey("keep_awake")
        val verboseLogging = booleanPreferencesKey("verbose_logging")
        val renameBluetooth = booleanPreferencesKey("rename_bluetooth")
        val paperOut = booleanPreferencesKey("paper_out_sim")
        val coverOpen = booleanPreferencesKey("cover_open_sim")
    }

    val settings: StateFlow<PrinterSettings> = context.printerSettingsStore.data
        .catch { e ->
            if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw e
        }
        .map { prefs ->
            PrinterSettings(
                networkEnabled = prefs[Keys.networkEnabled] ?: true,
                tcpPort = (prefs[Keys.tcpPort] ?: 9100).coerceIn(1, 65535),
                epsonDiscoveryEnabled = prefs[Keys.epsonDiscovery] ?: true,
                bluetoothEnabled = prefs[Keys.bluetoothEnabled] ?: false,
                printerName = (prefs[Keys.printerName] ?: "TM-m30III").ifBlank { "TM-m30III" }.take(64),
                profileId = prefs[Keys.profileId] ?: dev.tmulator.escpos.PrinterProfile.TM_M30III.name,
                autoScroll = prefs[Keys.autoScroll] ?: false,
                keepAwake = prefs[Keys.keepAwake] ?: true,
                verboseLogging = prefs[Keys.verboseLogging] ?: false,
                renameBluetoothDevice = prefs[Keys.renameBluetooth] ?: true,
                paperOutSimulated = prefs[Keys.paperOut] ?: false,
                coverOpenSimulated = prefs[Keys.coverOpen] ?: false,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, PrinterSettings())

    val current: PrinterSettings get() = settings.value

    suspend fun setNetworkEnabled(enabled: Boolean) = edit(Keys.networkEnabled, enabled)
    suspend fun setTcpPort(port: Int) = edit(Keys.tcpPort, port.coerceIn(1, 65535))
    suspend fun setEpsonDiscovery(enabled: Boolean) = edit(Keys.epsonDiscovery, enabled)
    suspend fun setBluetoothEnabled(enabled: Boolean) = edit(Keys.bluetoothEnabled, enabled)
    suspend fun setPrinterName(name: String) = edit(Keys.printerName, name.trim().ifBlank { "TM-m30III" }.take(64))
    suspend fun setProfileId(id: String) = edit(Keys.profileId, id)
    suspend fun setAutoScroll(enabled: Boolean) = edit(Keys.autoScroll, enabled)
    suspend fun setKeepAwake(enabled: Boolean) = edit(Keys.keepAwake, enabled)
    suspend fun setVerboseLogging(enabled: Boolean) = edit(Keys.verboseLogging, enabled)
    suspend fun setRenameBluetooth(enabled: Boolean) = edit(Keys.renameBluetooth, enabled)
    suspend fun setPaperOut(enabled: Boolean) = edit(Keys.paperOut, enabled)
    suspend fun setCoverOpen(enabled: Boolean) = edit(Keys.coverOpen, enabled)

    private suspend fun <T> edit(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.printerSettingsStore.edit { it[key] = value }
    }
}
