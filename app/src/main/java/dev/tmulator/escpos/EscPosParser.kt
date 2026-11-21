package dev.tmulator.escpos

import java.util.Locale

enum class LogSeverity { DEBUG, INFO, WARN, ERROR }

data class EscPosLog(
    val severity: LogSeverity,
    val title: String,
    val detail: String? = null,
    val hex: String? = null,
)

data class ParseOutcome(
    val revision: Long,
    val responses: List<ByteArray>,
    val logs: List<EscPosLog>,
    val completed: List<ReceiptSnapshot> = emptyList(),
)

/**
 * Incremental ESC/POS interpreter. Feed arbitrary TCP/Bluetooth chunks; commands
 * split across reads are buffered until complete. Rendering state is exposed as
 * a [ReceiptDocument].
 */
class EscPosParser(
    private val profile: PrinterProfile,
    private val statusProvider: () -> PrinterStatus = { PrinterStatus() },
    private val verboseLogging: Boolean = false,
    private val logger: ((EscPosLog) -> Unit)? = null,
) {
    private inner class State {
        var align = Align.LEFT
        var font = FontStyle.A
        var bold = false
        var underline = false
        var doubleStrike = false
        var reverse = false
        var italic = false
        var widthMultiplier = 1
        var heightMultiplier = 1
        var characterSpacing = 0
        var lineSpacing = 30
        var codePage = profile.defaultCodePage
        var barcodeHeight = 162
        var barcodeWidth = 2
        var hri = HriPosition.NONE
        var hriFont = FontStyle.A
        var asbMask = 0
        var qrModuleSize = 4
        var qrErrorCorrection = QrErrorCorrection.M
        var absoluteX: Int? = null
        var tabStops = mutableListOf<Int>()

        fun textStyle() = TextStyle(
            font = font,
            bold = bold,
            underline = underline,
            doubleStrike = doubleStrike,
            reverse = reverse,
            italic = italic,
            widthMultiplier = widthMultiplier,
            heightMultiplier = heightMultiplier,
            characterSpacingDots = characterSpacing,
            absoluteXDots = absoluteX,
        )

        fun reset() {
            align = Align.LEFT
            font = FontStyle.A
            bold = false
            underline = false
            doubleStrike = false
            reverse = false
            italic = false
            widthMultiplier = 1
            heightMultiplier = 1
            characterSpacing = 0
            lineSpacing = 30
            codePage = profile.defaultCodePage
            barcodeHeight = 162
            barcodeWidth = 2
            hri = HriPosition.NONE
            hriFont = FontStyle.A
            asbMask = 0
            qrModuleSize = 4
            qrErrorCorrection = QrErrorCorrection.M
            absoluteX = null
            tabStops.clear()
        }
    }

    val document = ReceiptDocument(profile.paperWidthDots)
    private val state = State()

    private var buffer = ByteArray(16 * 1024)
    private var length = 0
    private var discardRemaining = 0L
    private var overflowLogged = false
    private val responses = ArrayList<ByteArray>()
    private val logs = ArrayList<EscPosLog>()
    private val completedReceipts = ArrayList<ReceiptSnapshot>()

    private val lineRuns = ArrayList<TextRun>()
    private val text = StringBuilder()
    private var textStyle: TextStyle? = null
    private var columnChars = 0
    private var qrData: String? = null

    var commandCount = 0
        private set
    var unsupportedCount = 0
        private set
    var parsedBytes = 0L
        private set

    /** True while the host has ASB enabled; used to pace status updates. */
    val asbActive: Boolean get() = state.asbMask != 0

    /** Current 4-byte ASB status block (header 0x14). */
    fun currentAsb(): ByteArray = asbBytes()

    fun feed(data: ByteArray, offset: Int = 0, count: Int = data.size): ParseOutcome {
        responses.clear()
        logs.clear()
        completedReceipts.clear()
        parsedBytes += count
        var sourceOffset = offset
        var sourceCount = count
        if (discardRemaining > 0 && sourceCount > 0) {
            val drop = minOf(sourceCount.toLong(), discardRemaining).toInt()
            discardRemaining -= drop
            sourceOffset += drop
            sourceCount -= drop
        }
        if (sourceCount > 0) append(data, sourceOffset, sourceCount)

        var index = 0
        while (index < length) {
            val commandsBefore = commandCount
            val consumed = parseAt(index)
            if (consumed <= 0) {
                // A partial command was counted by its handler before it could
                // be consumed. Count commands only once they are complete.
                commandCount = commandsBefore
                break
            }
            index += consumed
        }
        if (index > 0) {
            System.arraycopy(buffer, index, buffer, 0, length - index)
            length -= index
        }

        updatePendingLine()
        if (document.overflowed && !overflowLogged) {
            log(WARN, "Receipt content limit reached", "Text, image or block budget exceeded")
            overflowLogged = true
        }
        return ParseOutcome(
            revision = document.revision,
            responses = responses.toList(),
            logs = logs.toList(),
            completed = completedReceipts.toList(),
        )
    }

    /** Flushes a partial line so a preview can be rendered before the next LF. */
    fun finish() {
        flushTextRun()
        updatePendingLine()
    }

    fun resetDocument() {
        document.clear()
        state.reset()
        lineRuns.clear()
        text.clear()
        textStyle = null
        columnChars = 0
        qrData = null
        length = 0
        discardRemaining = 0
        responses.clear()
        logs.clear()
        completedReceipts.clear()
        commandCount = 0
        unsupportedCount = 0
        parsedBytes = 0
        overflowLogged = false
    }

    // ------------------------------------------------------------------ buffer

    private fun append(data: ByteArray, offset: Int, count: Int) {
        if (count <= 0) return
        if (length + count > MAX_BUFFER_BYTES) {
            // A client is streaming an unbounded job or a bogus length. Drop the
            // oldest bytes rather than risk an OOM; keep parsing what remains.
            log(WARN, "Receive buffer limit reached", "${MAX_BUFFER_BYTES / 1024 / 1024} MB discarded")
            val keep = minOf(length, MAX_BUFFER_BYTES / 2)
            System.arraycopy(buffer, length - keep, buffer, 0, keep)
            length = keep
            if (count > MAX_BUFFER_BYTES - length) return
        }
        if (length + count > buffer.size) {
            var newSize = buffer.size
            while (newSize < length + count) newSize *= 2
            buffer = buffer.copyOf(minOf(newSize, MAX_BUFFER_BYTES))
        }
        System.arraycopy(data, offset, buffer, length, count)
        length += count
    }

    private fun available(index: Int): Int = length - index
    private fun u8(index: Int): Int = buffer[index].toInt() and 0xFF
    private fun u16(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

    /**
     * Consume a frame whose declared payload is valid but too large to keep.
     * Remaining payload bytes are dropped from subsequent feeds instead of
     * being reinterpreted as text/commands.
     */
    private fun discardFrame(index: Int, headerSize: Int, payload: Long): Int {
        val availableNow = available(index).toLong()
        if (availableNow < headerSize) return 0
        val total = headerSize + payload
        if (availableNow >= total) return total.toInt()
        discardRemaining = minOf(total - availableNow, MAX_DISCARD_BYTES)
        return availableNow.toInt()
    }
    private fun u32(index: Int): Long =
        (u8(index).toLong()) or
            (u8(index + 1).toLong() shl 8) or
            (u8(index + 2).toLong() shl 16) or
            (u8(index + 3).toLong() shl 24)

    private fun hex(index: Int, count: Int): String {
        val end = minOf(index + count, length)
        val sb = StringBuilder()
        for (i in index until end) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(String.format(Locale.US, "%02X", buffer[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    // --------------------------------------------------------------- tokenizer

    private fun parseAt(index: Int): Int {
        val b = u8(index)
        if (b >= 0x20 && b != 0x7F) {
            var end = index
            while (end < length) {
                val v = u8(end)
                if (v < 0x20 || v == 0x7F) break
                end++
            }
            appendText(state.codePage.decode(buffer, index, end - index))
            return end - index
        }

        return when (b) {
            0x09 -> { horizontalTab(); 1 }
            0x0A -> { lineFeed(); 1 }
            0x0C -> { formFeed(); 1 }
            0x0D -> 1
            0x18 -> { cancelLine(); 1 }
            0x07 -> { log(DEBUG, "Buzzer"); 1 }
            0x00, 0x11, 0x13, 0x1F -> 1
            0x1B -> parseEsc(index)
            0x1D -> parseGs(index)
            0x1C -> parseFs(index)
            0x10 -> parseDle(index)
            else -> {
                log(WARN, "Unknown control byte", hex = String.format(Locale.US, "%02X", b))
                1
            }
        }
    }

    private fun parseDle(index: Int): Int {
        if (available(index) < 2) return 0
        val cmd = u8(index + 1)
        return when (cmd) {
            0x04 -> {
                if (available(index) < 3) return 0
                val n = u8(index + 2)
                // DLE EOT 18 (interface status) takes an extra parameter byte.
                val withArg = n == 7 || n == 8 || n == 18
                if (withArg && available(index) < 4) return 0
                commandCount++
                responses += byteArrayOf(dleEot(n).toByte())
                log(DEBUG, "Real-time status request", "DLE EOT $n")
                if (withArg) 4 else 3
            }
            0x05 -> {
                if (available(index) < 3) return 0
                val n = u8(index + 2)
                commandCount++
                responses += byteArrayOf(printerStatusByte().toByte())
                log(DEBUG, "Real-time recovery request", "DLE ENQ $n")
                3
            }
            0x14 -> {
                parseDleDc4(index)
            }
            else -> {
                log(WARN, "Unsupported DLE command", hex = hex(index, 2))
                2
            }
        }
    }

    private fun parseDleDc4(index: Int): Int {
        if (available(index) < 3) return 0
        val fn = u8(index + 2)
        commandCount++
        return when (fn) {
            1 -> {
                // DLE DC4 fn=1 m t : cash drawer pulse, 5 bytes.
                if (available(index) < 5) return 0
                val pin = u8(index + 3)
                val on = u8(index + 4)
                log(INFO, "Cash drawer pulse", "pin=$pin on=${on * 2}ms")
                5
            }
            7 -> {
                // DLE DC4 fn=7 m : real-time status, 4 bytes.
                if (available(index) < 4) return 0
                val m = u8(index + 3)
                when (m) {
                    1 -> {
                        responses += asbBytes()
                        log(DEBUG, "Real-time ASB request", "DLE DC4 7 1")
                    }
                    2 -> {
                        responses += asbBytes()
                        log(DEBUG, "Real-time extended ASB request", "DLE DC4 7 2")
                    }
                    4 -> log(DEBUG, "Real-time offline response request", "DLE DC4 7 4")
                    5 -> {
                        // TM-m30 does not support battery status (m = 1, 2
                        // only), so real hardware sends no reply. Keep the
                        // stream clean for clients that wait for ASB.
                        log(DEBUG, "Battery status not supported", "DLE DC4 7 5")
                    }
                    else -> log(DEBUG, "DLE DC4 7 status", "m=$m")
                }
                4
            }
            8 -> {
                // DLE DC4 fn=8 (clear buffers + clear response) uses 10 bytes:
                // 10 14 08 01 03 14 01 06 02 08. Reply 37 25 00.
                if (available(index) < 10) return 0
                log(DEBUG, "Clear buffers", "DLE DC4 8")
                responses += byteArrayOf(0x37, 0x25, 0x00)
                10
            }
            2, 3 -> {
                // Power-off / response-mode variants are not needed for receipt
                // printing and the app sequence observed by TMulator does not use them.
                if (available(index) < 4) return 0
                log(WARN, "Unsupported DLE DC4 function", "fn=$fn")
                4
            }
            else -> {
                log(WARN, "Unsupported DLE DC4 function", "fn=$fn")
                3
            }
        }
    }

    private fun parseEsc(index: Int): Int {
        if (available(index) < 2) return 0
        val cmd = u8(index + 1)
        commandCount++
        return when (cmd) {
            0x40 -> { // ESC @
                flushTextRun()
                state.reset()
                log(DEBUG, "Initialize printer")
                2
            }
            0x21 -> { // ESC ! n
                if (available(index) < 3) return 0
                flushTextRun()
                val n = u8(index + 2)
                state.font = if (n and 0x01 != 0) FontStyle.B else FontStyle.A
                state.bold = n and 0x08 != 0
                state.heightMultiplier = if (n and 0x10 != 0) 2 else 1
                state.widthMultiplier = if (n and 0x20 != 0) 2 else 1
                state.underline = n and 0x80 != 0
                logVerbose("Set print mode", "ESC ! 0x%02X".format(n))
                3
            }
            0x24 -> { // ESC $ nL nH absolute position
                if (available(index) < 4) return 0
                flushTextRun()
                state.absoluteX = u16(index + 2)
                logVerbose("Absolute position", "${state.absoluteX} dots")
                4
            }
            0x25 -> { if (available(index) < 3) return 0; 3 } // select user chars
            0x26 -> { // define user-defined chars: ESC & y c1 c2 [x d...]
                if (available(index) < 5) return 0
                val y = u8(index + 2)
                val c1 = u8(index + 3)
                val c2 = u8(index + 4)
                val columns = (c2 - c1 + 1).coerceAtLeast(0)
                val total = 5 + columns * (y + 1)
                if (available(index) < total) return 0
                log(WARN, "User-defined characters ignored")
                total
            }
            0x2A -> parseEscStar(index)
            0x32 -> { flushTextRun(); state.lineSpacing = 30; logVerbose("Default line spacing"); 2 }
            0x33 -> {
                if (available(index) < 3) return 0
                flushTextRun()
                state.lineSpacing = u8(index + 2).coerceAtLeast(1)
                logVerbose("Line spacing", "${state.lineSpacing} dots")
                3
            }
            0x30 -> { flushTextRun(); state.lineSpacing = 24; logVerbose("1/8 inch line spacing"); 2 }
            0x31 -> { flushTextRun(); state.lineSpacing = 30; logVerbose("1/6 inch line spacing"); 2 }
            0x34 -> { flushTextRun(); state.italic = true; 2 }
            0x35 -> { flushTextRun(); state.italic = false; 2 }
            0x2D -> {
                if (available(index) < 3) return 0
                flushTextRun()
                state.underline = u8(index + 2) != 0
                3
            }
            0x3D -> { if (available(index) < 3) return 0; 3 } // ESC = select peripheral
            0x3F -> { if (available(index) < 3) return 0; 3 } // cancel user chars
            0x45 -> {
                if (available(index) < 3) return 0
                flushTextRun()
                state.bold = u8(index + 2) != 0
                logVerbose("Emphasis", state.bold.toString())
                3
            }
            0x47 -> {
                if (available(index) < 3) return 0
                flushTextRun()
                state.doubleStrike = u8(index + 2) != 0
                3
            }
            0x4A -> { // ESC J n feed dots
                if (available(index) < 3) return 0
                val dots = u8(index + 2)
                flushLine(commitEmpty = true)
                if (dots > 0) document.add(ReceiptBlock.Feed(dots))
                logVerbose("Feed", "$dots dots")
                3
            }
            0x4B -> parseColumnImage(index, 8, 4)
            0x4C -> { log(WARN, "Page mode not supported (ESC L)"); 2 }
            0x4D -> {
                if (available(index) < 3) return 0
                flushTextRun()
                state.font = if (u8(index + 2) == 0) FontStyle.A else FontStyle.B
                3
            }
            0x52 -> { if (available(index) < 3) return 0; 3 }
            0x53 -> { log(WARN, "Standard mode selection ignored"); 2 }
            0x54 -> { if (available(index) < 3) return 0; 3 }
            0x56 -> { if (available(index) < 3) return 0; log(WARN, "90-degree rotation not supported"); 3 }
            0x57 -> { if (available(index) < 10) return 0; log(WARN, "Page mode area ignored"); 10 }
            0x61 -> {
                if (available(index) < 3) return 0
                state.align = when (u8(index + 2)) {
                    0, 48 -> Align.LEFT
                    1, 49 -> Align.CENTER
                    2, 50 -> Align.RIGHT
                    else -> Align.LEFT
                }
                updatePendingLine()
                logVerbose("Justification", state.align.name)
                3
            }
            0x63 -> {
                if (available(index) < 3) return 0
                val sub = u8(index + 2)
                if (sub in 3..5) {
                    if (available(index) < 4) return 0
                    4
                } else {
                    3
                }
            }
            0x64 -> { // ESC d n lines
                if (available(index) < 3) return 0
                val lines = u8(index + 2)
                flushLine(commitEmpty = true)
                if (lines > 0) document.add(ReceiptBlock.Feed(state.lineSpacing * lines))
                logVerbose("Feed lines", lines.toString())
                3
            }
            0x65 -> { if (available(index) < 3) return 0; 3 }
            0x69 -> { flushLine(commitEmpty = true); document.add(ReceiptBlock.Cut(partial = false)); log(INFO, "Full cut"); 2 }
            0x6D -> { flushLine(commitEmpty = true); document.add(ReceiptBlock.Cut(partial = true)); log(INFO, "Partial cut"); 2 }
            0x70 -> { // cash drawer
                if (available(index) < 5) return 0
                log(INFO, "Cash drawer pulse", "pin=${u8(index + 2)} on=${u8(index + 3) * 2}ms off=${u8(index + 4) * 2}ms")
                5
            }
            0x72 -> { if (available(index) < 3) return 0; 3 }
            0x74 -> {
                if (available(index) < 3) return 0
                flushTextRun()
                val n = u8(index + 2)
                val page = CodePage.fromEscT(n)
                if (page != null) {
                    state.codePage = page
                    logVerbose("Code page", "${page.displayName} (ESC t $n)")
                } else {
                    log(WARN, "Unsupported code page", "ESC t $n")
                }
                3
            }
            0x75 -> {
                if (available(index) < 3) return 0
                responses += byteArrayOf(0x00)
                3
            }
            0x76 -> {
                responses += byteArrayOf(paperSensorByte().toByte())
                log(DEBUG, "Paper sensor request", "ESC v")
                2
            }
            0x7B -> {
                if (available(index) < 3) return 0
                log(WARN, "Upside-down mode not supported")
                3
            }
            0x28 -> { // ESC ( <kind> pL pH <payload>
                if (available(index) < 5) return 0
                val kind = u8(index + 2)
                val payload = u16(index + 3)
                val total = 5 + payload
                if (available(index) < total) return 0
                log(WARN, "Unsupported ESC ( command", "kind=${kind.toChar()}", hex = hex(index, minOf(total, 16)))
                total
            }
            0x59 -> parseColumnImage(index, 8, 4)
            0x5A -> parseColumnImage(index, 24, 4)
            else -> {
                log(WARN, "Unsupported ESC command", hex = hex(index, 2))
                2
            }
        }
    }

    private fun parseEscStar(index: Int): Int {
        if (available(index) < 5) return 0
        val mode = u8(index + 2)
        val n = u16(index + 3)
        val bytesPerColumn = if (mode == 32 || mode == 33) 3 else 1
        val height = if (bytesPerColumn == 3) 24 else 8
        val total = 5 + n * bytesPerColumn
        if (available(index) < total) return 0
        val columns = buffer.copyOfRange(index + 5, index + 5 + n * bytesPerColumn)
        val image = MonochromeImage.fromColumns(n, height, columns)
        flushLine(commitEmpty = false)
        document.add(ReceiptBlock.Image(image, state.align))
        log(INFO, "Bit image", "${image.width}x${image.height} dots")
        return total
    }

    private fun parseColumnImage(index: Int, height: Int, headerSize: Int): Int {
        if (available(index) < headerSize) return 0
        val n = u16(index + 2)
        val bytesPerColumn = if (height <= 8) 1 else 3
        val total = headerSize + n * bytesPerColumn
        if (available(index) < total) return 0
        val columns = buffer.copyOfRange(index + headerSize, index + headerSize + n * bytesPerColumn)
        val image = MonochromeImage.fromColumns(n, height, columns)
        flushLine(commitEmpty = false)
        document.add(ReceiptBlock.Image(image, state.align))
        log(INFO, "Column image", "${image.width}x${image.height} dots")
        return total
    }

    private fun parseFs(index: Int): Int {
        if (available(index) < 2) return 0
        val cmd = u8(index + 1)
        commandCount++
        return when (cmd) {
            0x26, 0x2E -> { log(WARN, "Kanji mode not supported"); 2 }
            0x43 -> { if (available(index) < 3) return 0; 3 }
            0x53 -> { if (available(index) < 4) return 0; 4 }
            0x21 -> { if (available(index) < 3) return 0; 3 }
            0x28 -> {
                if (available(index) < 5) return 0
                val kind = u8(index + 2)
                val payload = u16(index + 3)
                val total = 5 + payload
                if (available(index) < total) return 0
                log(WARN, "Unsupported FS ( command", "kind=${kind.toChar()}", hex = hex(index, minOf(total, 16)))
                total
            }
            else -> { log(WARN, "Unsupported FS command", hex = hex(index, 2)); 2 }
        }
    }

    private fun parseGs(index: Int): Int {
        if (available(index) < 2) return 0
        val cmd = u8(index + 1)
        commandCount++
        return when (cmd) {
            0x21 -> {
                if (available(index) < 3) return 0
                flushTextRun()
                val n = u8(index + 2)
                // GS ! uses bits 0-2 for height and bits 4-6 for width.
                val height = (n and 0x07) + 1
                val width = ((n ushr 4) and 0x07) + 1
                state.widthMultiplier = width.coerceIn(1, 8)
                state.heightMultiplier = height.coerceIn(1, 8)
                logVerbose("Character size", "${state.widthMultiplier}x${state.heightMultiplier}")
                3
            }
            0x24 -> { if (available(index) < 4) return 0; 4 }
            0x28 -> parseGsExtended(index)
            0x38 -> parseGs8L(index)
            0x42 -> {
                if (available(index) < 3) return 0
                flushTextRun()
                state.reverse = u8(index + 2) != 0
                3
            }
            0x45 -> { if (available(index) < 3) return 0; logVerbose("Print speed ignored"); 3 }
            0x48 -> {
                if (available(index) < 3) return 0
                state.hri = when (u8(index + 2)) {
                    1 -> HriPosition.ABOVE
                    2, 3 -> HriPosition.BELOW
                    else -> HriPosition.NONE
                }
                3
            }
            0x49 -> {
                if (available(index) < 3) return 0
                val n = u8(index + 2)
                val id = printerIdResponse(n)
                if (id != null) {
                    responses += id
                    log(DEBUG, "Printer ID request", "GS I $n")
                } else {
                    log(DEBUG, "Printer ID unsupported", "GS I $n")
                }
                3
            }
            0x4C -> { if (available(index) < 4) return 0; 4 }
            0x50 -> {
                // GS P x y : set horizontal/vertical motion units (4 bytes).
                if (available(index) < 4) return 0
                logVerbose("Motion units", "x=${u8(index + 2)} y=${u8(index + 3)}")
                4
            }
            0x56 -> parseGsCut(index)
            0x57 -> { if (available(index) < 4) return 0; 4 }
            0x5C -> { if (available(index) < 4) return 0; 4 }
            0x61 -> {
                if (available(index) < 3) return 0
                val mask = u8(index + 2)
                state.asbMask = mask
                if (mask != 0) {
                    responses += asbBytes()
                    log(DEBUG, "Automatic status back enabled", "mask=0x%02X".format(mask))
                } else {
                    log(DEBUG, "Automatic status back disabled")
                }
                3
            }
            0x62 -> { if (available(index) < 3) return 0; 3 }
            0x66 -> {
                if (available(index) < 3) return 0
                state.hriFont = if (u8(index + 2) == 0) FontStyle.A else FontStyle.B
                3
            }
            0x68 -> {
                if (available(index) < 3) return 0
                state.barcodeHeight = u8(index + 2).coerceIn(1, 255)
                3
            }
            0x6B -> parseBarcode(index)
            0x72 -> {
                if (available(index) < 3) return 0
                val n = u8(index + 2)
                responses += byteArrayOf(gsRStatus(n).toByte())
                log(DEBUG, "Status request", "GS r $n")
                3
            }
            0x76 -> parseRaster(index)
            0x77 -> {
                if (available(index) < 3) return 0
                state.barcodeWidth = u8(index + 2).coerceIn(1, 6)
                3
            }
            0x78 -> { log(DEBUG, "Reprint QR ignored"); 2 }
            0x7A -> { if (available(index) < 3) return 0; 3 }
            else -> {
                log(WARN, "Unsupported GS command", hex = hex(index, 2))
                2
            }
        }
    }

    private fun parseGsCut(index: Int): Int {
        if (available(index) < 3) return 0
        val m = u8(index + 2)
        val partial = m == 1 || m == 49 || m == 66
        val full = m == 0 || m == 48 || m == 65
        return if (full || partial) {
            val consumed = if (m == 65 || m == 66) {
                if (available(index) < 4) return 0
                4
            } else {
                3
            }
            flushLine(commitEmpty = true)
            document.add(ReceiptBlock.Cut(partial))
            completedReceipts += document.snapshot()
            document.clear()
            overflowLogged = false
            log(INFO, if (partial) "Partial cut" else "Full cut")
            consumed
        } else {
            if (available(index) < 4) return 0
            log(WARN, "Unsupported cut mode", "m=$m")
            4
        }
    }

    private fun parseGsExtended(index: Int): Int {
        // GS ( <kind> pL pH <payload...>
        if (available(index) < 5) return 0
        val kind = u8(index + 2)
        val payload = u16(index + 3)
        val total = 5 + payload
        if (payload > MAX_COMMAND_BYTES) {
            log(WARN, "Oversized GS ( command", "${payload} bytes")
            return minOf(total, available(index)).coerceAtLeast(5)
        }
        if (available(index) < total) return 0

        return when (kind) {
            0x6B -> parseQr(index, total)       // GS ( k  2D barcode
            0x4C -> parseGraphics(index, total) // GS ( L  graphics
            0x48 -> parseJobControl(index, total) // GS ( H print job queue
            0x45 -> parseUserSettings(index, total) // GS ( E  customized settings
            0x42 -> {
                // GS ( B customizes ASB status bits. It is a setting command
                // with no response unless the underlying status changes.
                log(DEBUG, "ASB status customization")
                total
            }
            else -> {
                log(WARN, "Unsupported GS ( command", "kind=${kind.toChar()}", hex = hex(index, minOf(total, 16)))
                total
            }
        }
    }

    private fun parseUserSettings(index: Int, total: Int): Int {
        if (available(index) < 7) return 0
        val fn = u8(index + 5)
        if (fn == 6) {
            val a = u8(index + 6)
            val value = when (a) {
                3 -> if (profile.paperWidthDots <= 384) "2" else "6"
                11 -> "0"
                else -> null
            }
            if (value != null) {
                val response = byteArrayOf(0x37, 0x27) +
                    a.toString().toByteArray(Charsets.US_ASCII) +
                    byteArrayOf(0x1F) +
                    value.toByteArray(Charsets.US_ASCII) +
                    byteArrayOf(0x00)
                responses += response
                log(DEBUG, "Customized setting", "a=$a value=$value")
            }
        }
        return total
    }

    private fun printerIdResponse(n: Int): ByteArray? {
        val ep = profile.supportsEpsonExtensions
        return when (n) {
            1, 49 -> byteArrayOf(profile.modelId.toByte())
            2, 50 -> byteArrayOf((if (ep) 0x02 else 0x00).toByte())
            3, 51 -> byteArrayOf(0x01)
            33 -> infoA(33, byteArrayOf(0x42, 0x40, 0x42))
            36 -> infoA(36, "155".toByteArray(Charsets.US_ASCII))
            // TM-m30 sends an empty information-A block here. The paper-width
            // byte used by TM-T70 is not part of the TM-m30 response.
            110 -> infoA(110, byteArrayOf())
            65 -> infoB("1.53")
            66 -> infoB("EPSON")
            67 -> infoB(profile.wireModelName)
            68 -> infoB("UB-EEAE083ENSN")
            69 -> infoB("MULTI FONT")
            else -> null
        }
    }

    private fun infoA(identifier: Int, value: ByteArray): ByteArray =
        byteArrayOf(0x3D, identifier.toByte()) + value + byteArrayOf(0x00)

    private fun infoB(value: String): ByteArray =
        byteArrayOf(0x5F) + value.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)

    private fun parseQr(index: Int, total: Int): Int {
        if (available(index) < 8) return 0
        val cn = u8(index + 5)
        val fn = u8(index + 6)
        if (cn != 49) {
            log(WARN, "Unsupported 2D barcode", "cn=$cn fn=$fn")
            return total
        }
        when (fn) {
            65, 0x41 -> {
                log(DEBUG, "QR model", u8(index + 7).toString())
            }
            67, 0x43 -> {
                state.qrModuleSize = u8(index + 7).coerceIn(1, 16)
            }
            69, 0x45 -> {
                state.qrErrorCorrection = when (u8(index + 7)) {
                    48 -> QrErrorCorrection.L
                    49 -> QrErrorCorrection.M
                    50 -> QrErrorCorrection.Q
                    else -> QrErrorCorrection.H
                }
            }
            80, 0x50 -> {
                val dataStart = index + 8
                val end = index + total
                if (end > dataStart) {
                    qrData = String(buffer, dataStart, end - dataStart, Charsets.UTF_8)
                    log(INFO, "QR data", "\"${qrData!!.take(80)}\"")
                }
            }
            81, 0x51 -> {
                val data = qrData
                if (data.isNullOrEmpty()) {
                    log(WARN, "QR print with no stored data")
                } else {
                    flushLine(commitEmpty = false)
                    document.add(
                        ReceiptBlock.QrCode(
                            data = data,
                            moduleSize = state.qrModuleSize,
                            errorCorrection = state.qrErrorCorrection,
                            align = state.align,
                        ),
                    )
                    log(INFO, "QR code printed", "${data.length} chars")
                }
            }
            else -> log(WARN, "Unsupported QR function", "fn=$fn")
        }
        return total
    }

    private fun parseGraphics(index: Int, total: Int): Int {
        if (available(index) < 7) return 0
        val m = u8(index + 5)
        val fn = u8(index + 6)
        if (m != 0x30) {
            log(WARN, "Unsupported graphics mode", "m=$m fn=$fn")
            return total
        }
        when (fn) {
            112, 0x70 -> {
                if (available(index) < 15) return 0
                val a = u8(index + 7)
                val bx = u8(index + 8).coerceIn(1, 2)
                val by = u8(index + 9).coerceIn(1, 2)
                val c = u8(index + 10)
                val width = u16(index + 11)
                val height = u16(index + 13)
                if (a != 0x30 || c != 0x31 || width == 0 || height == 0) {
                    log(WARN, "Invalid GS ( L raster header", "width=$width height=$height")
                    return total
                }
                if (width.toLong() * height > MAX_IMAGE_DOTS) {
                    log(WARN, "GS ( L raster too large", "$width x $height dots")
                    return total
                }
                val rowBytes = (width + 7) / 8
                val needed = rowBytes * height
                val dataStart = index + 15
                if (dataStart + needed > index + total) {
                    log(WARN, "Truncated GS ( L raster", "needed=$needed")
                    return total
                }
                var image = MonochromeImage.fromRaster(width, height, buffer, dataStart)
                if (bx != 1 || by != 1) image = image.scaled(bx, by)
                flushLine(commitEmpty = false)
                document.add(ReceiptBlock.Image(image, state.align))
                log(INFO, "Graphics data", "${image.width}x${image.height} dots")
            }
            50, 0x32 -> {
                // The image was already previewed when stored; do not duplicate it.
                log(DEBUG, "Print stored graphics")
            }
            48, 0x30 -> {
                responses += "70".toByteArray() + "00000000".toByteArray() + byteArrayOf(0)
                log(DEBUG, "NV graphics capacity request")
            }
            52, 0x34 -> {
                responses += "72".toByteArray() + "00000000".toByteArray() + byteArrayOf(0)
                log(DEBUG, "Download graphics capacity request")
            }
            else -> log(WARN, "Unsupported GS ( L function", "fn=$fn")
        }
        return total
    }

    private fun parseJobControl(index: Int, total: Int): Int {
        if (available(index) < 7) return 0
        val fn = u8(index + 5)
        if (fn == 64) {
            // Vendor/ePOS SDK probe seen in the iPos handshake:
            // GS ( H 02 00 40 01. It is not a TM-m30 command; real hardware
            // ignores it, so do not inject an unexpected reply.
            log(DEBUG, "Vendor job status probe ignored")
            return total
        }
        if (fn != 48) {
            // TM-m30 supports only GS ( H <Function 48>; status probes for
            // other functions are ignored by real hardware.
            log(DEBUG, "GS ( H function ignored", "fn=$fn")
            return total
        }
        // GS ( H 06 00 30 30 d1 d2 d3 d4. Epson returns all four
        // printable ID bytes unchanged, including leading zeros/non-digits.
        if (total != 11 || u8(index + 6) != 48 ||
            (index + 7 until index + 11).any { u8(it) !in 32..126 }
        ) {
            log(WARN, "Invalid print job ID")
            return total
        }
        val idBytes = buffer.copyOfRange(index + 7, index + 11)
        val id = idBytes.toString(Charsets.US_ASCII)
        responses += byteArrayOf(0x37, 0x22) + idBytes + byteArrayOf(0)
        log(INFO, "Print job queue", "id=$id")
        return total
    }

    private fun parseGs8L(index: Int): Int {
        if (available(index) < 7) return 0
        val payload = u32(index + 3)
        if (payload < 10) {
            val total = (7 + payload).toInt()
            if (available(index) < total) return 0
            log(WARN, "Invalid GS 8 L length", payload.toString())
            return total
        }
        if (payload > MAX_COMMAND_BYTES) {
            log(WARN, "Oversized GS 8 L command", "$payload bytes")
            return discardFrame(index, 7, payload)
        }
        val total = 7 + payload.toInt()
        if (available(index) < total) return 0
        val m = u8(index + 7)
        val fn = u8(index + 8)
        val a = u8(index + 9)
        val bx = u8(index + 10).coerceIn(1, 2)
        val by = u8(index + 11).coerceIn(1, 2)
        val c = u8(index + 12)
        val width = u16(index + 13)
        val height = u16(index + 15)
        if (m != 0x30 || fn != 0x70 || a != 0x30 || c != 0x31 || width == 0 || height == 0) {
            log(WARN, "Unsupported GS 8 L command", "m=$m fn=$fn")
            return total
        }
        if (width.toLong() * height > MAX_IMAGE_DOTS) {
            log(WARN, "GS 8 L raster too large", "$width x $height dots")
            return total
        }
        val rowBytes = (width + 7) / 8
        val needed = rowBytes * height
        val dataStart = index + 17
        if (dataStart + needed > index + total) {
            log(WARN, "Truncated GS 8 L raster", "needed=$needed")
            return total
        }
        var image = MonochromeImage.fromRaster(width, height, buffer, dataStart)
        if (bx != 1 || by != 1) image = image.scaled(bx, by)
        flushLine(commitEmpty = false)
        document.add(ReceiptBlock.Image(image, state.align))
        log(INFO, "Large graphics", "${image.width}x${image.height} dots")
        return total
    }

    private fun parseRaster(index: Int): Int {
        if (available(index) < 3) return 0
        if (u8(index + 2) != 0x30) {
            log(WARN, "Unsupported GS v function", "fn=${u8(index + 2)}")
            return 2
        }
        if (available(index) < 8) return 0
        val mode = u8(index + 3)
        val widthBytes = u16(index + 4)
        val height = u16(index + 6)
        val dataLenLong = widthBytes.toLong() * height.toLong()
        if (dataLenLong > MAX_COMMAND_BYTES) {
            log(WARN, "Oversized raster image", "$widthBytes x $height bytes")
            return discardFrame(index, 8, dataLenLong)
        }
        val dataLen = dataLenLong.toInt()
        val total = 8 + dataLen
        if (available(index) < total) return 0
        if (widthBytes == 0 || height == 0) return total
        if (mode !in 0..3 && mode !in 48..51) {
            log(WARN, "Invalid raster mode", mode.toString())
            return total
        }
        val width = widthBytes * 8
        if (width.toLong() * height > MAX_IMAGE_DOTS) {
            log(WARN, "Raster too large", "$width x $height dots")
            return if (available(index) >= total) total else discardFrame(index, 8, dataLen.toLong())
        }
        var image = MonochromeImage.fromRaster(width, height, buffer, index + 8)
        val normalized = if (mode >= 48) mode - 48 else mode
        val scaleX = if (normalized == 1 || normalized == 3) 2 else 1
        val scaleY = if (normalized == 2 || normalized == 3) 2 else 1
        if (scaleX != 1 || scaleY != 1) image = image.scaled(scaleX, scaleY)
        flushLine(commitEmpty = false)
        document.add(ReceiptBlock.Image(image, state.align))
        log(INFO, "Raster image", "${image.width}x${image.height} dots")
        return total
    }

    private fun parseBarcode(index: Int): Int {
        if (available(index) < 3) return 0
        val m = u8(index + 2)
        if (m <= 6) {
            var end = index + 3
            while (end < length && u8(end) != 0) end++
            if (end >= length) return 0
            if (end - (index + 3) > 512) { log(WARN, "Barcode data too long"); return end - index + 1 }
            val data = state.codePage.decode(buffer, index + 3, end - index - 3)
            addBarcode(m, data)
            return end - index + 1
        }
        if (m in 65..73) {
            if (available(index) < 4) return 0
            val n = u8(index + 3)
            val total = 4 + n
            if (available(index) < total) return 0
            val data = state.codePage.decode(buffer, index + 4, n)
            addBarcode(m, data)
            return total
        }
        log(WARN, "Unsupported barcode type", "m=$m")
        return 3
    }

    private fun addBarcode(m: Int, rawData: String) {
        val symbology = when (m) {
            0, 65 -> BarcodeSymbology.UPC_A
            1, 66 -> BarcodeSymbology.UPC_E
            2, 67 -> BarcodeSymbology.EAN_13
            3, 68 -> BarcodeSymbology.EAN_8
            4, 69 -> BarcodeSymbology.CODE_39
            5, 70 -> BarcodeSymbology.ITF
            6, 71 -> BarcodeSymbology.CODABAR
            72 -> BarcodeSymbology.CODE_93
            73 -> BarcodeSymbology.CODE_128
            else -> return
        }
        val data = when (symbology) {
            BarcodeSymbology.CODE_39 -> rawData.trim('*')
            BarcodeSymbology.CODE_128 -> rawData.removePrefix("{B").removePrefix("{A").removePrefix("{C")
            else -> rawData
        }
        if (data.isEmpty()) {
            log(WARN, "Empty barcode data")
            return
        }
        flushLine(commitEmpty = false)
        document.add(
            ReceiptBlock.Barcode(
                symbology = symbology,
                data = data,
                heightDots = state.barcodeHeight,
                moduleWidth = state.barcodeWidth,
                hri = state.hri,
                align = state.align,
            ),
        )
        log(INFO, "Barcode", "${symbology.name} \"${data.take(64)}\"")
    }

    // ------------------------------------------------------------------ text

    private fun appendText(value: String) {
        if (value.isEmpty()) return
        val style = state.textStyle()
        if (textStyle != null && textStyle != style) flushTextRun()
        if (text.isEmpty()) textStyle = style
        text.append(value)
        columnChars += value.length * state.widthMultiplier
    }

    private fun flushTextRun() {
        val value = text.toString()
        if (value.isEmpty()) return
        val style = textStyle ?: state.textStyle()
        lineRuns.add(TextRun(value, style))
        // ESC $ positions the next text run, not every later run after a
        // style change. Consume it once the run it belongs to is emitted.
        if (style.absoluteXDots != null) state.absoluteX = null
        text.clear()
        textStyle = null
        updatePendingLine()
    }

    private fun updatePendingLine() {
        val runs = if (text.isEmpty()) lineRuns.toList() else {
            lineRuns + TextRun(text.toString(), textStyle ?: state.textStyle())
        }
        document.setPendingLine(runs, state.align, state.lineSpacing)
    }

    private fun horizontalTab() {
        val stops = if (state.tabStops.isNotEmpty()) state.tabStops else DEFAULT_TABS
        val next = stops.firstOrNull { it > columnChars } ?: ((columnChars / 8) + 1) * 8
        val spaces = (next - columnChars).coerceIn(1, 64)
        appendText(" ".repeat(spaces))
    }

    private fun lineFeed() {
        flushTextRun()
        document.commitPending(state.align, state.lineSpacing)
        lineRuns.clear()
        columnChars = 0
        state.absoluteX = null
        updatePendingLine()
    }

    private fun formFeed() {
        flushTextRun()
        document.commitPending(state.align, state.lineSpacing)
        document.add(ReceiptBlock.Feed(state.lineSpacing))
        lineRuns.clear()
        columnChars = 0
        state.absoluteX = null
        updatePendingLine()
    }

    private fun cancelLine() {
        lineRuns.clear()
        text.clear()
        textStyle = null
        columnChars = 0
        state.absoluteX = null
        updatePendingLine()
        log(DEBUG, "Cancel current line")
    }

    /** Commits the current line before a non-text block. */
    private fun flushLine(commitEmpty: Boolean) {
        flushTextRun()
        if (lineRuns.isNotEmpty() || commitEmpty) {
            document.commitPending(state.align, state.lineSpacing)
            lineRuns.clear()
            columnChars = 0
        }
        state.absoluteX = null
        updatePendingLine()
    }

    // ----------------------------------------------------------------- status

    private fun printerStatusByte(): Int {
        val s = statusProvider()
        var value = 0x12
        if (s.coverOpen) value = value or 0x04
        if (s.paperOut) value = value or 0x20
        if (s.coverOpen || s.paperOut) value = value or 0x40
        return value
    }

    private fun dleEot(n: Int): Int {
        val s = statusProvider()
        return when (n) {
            1 -> 0x16 or
                (if (s.paperOut || s.coverOpen) 0x08 else 0)
            2 -> 0x12 or
                (if (s.coverOpen) 0x04 else 0) or
                (if (s.paperOut) 0x20 else 0) or
                (if (s.coverOpen || s.paperOut) 0x40 else 0)
            3 -> 0x12 or (if (s.coverOpen) 0x40 else 0)
            4 -> 0x12 or (if (s.paperOut) 0x60 else 0)
            else -> 0x12
        }
    }

    private fun paperSensorByte(): Int = 0x12 or (if (statusProvider().paperOut) 0x60 else 0)

    /** GS r paper/drawer status uses fixed-zero bits 4 and 7, unlike DLE EOT. */
    private fun gsRStatus(n: Int): Int = when (n) {
        1, 49 -> if (statusProvider().paperOut) 0x0F else 0x00
        2, 50 -> if (statusProvider().drawerOpen) 0x01 else 0x00
        else -> 0x00
    }

    private fun asbBytes(): ByteArray {
        val s = statusProvider()
        val mask = state.asbMask
        var first = 0x14
        if (mask and 0x01 != 0 && s.drawerOpen) first = first or 0x04
        if (mask and 0x02 != 0 && s.coverOpen) first = first or 0x20 or 0x08
        var third = 0
        if (mask and 0x08 != 0 && s.paperOut) third = third or 0x0F
        return byteArrayOf(first.toByte(), 0x00, third.toByte(), 0x0F)
    }

    // ------------------------------------------------------------------- logs

    private fun log(severity: LogSeverity, title: String, detail: String? = null, hex: String? = null) {
        if (severity == LogSeverity.WARN) unsupportedCount++
        if (severity == LogSeverity.DEBUG && !verboseLogging) return
        if (logs.size >= MAX_LOG_ENTRIES) return
        val entry = EscPosLog(severity, title, detail, hex)
        logs.add(entry)
        logger?.invoke(entry)
    }

    private fun logVerbose(title: String, detail: String? = null) = log(DEBUG, title, detail)

    companion object {
        private const val MAX_BUFFER_BYTES = 12 * 1024 * 1024
        private const val MAX_COMMAND_BYTES = 8 * 1024 * 1024
        private const val MAX_DISCARD_BYTES = 64L * 1024 * 1024
        private const val MAX_IMAGE_DOTS = 4_000_000L
        private const val MAX_LOG_ENTRIES = 300
        private val DEFAULT_TABS = listOf(8, 16, 24, 32, 40, 48, 56, 64)
        private val WARN = LogSeverity.WARN
        private val INFO = LogSeverity.INFO
        private val DEBUG = LogSeverity.DEBUG
    }
}
