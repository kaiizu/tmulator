package dev.tmulator.transport

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale

data class NetworkIdentity(
    val mac: ByteArray,
    val ip: ByteArray,
    val netmask: ByteArray,
    val gateway: ByteArray,
)

/**
 * ENPC device discovery (UDP 3289). Epson's SDKs broadcast short "EPSONQ"
 * queries; the device answers with "EPSONq" and a function-specific payload.
 * These are the five query functions used by Epson utilities/SDKs.
 */
object EnpcProtocol {
    private val QUERY_HEADER = byteArrayOf(0x45, 0x50, 0x53, 0x4F, 0x4E, 0x51) // EPSONQ
    private val COMMAND_HEADER = byteArrayOf(0x45, 0x50, 0x53, 0x4F, 0x4E, 0x43) // EPSONC

    /**
     * Epson's print client holds the printer before sending a job with
     * `EPSONC 03 00 00 15` and releases it with `03 00 00 16`. Both expect a
     * normal-end result, otherwise the client retries for several seconds
     * before printing.
     */
    fun buildCommandResponse(query: ByteArray): ByteArray? {
        if (query.size < 14) return null
        for (i in COMMAND_HEADER.indices) {
            if (query[i] != COMMAND_HEADER[i]) return null
        }
        val functionHigh = query[8].toInt() and 0xFF
        val functionLow = query[9].toInt() and 0xFF
        val supported = (functionHigh == 0x00 && (functionLow == 0x15 || functionLow == 0x16))
        if (!supported) return null
        val out = ByteArray(14)
        COMMAND_HEADER.copyInto(out, 0)
        out[5] = 'c'.code.toByte()
        query.copyInto(out, 6, 6, 10)
        // Result code 0x0000 (normal end), empty reply payload.
        out[10] = 0
        out[11] = 0
        out[12] = 0
        out[13] = 0
        return out
    }

    fun buildResponse(
        query: ByteArray,
        deviceName: String,
        identity: NetworkIdentity,
        holderIp: ByteArray?,
    ): ByteArray? {
        if (query.size < 10) return null
        for (i in QUERY_HEADER.indices) {
            if (query[i] != QUERY_HEADER[i]) return null
        }
        val function = query.copyOfRange(6, 10)
        val payload = when {
            function.contentEquals(byteArrayOf(3, 0, 0, 0)) -> deviceNamePayload(deviceName)
            function.contentEquals(byteArrayOf(0, 0, 0, 0)) -> broadcastPayload(identity)
            function.contentEquals(byteArrayOf(0, 0, 0, 0x10)) -> networkPayload(identity)
            function.contentEquals(byteArrayOf(3, 0, 0, 0x10)) -> byteArrayOf(
                0x0E, 0x14, 0x00, 0x00, 0x0F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0x39, 0x41, 0x40, 0x00,
            )
            function.contentEquals(byteArrayOf(3, 0, 0, 0x17)) -> holderIp ?: ByteArray(4)
            else -> return null
        }
        val out = ByteArray(14 + payload.size)
        QUERY_HEADER.copyInto(out, 0)
        out[5] = 'q'.code.toByte()
        function.copyInto(out, 6)
        writeInt(out, 10, payload.size)
        payload.copyInto(out, 14)
        return out
    }

    private fun deviceNamePayload(name: String): ByteArray {
        val payload = ByteArray(133)
        val prefix = byteArrayOf(0x00, 0x05, 0x01, 0x02, 0x01)
        prefix.copyInto(payload, 0)
        val ascii = name.toByteArray(Charsets.US_ASCII).take(127).toByteArray()
        ascii.copyInto(payload, prefix.size)
        return payload
    }

    private fun broadcastPayload(identity: NetworkIdentity): ByteArray {
        val payload = ByteArray(54)
        val serial = "UB-EEAE083ENSN".toByteArray(Charsets.US_ASCII)
        serial.copyInto(payload, 0)
        // Device/network flags observed from a TM-m30 broadcast.
        val tail = byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0x15, 0x00, 0x02, 0x00)
        tail.copyInto(payload, 33)
        identity.mac.take(6).toByteArray().copyInto(payload, 40)
        byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01).copyInto(payload, 46)
        return payload
    }

    private fun networkPayload(identity: NetworkIdentity): ByteArray {
        val out = ByteArray(23)
        out[0] = 0x01
        identity.mac.take(6).toByteArray().copyInto(out, 1)
        out[7] = 0x00
        out[8] = 0x04
        identity.ip.take(4).toByteArray().copyInto(out, 9)
        identity.netmask.take(4).toByteArray().copyInto(out, 13)
        identity.gateway.take(4).toByteArray().copyInto(out, 17)
        out[21] = 0x80.toByte()
        out[22] = 0x7C
        return out
    }

    private fun writeInt(out: ByteArray, offset: Int, value: Int) {
        out[offset] = ((value ushr 24) and 0xFF).toByte()
        out[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 3] = (value and 0xFF).toByte()
    }

    fun describe(query: ByteArray): String {
        if (query.size < 10) return "short packet"
        val function = query.copyOfRange(6, 10).joinToString("") {
            String.format(Locale.US, "%02X", it.toInt() and 0xFF)
        }
        return "f=$function size=${query.size}"
    }

    fun describeCommand(query: ByteArray): String {
        if (query.size < 14) return "short packet"
        val function = query.copyOfRange(8, 10).joinToString("") {
            String.format(Locale.US, "%02X", it.toInt() and 0xFF)
        }
        val length = ((query[12].toInt() and 0xFF) shl 8) or (query[13].toInt() and 0xFF)
        return "f=$function len=$length size=${query.size}"
    }
}

class EnpcDiscoveryResponder(
    private val identity: () -> NetworkIdentity?,
    private val holderIp: () -> ByteArray?,
    private val deviceName: () -> String,
    private val log: (String, String?) -> Unit,
    private val onListening: () -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    @Volatile private var running = false
    private val lifecycleLock = Any()
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Volatile var isRunning: Boolean = false
        private set

    fun start(port: Int = 3289) {
        synchronized(lifecycleLock) {
            if (running) return
            running = true
        }
        thread = Thread({
            var local: DatagramSocket? = null
            try {
                val candidate = DatagramSocket(null)
                candidate.reuseAddress = true
                candidate.broadcast = true
                synchronized(lifecycleLock) {
                    if (!running) {
                        runCatching { candidate.close() }
                        return@Thread
                    }
                    candidate.bind(java.net.InetSocketAddress(port))
                    local = candidate
                    socket = candidate
                    isRunning = true
                }
                onListening()
                log("ENPC discovery listening", "UDP $port")
                val buffer = ByteArray(2048)
                var unknown = 0
                var loggedQueries = 0
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        candidate.receive(packet)
                    } catch (_: Throwable) {
                        if (!running) break else continue
                    }
                    val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    if (query.startsWithAscii("EPSONC")) {
                        val commandResponse = EnpcProtocol.buildCommandResponse(query)
                        if (commandResponse != null) {
                            log("ENPC command", EnpcProtocol.describeCommand(query))
                            try {
                                candidate.send(
                                    DatagramPacket(
                                        commandResponse,
                                        commandResponse.size,
                                        packet.address,
                                        packet.port,
                                    ),
                                )
                            } catch (_: Throwable) {
                            }
                        } else {
                            log("ENPC command ignored", EnpcProtocol.describeCommand(query))
                        }
                        continue
                    }
                    val identityValue = identity()
                    if (identityValue == null) continue
                    val holder = holderIp()
                    if (query.size >= 10 &&
                        query[6] == 0x03.toByte() && query[7] == 0x00.toByte() &&
                        query[8] == 0x00.toByte() && query[9] == 0x17.toByte()
                    ) {
                        val text = holder?.joinToString(".") { (it.toInt() and 0xFF).toString() } ?: "0.0.0.0"
                        log("ENPC holder reply", "${packet.address.hostAddress} -> $text")
                    }
                    val response = EnpcProtocol.buildResponse(
                        query,
                        deviceName(),
                        identityValue,
                        holder,
                    )
                    if (response != null) {
                        if (loggedQueries++ < 64) {
                            log("ENPC query", "${packet.address.hostAddress} ${EnpcProtocol.describe(query)}")
                        }
                        try {
                            candidate.send(DatagramPacket(response, response.size, packet.address, packet.port))
                        } catch (_: Throwable) {
                        }
                    } else if (unknown < 20 && query.startsWithAscii("EPSONQ")) {
                        unknown++
                        log("ENPC query ignored", EnpcProtocol.describe(query))
                    }
                }
            } catch (t: Throwable) {
                if (running) {
                    log("ENPC discovery failed", t.message ?: t.javaClass.simpleName)
                    onError(t.message ?: t.javaClass.simpleName)
                }
            } finally {
                val bound = local
                synchronized(lifecycleLock) {
                    if (socket === bound) socket = null
                    isRunning = false
                    running = false
                }
                runCatching { bound?.close() }
            }
        }, "tmulator-enpc").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        val socketToClose: DatagramSocket?
        val worker: Thread?
        synchronized(lifecycleLock) {
            running = false
            socketToClose = socket
            socket = null
            isRunning = false
            worker = thread
            thread = null
        }
        runCatching { socketToClose?.close() }
        worker?.interrupt()
    }

    private fun ByteArray.startsWithAscii(value: String): Boolean {
        if (size < value.length) return false
        for (i in value.indices) if (this[i] != value[i].code.toByte()) return false
        return true
    }
}
