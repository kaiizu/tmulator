package dev.tmulator.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.tmulator.TMulatorApp
import dev.tmulator.data.PrintJobSummary
import dev.tmulator.data.PrinterSettings
import dev.tmulator.service.PrinterService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PrinterViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TMulatorApp
    val graph = app.graph
    val settings = graph.settings.settings
    val runtime = graph.runtime.state
    val jobs = graph.jobs.jobs

    init {
        viewModelScope.launch {
            settings
                .map { it.networkEnabled || it.bluetoothEnabled }
                .distinctUntilChanged()
                .collect { shouldRun ->
                    if (shouldRun) startServiceSafely()
                }
        }
    }

    fun ensureServiceRunning() {
        val value = settings.value
        if (value.networkEnabled || value.bluetoothEnabled) startServiceSafely()
    }

    private fun startServiceSafely() {
        try {
            PrinterService.start(getApplication())
        } catch (t: Throwable) {
            Log.e("PrinterViewModel", "Could not start printer service", t)
            graph.runtime.log(
                dev.tmulator.data.RuntimeLogLevel.ERROR,
                "SYS",
                "Could not start foreground service",
                t.message ?: t.javaClass.simpleName,
            )
        }
    }

    fun setNetworkEnabled(enabled: Boolean) = viewModelScope.launch {
        graph.settings.setNetworkEnabled(enabled)
    }

    fun setTcpPort(port: Int) = viewModelScope.launch {
        if (port in 1..65535) graph.settings.setTcpPort(port)
    }

    fun setEpsonDiscovery(enabled: Boolean) = viewModelScope.launch {
        graph.settings.setEpsonDiscovery(enabled)
    }

    fun setBluetoothEnabled(enabled: Boolean) = viewModelScope.launch {
        graph.settings.setBluetoothEnabled(enabled)
    }

    fun setPrinterName(name: String) = viewModelScope.launch {
        graph.settings.setPrinterName(name)
    }

    fun setProfile(profileId: String) = viewModelScope.launch {
        graph.settings.setProfileId(profileId)
    }

    fun setAutoScroll(enabled: Boolean) = viewModelScope.launch {
        graph.settings.setAutoScroll(enabled)
    }

    fun setKeepAwake(enabled: Boolean) = viewModelScope.launch {
        graph.settings.setKeepAwake(enabled)
    }

    fun setVerboseLogging(enabled: Boolean) = viewModelScope.launch {
        graph.settings.setVerboseLogging(enabled)
    }

    fun setRenameBluetooth(enabled: Boolean) = viewModelScope.launch {
        graph.settings.setRenameBluetooth(enabled)
    }

    fun setPaperOut(enabled: Boolean) = viewModelScope.launch {
        graph.settings.setPaperOut(enabled)
    }

    fun setCoverOpen(enabled: Boolean) = viewModelScope.launch {
        graph.settings.setCoverOpen(enabled)
    }

    fun setDiscoverableUntil(until: Long?) {
        graph.runtime.setDiscoverableUntil(until)
    }

    fun clearReceipt() {
        graph.runtime.clearReceipt()
        if (runtime.value.serviceRunning) {
            runCatching { PrinterService.clear(getApplication()) }
        }
    }

    fun resetPrinter() {
        graph.runtime.clearReceipt()
        if (runtime.value.serviceRunning) {
            runCatching { PrinterService.reset(getApplication()) }
        }
    }

    fun testReceipt() {
        val current = settings.value
        if (!current.networkEnabled) {
            graph.runtime.log(
                dev.tmulator.data.RuntimeLogLevel.WARN,
                "SYS",
                "Test print needs network emulation",
                "Enable the network printer in Settings",
            )
            return
        }
        runCatching { PrinterService.testReceipt(getApplication()) }
            .onFailure {
                graph.runtime.log(
                    dev.tmulator.data.RuntimeLogLevel.ERROR,
                    "SYS",
                    "Could not request test print",
                    it.message ?: it.javaClass.simpleName,
                )
            }
    }

    fun restartNetwork() = viewModelScope.launch {
        graph.settings.setNetworkEnabled(false)
        delay(250)
        graph.settings.setNetworkEnabled(true)
    }

    fun clearLogs() = graph.runtime.clearLogs()

    fun deleteJob(job: PrintJobSummary) = viewModelScope.launch {
        graph.jobs.delete(job.id)
    }

    fun clearHistory() = viewModelScope.launch {
        graph.jobs.clear()
    }

    fun snapshotText(): String = graph.runtime.latestSnapshot?.plainText().orEmpty()

    fun currentSettings(): PrinterSettings = graph.settings.current
}
