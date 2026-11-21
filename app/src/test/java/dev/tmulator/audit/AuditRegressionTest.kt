package dev.tmulator.audit

import android.content.ContextWrapper
import dev.tmulator.data.JobSource
import dev.tmulator.data.JobStore
import dev.tmulator.data.PrinterRuntime
import dev.tmulator.data.PrinterSettings
import dev.tmulator.escpos.*
import dev.tmulator.service.JobManager
import dev.tmulator.transport.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Expectations describe correct behavior; failures reproduce audit findings. */
class AuditRegressionTest {
    private fun b(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun parser() = EscPosParser(PrinterProfile.TM_M30)

    @Test fun unsupportedEscExtensionPreservesFollowingText() {
        val p = parser()
        p.feed(b(0x1b, 0x28, 0x41, 4, 0, 48, 1, 1, 10) + "OK\n".toByteArray())
        assertEquals("OK", p.document.snapshot().plainText())
    }

    @Test fun columnImageUsesMostSignificantBitForTopDot() {
        val image = MonochromeImage.fromColumns(1, 8, b(0x80))
        assertTrue("0x80 must light the top dot", image.isBlack(0, 0))
        assertFalse(image.isBlack(0, 7))
    }

    @Test fun escStarDoubleDensityDoesNotDoublePhysicalWidth() {
        val p = parser()
        p.feed(b(0x1b, 0x2a, 33, 1, 0, 0x80, 0, 0))
        val image = p.document.snapshot().blocks.filterIsInstance<ReceiptBlock.Image>().single().image
        assertEquals(1, image.width)
    }

    @Test fun escKDoesNotConsumeFollowingText() {
        val p = parser()
        p.feed(b(0x1b, 0x4b, 1, 0, 0x80) + "OK\n".toByteArray())
        assertEquals("OK", p.document.snapshot().plainText())
    }

    @Test fun gsRPresentsPaperSensorBitsRatherThanRealtimeStatusBits() {
        assertArrayEquals(b(0), parser().feed(b(0x1d, 0x72, 1)).responses.single())
    }

    @Test fun resetDiscardsIncompleteCommandBytes() {
        val p = parser()
        p.feed(b(0x1b, 0x45))
        p.resetDocument()
        p.feed("Hello\n".toByteArray())
        assertEquals("Hello", p.document.snapshot().plainText())
    }

    @Test fun commandCountDoesNotDependOnPacketFragmentation() {
        val command = b(0x1b, 0x45, 1)
        val whole = parser().apply { feed(command) }
        val split = parser().apply { command.forEach { feed(byteArrayOf(it)) } }
        assertEquals(whole.commandCount, split.commandCount)
    }

    @Test fun asciiJustificationParameterIsAccepted() {
        val p = parser()
        p.feed(b(0x1b, 0x61, 49) + "Centered\n".toByteArray())
        assertEquals(Align.CENTER, (p.document.snapshot().blocks.first() as ReceiptBlock.Text).align)
    }

    @Test fun absolutePositionDoesNotRewindAfterStyleChange() {
        val p = parser()
        p.feed(b(0x1b, 0x24, 100, 0) + "A".toByteArray() + b(0x1b, 0x45, 1) + "B\n".toByteArray())
        val runs = (p.document.snapshot().blocks.first() as ReceiptBlock.Text).runs
        assertEquals(100, runs[0].style.absoluteXDots)
        assertNull("The second run should continue after A", runs[1].style.absoluteXDots)
    }

    @Test fun persistentConnectionStartsAnotherJobAfterCut() {
        withManager { manager, runtime, peer ->
            manager.handleData(peer, "FIRST\n".toByteArray() + b(0x1d, 0x56, 0))
            manager.handleData(peer, "SECOND\n".toByteArray())
            assertNotNull("Second receipt needs an active job and a later save", runtime.state.value.activeJob)
        }
    }

    @Test fun clearAllowsPersistentConnectionToPrintAgain() {
        withManager { manager, runtime, peer ->
            manager.handleData(peer, "FIRST\n".toByteArray() + b(0x1d, 0x56, 0))
            manager.clearCurrent()
            manager.handleData(peer, "SECOND\n".toByteArray())
            assertNotNull("Clear must rearm the job lifecycle", runtime.state.value.activeJob)
        }
    }

    @Test fun tcpConnectionsHaveUniqueKeysAcrossServerInstances() {
        val first = connectOnce()
        val second = connectOnce()
        assertNotEquals("Old disconnect callbacks must not identify a new session", first, second)
    }

    @Test fun stoppedTcpListenerClosesItsBoundSocket() {
        val port = ServerSocket(0).use { it.localPort }
        val server = NetworkPrintServer(port, listener())
        server.start()
        server.stop()
        // Immediate stop may win the race before the worker binds. Either way,
        // no socket may remain bound and the port must be reusable.
        val field = NetworkPrintServer::class.java.getDeclaredField("serverSocket").apply { isAccessible = true }
        repeat(40) {
            val socket = field.get(server) as? ServerSocket
            if (socket == null || socket.isClosed) return@repeat
            Thread.sleep(25)
        }
        val socket = field.get(server) as? ServerSocket
        assertTrue("start/stop race left a bound socket after stop", socket == null || socket.isClosed)
        ServerSocket(port).use { rebound ->
            assertTrue(rebound.localPort == port)
        }
    }

    @Test fun parserTimingAndMemoryObservations() {
        // Observational, not an Android/device benchmark. Bound inputs keep the probe safe.
        fun elapsed(lineLength: Int, chunkSize: Int): Double {
            val p = parser()
            val chunk = ByteArray(chunkSize) { 65 }
            val start = System.nanoTime()
            repeat(lineLength / chunkSize) { p.feed(chunk) }
            return (System.nanoTime() - start) / 1e6
        }
        elapsed(16_384, 1024)
        for (size in listOf(65_536, 131_072, 262_144)) {
            println("unterminated_line_bytes=$size chunk_bytes=1024 elapsed_ms=${elapsed(size, 1024)}")
        }
        val p = parser()
        repeat(128) { p.feed(ByteArray(8192) { 65 }) }
        val retained = p.document.snapshot().blocks.filterIsInstance<ReceiptBlock.Text>().sumOf { line -> line.runs.sumOf { it.text.length } }
        println("unterminated_line_retained_characters=$retained blocks=${p.document.blockCount}")
        val raster = parser()
        // 8192 x 4096 dots: only 4 MiB on wire, but over the bounded input
        // budget. It must be rejected rather than expanded.
        raster.feed(b(0x1d, 0x76, 0x30, 0, 0, 4, 0, 16) + ByteArray(4 * 1024 * 1024))
        val images = raster.document.snapshot().blocks.filterIsInstance<ReceiptBlock.Image>()
        println("oversized_raster_accepted=${images.isNotEmpty()} blocks=${raster.document.blockCount}")
        assertTrue("oversized raster must be rejected", images.isEmpty())
    }

    private fun withManager(block: (JobManager, PrinterRuntime, PrintPeer) -> Unit) {
        val directory = Files.createTempDirectory("tmulator-audit-").toFile()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        val context = object : ContextWrapper(null) { override fun getFilesDir(): File = directory }
        val runtime = PrinterRuntime()
        val peer = object : PrintPeer {
            override val key = "net-audit"
            override val remoteAddress = "127.0.0.1"
            override fun write(bytes: ByteArray) = Unit
        }
        val manager = JobManager(runtime, JobStore(context, scope), { PrinterSettings() }, scope)
        try { manager.begin(peer, JobSource.NETWORK); block(manager, runtime, peer) }
        finally { scope.cancel(); File(directory, "jobs").delete(); directory.delete() }
    }

    private fun connectOnce(): String {
        val ready = CountDownLatch(1)
        val connected = CountDownLatch(1)
        val key = AtomicReference<String>()
        val port = ServerSocket(0).use { it.localPort }
        val server = NetworkPrintServer(port, listener(
            onListening = { ready.countDown() },
            onConnected = { key.set(it.key); connected.countDown() },
        ))
        try {
            server.start()
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            Socket("127.0.0.1", port).use { assertTrue(connected.await(2, TimeUnit.SECONDS)) }
            return key.get()
        } finally { server.stop() }
    }

    private fun listener(onListening: () -> Unit = {}, onConnected: (TcpPeer) -> Unit = {}) =
        object : NetworkPrintServerListener {
            override fun onListening(port: Int) = onListening()
            override fun onListenError(message: String) = throw AssertionError(message)
            override fun onPeerConnected(peer: TcpPeer) = onConnected(peer)
            override fun onPeerData(peer: TcpPeer, data: ByteArray) = Unit
            override fun onPeerDisconnected(peer: TcpPeer) = Unit
        }
}
