package dev.tmulator.transport

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class TcpPeer internal constructor(
    val id: Long,
    override val remoteAddress: String,
    private val keyPrefix: String,
    private val writer: (ByteArray) -> Unit,
) : PrintPeer {
    override val key: String get() = "$keyPrefix-$id"

    override fun write(bytes: ByteArray) {
        if (bytes.isNotEmpty()) writer(bytes)
    }
}

interface NetworkPrintServerListener {
    fun onListening(port: Int)
    fun onListenError(message: String)
    fun onPeerConnected(peer: TcpPeer)
    fun onPeerData(peer: TcpPeer, data: ByteArray)
    fun onPeerDisconnected(peer: TcpPeer)
}

/**
 * Raw ESC/POS TCP printer endpoint. Pure JVM so the real loopback path can be
 * exercised by unit tests.
 */
class NetworkPrintServer(
    private val port: Int,
    private val listener: NetworkPrintServerListener,
    private val maxClients: Int = 8,
) {
    @Volatile private var running = false
    @Volatile private var active = false
    private val lifecycleLock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = ConcurrentHashMap<Long, Socket>()
    private val nextId = AtomicLong(1)
    private val instanceToken = UUID.randomUUID().toString().take(8)

    val isRunning: Boolean get() = active

    fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            running = true
        }
        acceptThread = Thread({ acceptLoop() }, "tmulator-tcp-$port").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        val socket: ServerSocket?
        val thread: Thread?
        synchronized(lifecycleLock) {
            running = false
            socket = serverSocket
            serverSocket = null
            active = false
            thread = acceptThread
            acceptThread = null
        }
        runCatching { socket?.close() }
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        thread?.interrupt()
    }

    private fun acceptLoop() {
        var socket: ServerSocket? = null
        try {
            val candidate = ServerSocket()
            candidate.reuseAddress = true
            synchronized(lifecycleLock) {
                if (!running) {
                    runCatching { candidate.close() }
                    return
                }
                socket = candidate
                candidate.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = candidate
                active = true
            }
            listener.onListening(port)
            while (running) {
                val client = try {
                    candidate.accept()
                } catch (e: SocketException) {
                    if (!running) break else continue
                } catch (e: IOException) {
                    if (running) listener.onListenError("accept failed: ${e.message}")
                    break
                }
                if (clients.size >= maxClients) {
                    runCatching { client.close() }
                    continue
                }
                configure(client)
                val id = nextId.getAndIncrement()
                clients[id] = client
                val peer = TcpPeer(
                    id = id,
                    remoteAddress = client.inetAddress?.hostAddress ?: "unknown",
                    keyPrefix = "net-$instanceToken",
                ) { bytes ->
                    writeTo(client, bytes)
                }
                Thread({ clientLoop(client, peer) }, "tmulator-client-$id").apply {
                    isDaemon = true
                    start()
                }
            }
        } catch (e: Throwable) {
            if (running) {
                listener.onListenError("Cannot listen on TCP $port: ${e.message ?: e.javaClass.simpleName}")
            }
        } finally {
            val bound = socket
            synchronized(lifecycleLock) {
                if (serverSocket === bound) serverSocket = null
                active = false
                running = false
            }
            runCatching { bound?.close() }
        }
    }

    private fun configure(socket: Socket) {
        runCatching { socket.tcpNoDelay = true }
        runCatching { socket.keepAlive = true }
        runCatching { socket.receiveBufferSize = 64 * 1024 }
    }

    private fun clientLoop(socket: Socket, peer: TcpPeer) {
        listener.onPeerConnected(peer)
        try {
            val input = socket.getInputStream()
            val buffer = ByteArray(16 * 1024)
            while (running) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    listener.onPeerData(peer, buffer.copyOf(read))
                }
            }
        } catch (_: Throwable) {
            // The peer vanished or the server was stopped.
        } finally {
            clients.remove(peer.id)
            runCatching { socket.close() }
            listener.onPeerDisconnected(peer)
        }
    }

    private fun writeTo(socket: Socket, bytes: ByteArray) {
        try {
            val output = socket.getOutputStream()
            synchronized(socket) {
                output.write(bytes)
                output.flush()
            }
        } catch (_: Throwable) {
            // A status reply racing with a disconnect is not fatal.
        }
    }
}
