package dev.tmulator.transport

import dev.tmulator.escpos.EscPosParser
import dev.tmulator.escpos.PrinterProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NetworkPrintServerTest {

    @Test
    fun `accepts a real TCP client and returns a status byte`() {
        val port = freePort()
        val listening = CountDownLatch(1)
        val connected = CountDownLatch(1)
        val dataLatch = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val received = AtomicReference<ByteArray>()
        val parser = EscPosParser(PrinterProfile.TM_M30)

        val listener = object : NetworkPrintServerListener {
            override fun onListening(port: Int) {
                listening.countDown()
            }

            override fun onListenError(message: String) {
                throw AssertionError(message)
            }

            override fun onPeerConnected(peer: TcpPeer) {
                connected.countDown()
            }

            override fun onPeerData(peer: TcpPeer, data: ByteArray) {
                received.set(data)
                val outcome = parser.feed(data)
                outcome.responses.forEach(peer::write)
                dataLatch.countDown()
            }

            override fun onPeerDisconnected(peer: TcpPeer) {
                disconnected.countDown()
            }
        }

        val server = NetworkPrintServer(port, listener)
        try {
            server.start()
            assertTrue("listener did not start", listening.await(4, TimeUnit.SECONDS))

            val payload = byteArrayOf(0x1B, 0x40, 0x48, 0x69, 0x0A, 0x10, 0x04, 0x01)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 4000
                socket.getOutputStream().write(payload)
                socket.getOutputStream().flush()
                val status = socket.getInputStream().read()
                assertEquals(0x16, status)
            }

            assertTrue("connection callback missing", connected.await(2, TimeUnit.SECONDS))
            assertTrue("data callback missing", dataLatch.await(2, TimeUnit.SECONDS))
            assertArrayEquals(payload, received.get())
            assertTrue("disconnect callback missing", disconnected.await(2, TimeUnit.SECONDS))
            assertTrue(parser.document.snapshot().plainText().contains("Hi"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `port conflict is reported as an error`() {
        val blocker = ServerSocket(0)
        val error = AtomicReference<String>()
        val latch = CountDownLatch(1)
        val listener = object : NetworkPrintServerListener {
            override fun onListening(port: Int) = Unit
            override fun onListenError(message: String) {
                error.set(message)
                latch.countDown()
            }
            override fun onPeerConnected(peer: TcpPeer) = Unit
            override fun onPeerData(peer: TcpPeer, data: ByteArray) = Unit
            override fun onPeerDisconnected(peer: TcpPeer) = Unit
        }
        val server = NetworkPrintServer(blocker.localPort, listener)
        try {
            server.start()
            assertTrue(latch.await(4, TimeUnit.SECONDS))
            assertTrue(error.get().contains("listen", ignoreCase = true))
        } finally {
            server.stop()
            blocker.close()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
