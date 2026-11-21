package dev.tmulator.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.tmulator.MainActivity
import dev.tmulator.R
import dev.tmulator.TMulatorApp
import dev.tmulator.data.BluetoothRuntime
import dev.tmulator.data.JobSource
import dev.tmulator.data.ListenerStatus
import dev.tmulator.data.NetworkRuntime
import dev.tmulator.data.PrinterSettings
import dev.tmulator.data.RuntimeLogLevel
import dev.tmulator.transport.BluetoothSppServer
import dev.tmulator.transport.EnpcDiscoveryResponder
import dev.tmulator.transport.NetworkPrintServer
import dev.tmulator.transport.NetworkPrintServerListener
import dev.tmulator.transport.PrintPeer
import dev.tmulator.transport.TcpPeer
import dev.tmulator.util.NetworkAddresses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class PrinterService : Service() {

    private val graph get() = (application as TMulatorApp).graph
    private val runtime get() = graph.runtime
    private lateinit var scope: CoroutineScope
    private lateinit var jobManager: JobManager

    private var networkServer: NetworkPrintServer? = null
    private var discovery: EnpcDiscoveryResponder? = null
    private var bluetoothServer: BluetoothSppServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var settingsJob: Job? = null
    private var ipJob: Job? = null
    private var applied: PrinterSettings? = null
    private var shuttingDown = false
    private var foregroundStarted = false
    private var networkGeneration = 0
    private var bluetoothGeneration = 0
    private val networkClients = AtomicInteger(0)
    private val btClients = AtomicInteger(0)
    private val networkClientIps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var holderIp: String? = null
    private val btNamePrefs by lazy { getSharedPreferences("tmulator_bluetooth", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        jobManager = JobManager(
            runtime = runtime,
            jobStore = graph.jobs,
            settings = { graph.settings.current },
            scope = scope,
        )
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            scope.launch {
                runCatching { graph.settings.setNetworkEnabled(false) }
                runCatching { graph.settings.setBluetoothEnabled(false) }
            }
            shutdownAndStop()
            return START_NOT_STICKY
        }

        ensureForeground()
        ensureCollectors()
        runtime.setServiceRunning(true)

        when (action) {
            ACTION_CLEAR -> {
                jobManager.clearCurrent()
                if (runtime.state.value.network.enabled || runtime.state.value.bluetooth.enabled) {
                    updateNotification()
                }
            }
            ACTION_RESET -> {
                jobManager.resetAll()
                if (runtime.state.value.network.enabled || runtime.state.value.bluetooth.enabled) {
                    updateNotification()
                }
            }
            ACTION_TEST -> sendTestReceipt()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cleanup()
        runtime.setServiceRunning(false)
        super.onDestroy()
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        val notification = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            foregroundStarted = true
        } catch (t: Throwable) {
            runtime.log(RuntimeLogLevel.ERROR, "SYS", "Foreground service could not start", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun ensureCollectors() {
        if (settingsJob != null) return
        settingsJob = scope.launch {
            graph.settings.settings.collect { settings -> applySettings(settings) }
        }
        ipJob = scope.launch {
            while (isActive) {
                refreshAddresses(updateNotification = true)
                delay(5_000)
            }
        }
    }

    private fun applySettings(settings: PrinterSettings) {
        if (shuttingDown) return
        val previous = applied
        val statusChanged = previous != null && (
            previous.paperOutSimulated != settings.paperOutSimulated ||
                previous.coverOpenSimulated != settings.coverOpenSimulated
            )
        applied = settings

        runtime.setProfile(settings.profile)
        runtime.setSimulatedStatus(settings.paperOutSimulated, settings.coverOpenSimulated)
        if (statusChanged) jobManager.broadcastAsb()
        refreshAddresses(updateNotification = false)

        val networkNeedsRestart = previous == null ||
            previous.networkEnabled != settings.networkEnabled ||
            previous.tcpPort != settings.tcpPort
        if (networkNeedsRestart) {
            stopNetwork()
            if (settings.networkEnabled) startNetwork(settings)
        }

        if (!settings.networkEnabled) {
            stopDiscovery()
        } else if (
            settings.epsonDiscoveryEnabled &&
            (networkNeedsRestart || previous?.epsonDiscoveryEnabled != settings.epsonDiscoveryEnabled)
        ) {
            startDiscovery()
        } else if (!settings.epsonDiscoveryEnabled) {
            stopDiscovery()
        }

        val btNeedsRestart = previous == null ||
            previous.bluetoothEnabled != settings.bluetoothEnabled ||
            previous.printerName != settings.printerName ||
            previous.renameBluetoothDevice != settings.renameBluetoothDevice
        if (btNeedsRestart) {
            stopBluetooth()
            if (settings.bluetoothEnabled) startBluetooth(settings)
        }

        syncWakeLock(settings)
        if (!settings.networkEnabled && !settings.bluetoothEnabled) {
            shutdownAndStop()
            return
        }
        updateNotification()
    }

    // --------------------------------------------------------------- network

    private fun startNetwork(settings: PrinterSettings) {
        val generation = ++networkGeneration
        networkClients.set(0)
        networkClientIps.clear()
        holderIp = null
        runtime.setNetwork(true, ListenerStatus.STARTING, settings.tcpPort)
        runtime.setNetworkClients(0)
        runtime.log(RuntimeLogLevel.INFO, "NET", "Starting network printer", "0.0.0.0:${settings.tcpPort}")

        val listener = object : NetworkPrintServerListener {
            override fun onListening(port: Int) {
                if (generation != networkGeneration || shuttingDown) return
                runtime.setNetwork(true, ListenerStatus.LISTENING, port)
                runtime.log(RuntimeLogLevel.INFO, "NET", "Network printer listening", "TCP $port")
                refreshAddresses(updateNotification = false)
                updateNotification()
            }

            override fun onListenError(message: String) {
                if (generation != networkGeneration || shuttingDown) return
                runtime.setNetwork(true, ListenerStatus.ERROR, settings.tcpPort, message)
                runtime.log(RuntimeLogLevel.ERROR, "NET", "Network listener error", message)
                updateNotification()
            }

            override fun onPeerConnected(peer: TcpPeer) {
                if (generation != networkGeneration || shuttingDown) return
                networkClientIps.add(peer.remoteAddress)
                holderIp = peer.remoteAddress
                val count = networkClients.incrementAndGet()
                runtime.setNetworkClients(count, peer.remoteAddress)
                runtime.log(RuntimeLogLevel.INFO, "NET", "POS connected", peer.remoteAddress)
                jobManager.begin(peer, JobSource.NETWORK)
                updateNotification()
            }

            override fun onPeerData(peer: TcpPeer, data: ByteArray) {
                if (generation != networkGeneration || shuttingDown) return
                jobManager.handleData(peer, data)
            }

            override fun onPeerDisconnected(peer: TcpPeer) {
                jobManager.end(peer)
                if (generation != networkGeneration || shuttingDown) return
                networkClientIps.remove(peer.remoteAddress)
                val count = networkClients.updateAndGet { if (it > 0) it - 1 else 0 }
                if (peer.remoteAddress == holderIp) {
                    holderIp = networkClientIps.firstOrNull()
                }
                runtime.setNetworkClients(count)
                runtime.log(RuntimeLogLevel.INFO, "NET", "POS disconnected", peer.remoteAddress)
                updateNotification()
            }
        }

        networkServer = NetworkPrintServer(settings.tcpPort, listener).also { it.start() }
    }

    private fun stopNetwork() {
        networkGeneration++
        networkServer?.stop()
        networkServer = null
        networkClients.set(0)
        networkClientIps.clear()
        holderIp = null
        stopDiscovery()
        runtime.setNetwork(false, ListenerStatus.DISABLED, applied?.tcpPort ?: 9100)
        runtime.setNetworkClients(0)
    }

    private fun startDiscovery() {
        stopDiscovery()
        if (shuttingDown) return
        runtime.setDiscovery(ListenerStatus.STARTING)
        val responder = EnpcDiscoveryResponder(
            identity = { NetworkAddresses.inspect(this).identity },
            holderIp = { NetworkAddresses.parseIpv4(holderIp) },
            deviceName = { graph.settings.current.printerName },
            log = { message, detail ->
                runtime.log(
                    if (message.contains("ignored")) RuntimeLogLevel.DEBUG else RuntimeLogLevel.INFO,
                    "NET",
                    message,
                    detail,
                )
            },
            onListening = {
                runtime.setDiscovery(ListenerStatus.LISTENING)
                runtime.log(RuntimeLogLevel.INFO, "NET", "Epson discovery active", "ENPC UDP 3289")
            },
            onError = { message ->
                runtime.setDiscovery(ListenerStatus.ERROR, message)
                runtime.log(RuntimeLogLevel.WARN, "NET", "Epson discovery unavailable", message)
            },
        )
        discovery = responder
        responder.start(3289)
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
        runtime.setDiscovery(ListenerStatus.DISABLED)
    }

    // -------------------------------------------------------------- bluetooth

    private fun startBluetooth(settings: PrinterSettings) {
        val generation = ++bluetoothGeneration
        btClients.set(0)
        if (settings.renameBluetoothDevice) rememberBluetoothName(settings.printerName)
        runtime.setBluetooth(true, ListenerStatus.STARTING, null, null)
        val listener = object : BluetoothSppServer.Listener {
            override fun onListening(deviceName: String, renamed: Boolean) {
                if (generation != bluetoothGeneration || shuttingDown) return
                runtime.setBluetooth(true, ListenerStatus.LISTENING, deviceName, null)
                runtime.log(
                    RuntimeLogLevel.INFO,
                    "BT",
                    "Bluetooth SPP listening",
                    if (renamed) "Device renamed to \"$deviceName\"" else "Device name \"$deviceName\"",
                )
                updateNotification()
            }

            override fun onError(message: String) {
                if (generation != bluetoothGeneration || shuttingDown) return
                runtime.setBluetooth(true, ListenerStatus.ERROR, null, message)
                runtime.log(RuntimeLogLevel.ERROR, "BT", "Bluetooth error", message)
                updateNotification()
            }

            override fun onPeerConnected(peer: BluetoothSppServer.BluetoothPeer) {
                if (generation != bluetoothGeneration || shuttingDown) return
                btClients.set(1)
                runtime.setBluetoothClients(1)
                runtime.log(RuntimeLogLevel.INFO, "BT", "SPP client connected", peer.remoteAddress)
                jobManager.begin(peer, JobSource.BLUETOOTH)
                updateNotification()
            }

            override fun onPeerData(peer: BluetoothSppServer.BluetoothPeer, data: ByteArray) {
                if (generation != bluetoothGeneration || shuttingDown) return
                jobManager.handleData(peer, data)
            }

            override fun onPeerDisconnected(peer: BluetoothSppServer.BluetoothPeer) {
                jobManager.end(peer)
                if (generation != bluetoothGeneration || shuttingDown) return
                btClients.set(0)
                runtime.setBluetoothClients(0)
                runtime.log(RuntimeLogLevel.INFO, "BT", "SPP client disconnected", peer.remoteAddress)
                updateNotification()
            }
        }
        bluetoothServer = BluetoothSppServer(this, listener).also {
            it.start(settings.printerName, settings.renameBluetoothDevice)
        }
    }

    private fun stopBluetooth() {
        bluetoothGeneration++
        bluetoothServer?.stop()
        bluetoothServer = null
        btClients.set(0)
        restoreBluetoothName()
        runtime.setBluetooth(false, ListenerStatus.DISABLED)
        runtime.setBluetoothClients(0)
    }

    @SuppressLint("MissingPermission")
    private fun rememberBluetoothName(replacement: String) {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (btNamePrefs.contains(KEY_BT_ORIGINAL_NAME)) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        val current = runCatching { adapter.name }.getOrNull()
        if (!current.isNullOrBlank() && current != replacement) {
            btNamePrefs.edit().putString(KEY_BT_ORIGINAL_NAME, current).apply()
        }
    }

    @SuppressLint("MissingPermission")
    private fun restoreBluetoothName() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val original = btNamePrefs.getString(KEY_BT_ORIGINAL_NAME, null) ?: return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter != null && runCatching { adapter.setName(original) }.getOrDefault(false)) {
            btNamePrefs.edit().remove(KEY_BT_ORIGINAL_NAME).apply()
            runtime.log(RuntimeLogLevel.INFO, "BT", "Bluetooth name restored", original)
        }
    }

    // ------------------------------------------------------------------ test

    private fun sendTestReceipt() {
        val settings = graph.settings.current
        if (!settings.networkEnabled) {
            runtime.log(RuntimeLogLevel.WARN, "SYS", "Test print needs network emulation", "Enable the network printer first")
            return
        }
        scope.launch(Dispatchers.IO) {
            val port = settings.tcpPort
            var lastError: String? = null
            repeat(12) { attempt ->
                val socket = Socket()
                try {
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                    socket.getOutputStream().use { output ->
                        output.write(dev.tmulator.util.SampleReceipt.bytes())
                        output.flush()
                    }
                    runtime.log(RuntimeLogLevel.INFO, "SYS", "Test receipt sent", "loopback 127.0.0.1:$port")
                    return@launch
                } catch (t: Throwable) {
                    lastError = t.message ?: t.javaClass.simpleName
                    runCatching { socket.close() }
                    if (attempt < 11) delay(250)
                }
            }
            runtime.log(RuntimeLogLevel.ERROR, "SYS", "Test print failed", "127.0.0.1:$port · $lastError")
        }
    }

    // ------------------------------------------------------------------ power

    private fun syncWakeLock(settings: PrinterSettings) {
        val needed = settings.keepAwake && (settings.networkEnabled || settings.bluetoothEnabled)
        if (needed) {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TMulator:printer").apply {
                    setReferenceCounted(false)
                }
                wakeLock = lock
                runCatching { lock.acquire() }
                runtime.log(RuntimeLogLevel.DEBUG, "SYS", "Wake lock acquired")
            }
        } else {
            wakeLock?.let {
                if (it.isHeld) runCatching { it.release() }
            }
            wakeLock = null
        }
        syncWifiLock(settings.keepAwake && settings.networkEnabled)
    }

    /**
     * Keep the Wi-Fi radio out of power save while the virtual printer is
     * listening. Without this, the first TCP segments of a receipt can be
     * delayed by retransmission timeouts (the classic 1+2+4 s stall) even
     * though the CPU is awake.
     */
    private fun syncWifiLock(needed: Boolean) {
        if (!needed) {
            wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
            wifiLock = null
            return
        }
        if (wifiLock?.isHeld == true) return
        val wm = getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        val lock = runCatching { wm.createWifiLock(mode, "TMulator:wifi") }.getOrNull() ?: return
        lock.setReferenceCounted(false)
        if (runCatching { lock.acquire() }.isSuccess) {
            wifiLock = lock
            runtime.log(RuntimeLogLevel.DEBUG, "SYS", "Wi-Fi low-latency lock acquired")
        }
    }

    private fun releaseWakeLock() {
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
        wakeLock?.let {
            if (it.isHeld) runCatching { it.release() }
        }
        wakeLock = null
    }

    private fun refreshAddresses(updateNotification: Boolean) {
        val ips = NetworkAddresses.localIpv4(this)
        runtime.setLocalIps(ips)
        if (updateNotification && foregroundStarted) updateNotification()
    }

    // ----------------------------------------------------------- notifications

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val state = runtime.state.value
        val ip = state.localIps.firstOrNull()
        val networkText = when {
            state.network.status == ListenerStatus.ERROR -> state.network.message ?: "Network error"
            state.network.clients > 0 -> "Printing from ${state.network.lastClient ?: "POS"}"
            state.network.status == ListenerStatus.LISTENING -> "${ip ?: "No Wi-Fi IP"}:${state.network.port}"
            state.network.enabled -> "Starting network listener…"
            else -> "Network off"
        }
        val btText = when {
            state.bluetooth.status == ListenerStatus.ERROR -> "Bluetooth error"
            state.bluetooth.clients > 0 -> "SPP connected"
            state.bluetooth.status == ListenerStatus.LISTENING -> "SPP ready${state.bluetooth.deviceName?.let { " · $it" } ?: ""}"
            state.bluetooth.enabled -> "Bluetooth starting…"
            else -> "Bluetooth off"
        }
        val title = when {
            state.network.clients > 0 || state.bluetooth.clients > 0 -> "Receipt printer connected"
            state.network.status == ListenerStatus.ERROR && state.bluetooth.status != ListenerStatus.LISTENING -> "Virtual printer needs attention"
            else -> "Virtual receipt printer ready"
        }
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val clear = PendingIntent.getService(
            this,
            2,
            Intent(this, PrinterService::class.java).setAction(ACTION_CLEAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            3,
            Intent(this, PrinterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("$networkText · $btText")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$networkText\n$btText"))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Clear receipt", clear)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification() {
        if (!foregroundStarted || shuttingDown) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- shutdown

    private fun shutdownAndStop() {
        if (shuttingDown) return
        shuttingDown = true
        cleanup()
        runtime.setServiceRunning(false)
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        foregroundStarted = false
        stopSelf()
    }

    private fun cleanup() {
        runCatching { networkServer?.stop() }
        networkServer = null
        networkGeneration++
        runCatching { discovery?.stop() }
        discovery = null
        runCatching { bluetoothServer?.stop() }
        bluetoothServer = null
        bluetoothGeneration++
        networkClients.set(0)
        networkClientIps.clear()
        btClients.set(0)
        holderIp = null
        jobManager.finishAll()
        releaseWakeLock()
        runtime.setNetwork(false, ListenerStatus.DISABLED, applied?.tcpPort ?: 9100)
        runtime.setBluetooth(false, ListenerStatus.DISABLED)
        runtime.setDiscovery(ListenerStatus.DISABLED)
        runtime.setServiceRunning(false)
        settingsJob?.cancel()
        settingsJob = null
        ipJob?.cancel()
        ipJob = null
    }

    companion object {
        const val ACTION_START = "dev.tmulator.action.START"
        const val ACTION_STOP = "dev.tmulator.action.STOP"
        const val ACTION_CLEAR = "dev.tmulator.action.CLEAR"
        const val ACTION_RESET = "dev.tmulator.action.RESET"
        const val ACTION_TEST = "dev.tmulator.action.TEST"
        private const val CHANNEL_ID = "printer"
        private const val NOTIFICATION_ID = 9100
        private const val KEY_BT_ORIGINAL_NAME = "original_name"

        fun start(context: Context) {
            val intent = Intent(context, PrinterService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun clear(context: Context) {
            context.startService(Intent(context, PrinterService::class.java).setAction(ACTION_CLEAR))
        }

        fun reset(context: Context) {
            context.startService(Intent(context, PrinterService::class.java).setAction(ACTION_RESET))
        }

        fun testReceipt(context: Context) {
            context.startService(Intent(context, PrinterService::class.java).setAction(ACTION_TEST))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PrinterService::class.java).setAction(ACTION_STOP))
        }
    }
}
