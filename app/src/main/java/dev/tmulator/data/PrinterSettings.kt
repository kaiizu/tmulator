package dev.tmulator.data

import dev.tmulator.escpos.PrinterProfile

data class PrinterSettings(
    val networkEnabled: Boolean = true,
    val tcpPort: Int = 9100,
    val epsonDiscoveryEnabled: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val printerName: String = "TM-m30III",
    val profileId: String = PrinterProfile.TM_M30III.name,
    val autoScroll: Boolean = false,
    val keepAwake: Boolean = true,
    val verboseLogging: Boolean = false,
    val renameBluetoothDevice: Boolean = true,
    val paperOutSimulated: Boolean = false,
    val coverOpenSimulated: Boolean = false,
) {
    val profile: PrinterProfile get() = PrinterProfile.fromId(profileId)
}
