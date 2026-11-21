package dev.tmulator.service

import dev.tmulator.data.ActiveJobInfo
import dev.tmulator.data.JobSource
import dev.tmulator.data.JobStore
import dev.tmulator.data.PrintJobSummary
import dev.tmulator.data.PrinterRuntime
import dev.tmulator.data.PrinterSettings
import dev.tmulator.data.RuntimeLogLevel
import dev.tmulator.escpos.EscPosParser
import dev.tmulator.escpos.PrinterStatus
import dev.tmulator.escpos.ReceiptSnapshot
import dev.tmulator.render.ReceiptRenderer
import dev.tmulator.render.RenderOptions
import dev.tmulator.transport.PrintPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * One parser per client, a throttled live renderer and per-receipt persistence.
 * A cut completes the current receipt segment; the parser keeps the printer
 * configuration and starts a fresh document for the next receipt on the same
 * connection. All rendering is serialized on one background dispatcher.
 */
class JobManager(
    private val runtime: PrinterRuntime,
    private val jobStore: JobStore,
    private val settings: () -> PrinterSettings,
    private val scope: CoroutineScope,
) {
    private inner class Session(
        val peer: PrintPeer,
        val source: JobSource,
        val profileName: String,
        val parser: EscPosParser,
    ) {
        var jobId: Long = jobStore.newId()
        var jobStartedAt: Long = System.currentTimeMillis()
        var jobBytes: Long = 0
        var bytes: Long = 0
        var lastRenderAt: Long = 0
        var renderScheduled: Boolean = false
        var lastPreviewRevision: Long = -1
        var lastActivityAt: Long = jobStartedAt
        var lastDataAt: Long = 0
        var finishing: Boolean = false
        var hasRenderableContent: Boolean = false
        var epoch: Long = 0
        var loggedChunks: Int = 0
        var loggedTx: Int = 0
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val renderer = ReceiptRenderer()
    private val loggedWarnings = HashSet<String>()
    private val renderScope = CoroutineScope(scope.coroutineContext + Dispatchers.Default.limitedParallelism(1))

    @Volatile private var displayEpoch: Long = 0

    fun begin(peer: PrintPeer, source: JobSource): Unit = synchronized(this) {
        if (sessions.containsKey(peer.key)) return
        val config = settings()
        val profile = config.profile
        val parser = EscPosParser(
            profile = profile,
            statusProvider = {
                val s = settings()
                PrinterStatus(
                    paperOut = s.paperOutSimulated,
                    coverOpen = s.coverOpenSimulated,
                )
            },
            verboseLogging = config.verboseLogging,
        )
        val session = Session(
            peer = peer,
            source = source,
            profileName = profile.displayName,
            parser = parser,
        )
        sessions[peer.key] = session
        runtime.log(
            RuntimeLogLevel.DEBUG,
            if (source == JobSource.NETWORK) "NET" else "BT",
            "Client connected",
            peer.remoteAddress,
        )
    }

    fun handleData(peer: PrintPeer, data: ByteArray) {
        val session = sessions[peer.key] ?: run {
            begin(peer, sourceOf(peer))
            sessions[peer.key]
        } ?: return

        val now = System.currentTimeMillis()
        var gapMs = 0L
        val pair = synchronized(session) {
            gapMs = if (session.lastDataAt == 0L) 0L else now - session.lastDataAt
            session.lastDataAt = now
            session.bytes += data.size
            session.jobBytes += data.size
            session.lastActivityAt = now
            val outcome = session.parser.feed(data)
            outcome to session.parser.document.snapshot()
        }
        val outcome = pair.first
        val snapshot = pair.second

        outcome.responses.forEach { response ->
            if (session.loggedTx < 16) {
                runtime.log(
                    RuntimeLogLevel.DEBUG,
                    if (session.source == JobSource.NETWORK) "NET/TX" else "BT/TX",
                    "TX ${response.size} bytes",
                    response.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
                )
                session.loggedTx++
            }
            peer.write(response)
        }
        outcome.logs.forEach { runtime.log(it, if (session.source == JobSource.NETWORK) "NET/ESC" else "BT/ESC") }

        if (session.loggedChunks < 8 || settings().verboseLogging) {
            val sourceLabel = if (session.source == JobSource.NETWORK) "NET" else "BT"
            runtime.log(
                RuntimeLogLevel.DEBUG,
                sourceLabel,
                "RX ${data.size} bytes",
                "+${gapMs}ms · " +
                    data.take(160).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } +
                    if (data.size > 160) " …" else "",
            )
            session.loggedChunks++
        }

        if (outcome.completed.isNotEmpty()) {
            val completedJobs = ArrayList<CompletedJob>(outcome.completed.size)
            synchronized(session) {
                outcome.completed.forEach { completed ->
                    runtime.noteCut()
                    runtime.log(
                        RuntimeLogLevel.INFO,
                        if (session.source == JobSource.NETWORK) "NET" else "BT",
                        "Paper cut",
                        "Receipt has ${completed.cutCount} cut marker${if (completed.cutCount == 1) "" else "s"}",
                    )
                    completedJobs += CompletedJob(
                        snapshot = completed,
                        id = session.jobId,
                        startedAt = session.jobStartedAt,
                        bytes = session.jobBytes,
                    )
                    session.jobId = jobStore.newId()
                    session.jobStartedAt = System.currentTimeMillis()
                    session.jobBytes = 0
                    session.hasRenderableContent = false
                    session.lastPreviewRevision = -1
                }
            }
            completedJobs.forEach { job ->
                finalizeJob(session, job.snapshot, job.id, job.startedAt, job.bytes)
            }
        }

        if (!snapshot.isEmpty) {
            if (!session.hasRenderableContent) {
                session.hasRenderableContent = true
                runtime.log(
                    RuntimeLogLevel.INFO,
                    if (session.source == JobSource.NETWORK) "NET" else "BT",
                    "Print job started",
                    session.peer.remoteAddress,
                )
            }
            requestPreview(session, force = false)
        }
        publishActive()
    }

    fun end(peer: PrintPeer) {
        val session = sessions.remove(peer.key) ?: return
        val snapshot = synchronized(session) {
            session.finishing = true
            session.parser.finish()
            session.parser.document.snapshot()
        }
        publishActive()

        if (snapshot.isEmpty) {
            runtime.log(
                RuntimeLogLevel.DEBUG,
                if (session.source == JobSource.NETWORK) "NET" else "BT",
                "Status connection closed",
                "${session.bytes} bytes, no printable content",
            )
            return
        }
        finalizeJob(session, snapshot, session.jobId, session.jobStartedAt, session.jobBytes)
    }

    /**
     * Renders and persists one completed receipt segment. Called once at each
     * cut and once at disconnect for the remaining uncut segment.
     */
    private fun finalizeJob(
        session: Session,
        snapshot: ReceiptSnapshot,
        jobId: Long,
        startedAt: Long,
        bytes: Long,
    ) {
        val epoch = displayEpoch
        renderScope.launch {
            val renderResult = try {
                renderer.render(snapshot, RenderOptions())
            } catch (t: Throwable) {
                runtime.log(
                    RuntimeLogLevel.ERROR,
                    "RENDER",
                    "Could not render receipt",
                    t.message ?: t.javaClass.simpleName,
                )
                null
            }
            if (renderResult != null) {
                val anotherJobActive = sessions.values.any { other ->
                    other !== session && synchronized(other) {
                        !other.finishing && other.parser.document.blockCount > 0
                    }
                }
                if (!anotherJobActive && displayEpoch == epoch) {
                    runtime.showPreview(renderResult.bitmap, snapshot, job = null)
                }
                renderResult.warnings.forEach { warn ->
                    if (loggedWarnings.add(warn)) runtime.log(RuntimeLogLevel.WARN, "RENDER", warn)
                }
            }

            val summary = PrintJobSummary(
                id = jobId,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                source = session.source,
                remote = session.peer.remoteAddress,
                bytes = bytes,
                blocks = snapshot.blocks.size,
                hadCut = snapshot.cutCount > 0,
                previewFile = null,
                previewWidth = renderResult?.bitmap?.width ?: 0,
                previewHeight = renderResult?.bitmap?.height ?: 0,
                textPreview = snapshot.plainText().take(500),
                profileName = session.profileName,
                unsupportedCommands = session.parser.unsupportedCount,
            )
            if (renderResult == null) return@launch
            try {
                jobStore.add(summary, renderResult.bitmap)
                runtime.incrementJobs()
                runtime.log(
                    RuntimeLogLevel.INFO,
                    if (session.source == JobSource.NETWORK) "NET" else "BT",
                    "Print job saved",
                    "${snapshot.blocks.size} blocks · $bytes bytes",
                )
            } catch (t: Throwable) {
                runtime.log(
                    RuntimeLogLevel.ERROR,
                    "STORE",
                    "Could not save receipt",
                    t.message ?: t.javaClass.simpleName,
                )
            }
        }
    }

    /** Dismisses the visible receipt without touching any client's parser. */
    fun clearCurrent() {
        displayEpoch++
        runtime.clearReceipt()
        runtime.log(RuntimeLogLevel.INFO, "SYS", "Receipt cleared")
    }

    /** Protocol reset: discard pending bytes and restart the current receipts. */
    fun resetAll() {
        displayEpoch++
        synchronized(this) {
            sessions.values.forEach { session ->
                synchronized(session) {
                    session.parser.resetDocument()
                    session.jobId = jobStore.newId()
                    session.jobStartedAt = System.currentTimeMillis()
                    session.jobBytes = 0
                    session.lastPreviewRevision = -1
                    session.hasRenderableContent = false
                    session.epoch++
                }
            }
        }
        runtime.clearReceipt()
        runtime.setActiveJob(null)
        runtime.log(RuntimeLogLevel.INFO, "SYS", "Virtual printer reset")
    }

    /** Sends the current ASB block to clients that enabled ASB. */
    fun broadcastAsb() {
        sessions.values.forEach { session ->
            synchronized(session) {
                if (session.parser.asbActive) {
                    runCatching { session.peer.write(session.parser.currentAsb()) }
                }
            }
        }
    }

    fun finishAll() {
        val peers = sessions.values.map { it.peer }
        peers.forEach { end(it) }
    }

    private fun requestPreview(session: Session, force: Boolean) {
        var delayed = false
        var revision = 0L
        var sessionEpoch = 0L
        var display = 0L
        val now = System.currentTimeMillis()
        synchronized(session) {
            if (session.finishing) return
            revision = session.parser.document.revision
            if (!force && revision == session.lastPreviewRevision) return
            sessionEpoch = session.epoch
            display = displayEpoch
            if (!force && now - session.lastRenderAt < PREVIEW_INTERVAL_MS) {
                if (session.renderScheduled) return
                session.renderScheduled = true
                delayed = true
            } else {
                session.lastRenderAt = now
            }
        }
        renderScope.launch {
            if (delayed) delay(PREVIEW_INTERVAL_MS)
            val canceled = synchronized(session) {
                if (session.finishing || sessions[session.peer.key] !== session ||
                    sessionEpoch != session.epoch || display != displayEpoch
                ) {
                    session.renderScheduled = false
                    true
                } else {
                    session.renderScheduled = false
                    session.lastRenderAt = System.currentTimeMillis()
                    false
                }
            }
            if (canceled) return@launch

            val snapshot = synchronized(session) { session.parser.document.snapshot() }
            if (snapshot.isEmpty) return@launch
            try {
                val result = renderer.render(snapshot, RenderOptions())
                if (session.finishing || sessions[session.peer.key] !== session ||
                    sessionEpoch != session.epoch || display != displayEpoch
                ) {
                    return@launch
                }
                val info = info(session, snapshot)
                runtime.showPreview(result.bitmap, snapshot, info)
                session.lastPreviewRevision = snapshot.revision
                result.warnings.forEach { warn ->
                    if (loggedWarnings.add(warn)) runtime.log(RuntimeLogLevel.WARN, "RENDER", warn)
                }
            } catch (t: Throwable) {
                runtime.log(RuntimeLogLevel.ERROR, "RENDER", "Preview failed", t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun publishActive() {
        val mostRecent = sessions.values
            .filter { session ->
                !session.finishing && synchronized(session) { session.parser.document.blockCount > 0 }
            }
            .maxByOrNull { it.lastActivityAt }
        if (mostRecent == null) {
            runtime.setActiveJob(null)
            return
        }
        val snapshot = synchronized(mostRecent) { mostRecent.parser.document.snapshot() }
        runtime.setActiveJob(info(mostRecent, snapshot))
    }

    private fun info(session: Session, snapshot: ReceiptSnapshot): ActiveJobInfo = ActiveJobInfo(
        id = session.jobId,
        source = session.source,
        remote = session.peer.remoteAddress,
        startedAt = session.jobStartedAt,
        bytes = session.jobBytes,
        commands = session.parser.commandCount,
        unsupported = session.parser.unsupportedCount,
        blocks = snapshot.blocks.size,
        hasCut = snapshot.cutCount > 0,
    )

    private fun sourceOf(peer: PrintPeer): JobSource =
        if (peer.key.startsWith("bt-")) JobSource.BLUETOOTH else JobSource.NETWORK

    private data class CompletedJob(
        val snapshot: ReceiptSnapshot,
        val id: Long,
        val startedAt: Long,
        val bytes: Long,
    )

    companion object {
        private const val PREVIEW_INTERVAL_MS = 140L
    }
}
