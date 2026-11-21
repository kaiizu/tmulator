package dev.tmulator.data

import android.graphics.Bitmap
import android.util.Log
import dev.tmulator.BuildConfig
import dev.tmulator.escpos.EscPosLog
import dev.tmulator.escpos.LogSeverity
import dev.tmulator.escpos.PrinterProfile
import dev.tmulator.escpos.ReceiptSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class ListenerStatus { DISABLED, STARTING, LISTENING, CONNECTED, ERROR }

data class NetworkRuntime(
    val enabled: Boolean = false,
    val status: ListenerStatus = ListenerStatus.DISABLED,
    val port: Int = 9100,
    val clients: Int = 0,
    val lastClient: String? = null,
    val message: String? = null,
    val discoveryStatus: ListenerStatus = ListenerStatus.DISABLED,
    val discoveryMessage: String? = null,
)

data class BluetoothRuntime(
    val enabled: Boolean = false,
    val status: ListenerStatus = ListenerStatus.DISABLED,
    val deviceName: String? = null,
    val clients: Int = 0,
    val message: String? = null,
    val discoverableUntil: Long? = null,
)

data class ActiveJobInfo(
    val id: Long,
    val source: JobSource,
    val remote: String,
    val startedAt: Long,
    val bytes: Long,
    val commands: Int,
    val unsupported: Int,
    val blocks: Int,
    val hasCut: Boolean,
)

enum class RuntimeLogLevel { DEBUG, INFO, WARN, ERROR }

data class AppLogEntry(
    val id: Long,
    val time: Long,
    val source: String,
    val level: RuntimeLogLevel,
    val message: String,
    val detail: String? = null,
)

data class RuntimeState(
    val network: NetworkRuntime = NetworkRuntime(),
    val bluetooth: BluetoothRuntime = BluetoothRuntime(),
    val activeJob: ActiveJobInfo? = null,
    val preview: Bitmap? = null,
    val previewRevision: Long = 0,
    val logs: List<AppLogEntry> = emptyList(),
    val cutCount: Int = 0,
    val cutEventAt: Long = 0,
    val serviceRunning: Boolean = false,
    val paperOut: Boolean = false,
    val coverOpen: Boolean = false,
    val profile: PrinterProfile = PrinterProfile.TM_M30III,
    val localIps: List<String> = emptyList(),
    val jobsHandled: Int = 0,
) {
    val hasReceipt: Boolean get() = preview != null
}

/**
 * Live, process-wide printer state. All transport/job threads publish here;
 * Compose observes a single StateFlow.
 */
class PrinterRuntime {
    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    @Volatile var latestSnapshot: ReceiptSnapshot? = null
        private set
    @Volatile var latestBitmap: Bitmap? = null
        private set

    private val logId = AtomicLong(0)
    private val revision = AtomicLong(0)

    fun setServiceRunning(running: Boolean) = _state.update { it.copy(serviceRunning = running) }

    fun setProfile(profile: PrinterProfile) = _state.update { it.copy(profile = profile) }

    fun setLocalIps(ips: List<String>) = _state.update { it.copy(localIps = ips) }

    fun setNetwork(
        enabled: Boolean,
        status: ListenerStatus,
        port: Int,
        message: String? = null,
    ) = _state.update {
        it.copy(
            network = it.network.copy(
                enabled = enabled,
                status = status,
                port = port,
                message = message,
            ),
        )
    }

    fun setNetworkClients(count: Int, lastClient: String? = null) = _state.update {
        it.copy(
            network = it.network.copy(
                clients = count,
                lastClient = lastClient ?: it.network.lastClient,
                status = if (count > 0) ListenerStatus.CONNECTED else if (it.network.status == ListenerStatus.CONNECTED) ListenerStatus.LISTENING else it.network.status,
            ),
        )
    }

    fun setDiscovery(status: ListenerStatus, message: String? = null) = _state.update {
        it.copy(network = it.network.copy(discoveryStatus = status, discoveryMessage = message))
    }

    fun setBluetooth(
        enabled: Boolean,
        status: ListenerStatus,
        deviceName: String? = null,
        message: String? = null,
    ) = _state.update {
        it.copy(
            bluetooth = it.bluetooth.copy(
                enabled = enabled,
                status = status,
                deviceName = deviceName ?: it.bluetooth.deviceName,
                message = message,
            ),
        )
    }

    fun setBluetoothClients(count: Int) = _state.update {
        it.copy(
            bluetooth = it.bluetooth.copy(
                clients = count,
                status = if (count > 0) ListenerStatus.CONNECTED else if (it.bluetooth.status == ListenerStatus.CONNECTED) ListenerStatus.LISTENING else it.bluetooth.status,
            ),
        )
    }

    fun setDiscoverableUntil(until: Long?) = _state.update {
        it.copy(bluetooth = it.bluetooth.copy(discoverableUntil = until))
    }

    fun setActiveJob(info: ActiveJobInfo?) = _state.update { it.copy(activeJob = info) }

    fun showPreview(bitmap: Bitmap, snapshot: ReceiptSnapshot, job: ActiveJobInfo?) {
        latestSnapshot = snapshot
        latestBitmap = bitmap
        val rev = revision.incrementAndGet()
        _state.update { it.copy(preview = bitmap, previewRevision = rev, activeJob = job) }
    }

    fun clearReceipt() {
        latestSnapshot = null
        latestBitmap = null
        revision.incrementAndGet()
        _state.update {
            it.copy(
                preview = null,
                previewRevision = it.previewRevision + 1,
                activeJob = it.activeJob?.copy(blocks = 0, hasCut = false),
                cutCount = 0,
                cutEventAt = 0,
            )
        }
    }

    fun noteCut() = _state.update { it.copy(cutCount = it.cutCount + 1, cutEventAt = System.currentTimeMillis()) }

    fun setSimulatedStatus(paperOut: Boolean, coverOpen: Boolean) = _state.update {
        it.copy(paperOut = paperOut, coverOpen = coverOpen)
    }

    fun incrementJobs() = _state.update { it.copy(jobsHandled = it.jobsHandled + 1) }

    fun clearLogs() = _state.update { it.copy(logs = emptyList()) }

    fun log(level: RuntimeLogLevel, source: String, message: String, detail: String? = null) {
        val entry = AppLogEntry(
            id = logId.incrementAndGet(),
            time = System.currentTimeMillis(),
            source = source,
            level = level,
            message = message,
            detail = detail?.take(2000),
        )
        if (BuildConfig.DEBUG) {
            // Debug builds mirror the in-app log to logcat so timing can be
            // measured from adb without scrolling the UI.
            Log.println(
                Log.DEBUG,
                "TMulator",
                "$source ${level.name} $message" + (detail?.let { " · ${it.take(400)}" } ?: ""),
            )
        }
        _state.update { it.copy(logs = (listOf(entry) + it.logs).take(MAX_LOGS)) }
    }

    fun log(entry: EscPosLog, source: String = "ESC/POS") {
        log(entry.severity.toRuntime(), source, entry.title, listOfNotNull(entry.detail, entry.hex?.let { "hex: $it" }).joinToString(" · ").ifBlank { null })
    }

    private fun LogSeverity.toRuntime(): RuntimeLogLevel = when (this) {
        LogSeverity.DEBUG -> RuntimeLogLevel.DEBUG
        LogSeverity.INFO -> RuntimeLogLevel.INFO
        LogSeverity.WARN -> RuntimeLogLevel.WARN
        LogSeverity.ERROR -> RuntimeLogLevel.ERROR
    }

    companion object {
        private const val MAX_LOGS = 500
    }
}
