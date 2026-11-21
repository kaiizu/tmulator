package dev.tmulator.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Best-effort Bluetooth SPP printer endpoint. Android can host a secure RFCOMM
 * server, but cannot present an MFi printer to iPadOS, change the Class of
 * Device, or set a fixed pairing PIN. See SPEC.md.
 */
class BluetoothSppServer(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onListening(deviceName: String, renamed: Boolean)
        fun onError(message: String)
        fun onPeerConnected(peer: BluetoothPeer)
        fun onPeerData(peer: BluetoothPeer, data: ByteArray)
        fun onPeerDisconnected(peer: BluetoothPeer)
    }

    class BluetoothPeer internal constructor(
        val id: Long,
        override val remoteAddress: String,
        private val keyPrefix: String,
        private val socket: BluetoothSocket,
    ) : PrintPeer {
        override val key: String get() = "$keyPrefix-$id"

        override fun write(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            try {
                synchronized(socket) {
                    socket.outputStream.write(bytes)
                    socket.outputStream.flush()
                }
            } catch (_: Throwable) {
            }
        }
    }

    @Volatile private var running = false
    private var serverSocket: BluetoothServerSocket? = null
    private var thread: Thread? = null
    private val instanceToken = UUID.randomUUID().toString().take(8)
    private var originalName: String? = null
    private var changedName = false
    private var adapter: BluetoothAdapter? = null
    private var connected: BluetoothSocket? = null
    private val nextId = AtomicLong(1)

    @Volatile var isRunning: Boolean = false
        private set

    @SuppressLint("MissingPermission") // hasPermission() is checked at entry; adapter calls are inside try/catch.
    fun start(identity: String, renameDevice: Boolean) {
        if (running) return
        if (!hasPermission()) {
            listener.onError("Bluetooth permission is required. Open Settings and allow Nearby devices.")
            return
        }
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bt = bluetoothManager?.adapter
        if (bt == null) {
            listener.onError("This device does not support Bluetooth.")
            return
        }
        if (!bt.isEnabled) {
            listener.onError("Bluetooth is turned off.")
            return
        }
        adapter = bt
        running = true
        try {
            if (renameDevice) {
                originalName = bt.name
                val wanted = identity.take(64)
                if (!originalName.isNullOrBlank() && originalName != wanted) {
                    changedName = bt.name != wanted && bt.setName(wanted)
                }
            }
            serverSocket = bt.listenUsingRfcommWithServiceRecord("TMulator SPP", SPP_UUID)
            isRunning = true
            listener.onListening(bt.name ?: identity, changedName)
        } catch (t: Throwable) {
            running = false
            isRunning = false
            listener.onError("Bluetooth SPP server failed: ${t.message ?: t.javaClass.simpleName}")
            runCatching { serverSocket?.close() }
            return
        }

        thread = Thread({ acceptLoop() }, "tmulator-bt-spp").apply {
            isDaemon = true
            start()
        }
    }

    @SuppressLint("MissingPermission") // Name restore is guarded by hasPermission().
    fun stop() {
        running = false
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { connected?.close() }
        connected = null
        thread?.interrupt()
        thread = null
        if (changedName && hasPermission()) {
            val original = originalName
            if (!original.isNullOrBlank()) runCatching { adapter?.setName(original) }
        }
        changedName = false
        originalName = null
        adapter = null
    }

    private fun acceptLoop() {
        val server = serverSocket ?: return
        while (running) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break
            } catch (_: Throwable) {
                break
            }
            if (connected != null) {
                runCatching { socket.close() }
                continue
            }
            connected = socket
            val peer = BluetoothPeer(
                id = nextId.getAndIncrement(),
                remoteAddress = safeRemoteName(socket),
                keyPrefix = "bt-$instanceToken",
                socket = socket,
            )
            listener.onPeerConnected(peer)
            Thread({ clientLoop(peer, socket) }, "tmulator-bt-client").apply {
                isDaemon = true
                start()
            }
        }
        isRunning = false
    }

    private fun clientLoop(peer: BluetoothPeer, socket: BluetoothSocket) {
        try {
            val input = socket.inputStream
            val buffer = ByteArray(8 * 1024)
            while (running) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) listener.onPeerData(peer, buffer.copyOf(read))
            }
        } catch (_: Throwable) {
        } finally {
            connected = null
            runCatching { socket.close() }
            listener.onPeerDisconnected(peer)
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeRemoteName(socket: BluetoothSocket): String {
        return try {
            val device = socket.remoteDevice
            val name = device?.name
            val address = device?.address
            if (!name.isNullOrBlank() && !address.isNullOrBlank()) "$name ($address)" else address ?: "Bluetooth client"
        } catch (_: Throwable) {
            "Bluetooth client"
        }
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
