package dev.tmulator.escpos

import dev.tmulator.util.SampleReceipt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EscPosParserTest {

    private fun parser(
        status: () -> PrinterStatus = { PrinterStatus() },
        verbose: Boolean = false,
    ) = EscPosParser(
        profile = PrinterProfile.TM_M30,
        statusProvider = status,
        verboseLogging = verbose,
    )

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun text(value: String) = value.toByteArray(Charsets.US_ASCII)

    @Test
    fun `prints text and applies alignment and emphasis`() {
        val p = parser()
        val data = bytes(0x1B, 0x40) + text("Hello\n") +
            bytes(0x1B, 0x61, 1) + text("Centered\n") +
            bytes(0x1B, 0x45, 1) + text("Bold\n")
        p.feed(data)
        val snapshot = p.document.snapshot()
        val textBlocks = snapshot.blocks.filterIsInstance<ReceiptBlock.Text>()
        assertEquals(3, textBlocks.size)
        assertEquals("Hello", textBlocks[0].runs.first().text)
        assertEquals(Align.CENTER, textBlocks[1].align)
        assertTrue(textBlocks[2].runs.first().style.bold)
    }

    @Test
    fun `character size and underline commands are interpreted`() {
        val p = parser()
        p.feed(
            bytes(
                0x1D, 0x21, 0x11, // double width + height
                0x1B, 0x2D, 1, // underline
            ) + text("Big\n"),
        )
        val style = (p.document.snapshot().blocks.first() as ReceiptBlock.Text).runs.first().style
        assertEquals(2, style.widthMultiplier)
        assertEquals(2, style.heightMultiplier)
        assertTrue(style.underline)
    }

    @Test
    fun `incremental one-byte feeds produce the same receipt`() {
        val all = SampleReceipt.bytes()
        val whole = parser()
        val wholeOutcome = whole.feed(all)
        val a = wholeOutcome.completed.firstOrNull() ?: whole.document.snapshot()
        val oneByte = parser()
        val completed = ArrayList<ReceiptSnapshot>()
        for (b in all) completed += oneByte.feed(byteArrayOf(b)).completed
        oneByte.finish()
        val b = completed.firstOrNull() ?: oneByte.document.snapshot()
        assertEquals(a.plainText(), b.plainText())
        assertEquals(a.blocks.size, b.blocks.size)
        assertEquals(a.cutCount, b.cutCount)
        assertTrue(b.blocks.any { it is ReceiptBlock.Image })
        assertTrue(b.blocks.any { it is ReceiptBlock.QrCode })
        assertTrue(b.blocks.any { it is ReceiptBlock.Barcode })
    }

    @Test
    fun `sample receipt contains all major block types`() {
        val p = parser()
        val outcome = p.feed(SampleReceipt.bytes())
        p.finish()
        val snapshot = outcome.completed.firstOrNull() ?: p.document.snapshot()
        assertEquals(1, snapshot.cutCount)
        assertEquals(1, snapshot.blocks.count { it is ReceiptBlock.QrCode })
        assertEquals(1, snapshot.blocks.count { it is ReceiptBlock.Barcode })
        assertEquals(1, snapshot.blocks.count { it is ReceiptBlock.Image })
        assertTrue(snapshot.plainText().contains("TOTAL"))
        assertEquals(0, p.unsupportedCount)
    }

    @Test
    fun `cut commands produce a cut block`() {
        val p = parser()
        val outcome = p.feed(text("Cut me\n") + bytes(0x1D, 0x56, 0x00))
        val snapshot = outcome.completed.single()
        assertTrue(snapshot.blocks.last() is ReceiptBlock.Cut)
        val partial = parser()
        val partialOutcome = partial.feed(text("Partial\n") + bytes(0x1D, 0x56, 0x01))
        assertTrue((partialOutcome.completed.single().blocks.last() as ReceiptBlock.Cut).partial)
    }

    @Test
    fun `status responses match ESC POS bits`() {
        val ready = parser()
        var outcome = ready.feed(bytes(0x10, 0x04, 1))
        assertArrayEquals(byteArrayOf(0x16), outcome.responses.first())

        val paperOut = parser(status = { PrinterStatus(paperOut = true) })
        outcome = paperOut.feed(bytes(0x10, 0x04, 4))
        assertEquals(0x72, outcome.responses.first().first().toInt() and 0xFF)

        outcome = paperOut.feed(bytes(0x10, 0x04, 2))
        assertTrue((outcome.responses.first().first().toInt() and 0x20) != 0)
        assertTrue((outcome.responses.first().first().toInt() and 0x40) != 0)

        outcome = ready.feed(bytes(0x1D, 0x61, 0xFF))
        assertArrayEquals(byteArrayOf(0x14, 0x00, 0x00, 0x0F), outcome.responses.first())
    }

    @Test
    fun `Epson job queue is acknowledged`() {
        val p = parser()
        val outcome = p.feed(
            bytes(0x1D, 0x28, 0x48, 0x06, 0x00, 0x30, 0x30, 0x30, 0x30, 0x30, 0x31),
        )
        assertArrayEquals(bytes(0x37, 0x22) + text("0001") + bytes(0), outcome.responses.single())
    }

    @Test
    fun `process ID preserves printable non-digit bytes across fragmented reads`() {
        val p = parser()
        val command = bytes(0x1D, 0x28, 0x48, 0x06, 0x00, 0x30, 0x30) + text("A 9!")
        for (b in command.dropLast(1)) {
            assertTrue(p.feed(byteArrayOf(b)).responses.isEmpty())
        }
        val outcome = p.feed(byteArrayOf(command.last()))
        assertArrayEquals(bytes(0x37, 0x22) + text("A 9!") + bytes(0), outcome.responses.single())
        assertTrue(p.document.snapshot().isEmpty)
    }

    @Test
    fun `raster image GS v 0 becomes an image block`() {
        val p = parser()
        // 8 x 2 dots, first row all black, second row alternating.
        val data = bytes(0xFF, 0xAA)
        val command = bytes(0x1D, 0x76, 0x30, 0x00, 1, 0, 2, 0) + data
        p.feed(command)
        val image = p.document.snapshot().blocks.filterIsInstance<ReceiptBlock.Image>().single().image
        assertEquals(8, image.width)
        assertEquals(2, image.height)
        assertTrue(image.isBlack(0, 0))
        assertTrue(image.isBlack(0, 1))
        assertFalse(image.isBlack(1, 1))
    }

    @Test
    fun `GS 8 L large raster is parsed`() {
        val p = parser()
        val width = 16
        val height = 2
        val rowBytes = width / 8
        val data = ByteArray(rowBytes * height) { 0xFF.toByte() }
        val parameterLength = 10 + data.size
        val command = bytes(
            0x1D, 0x38, 0x4C,
            parameterLength and 0xFF, (parameterLength shr 8) and 0xFF, 0, 0,
            0x30, 0x70, 0x30, 0x01, 0x01, 0x31,
            width and 0xFF, (width shr 8) and 0xFF, height and 0xFF, (height shr 8) and 0xFF,
        ) + data
        p.feed(command)
        val image = p.document.snapshot().blocks.filterIsInstance<ReceiptBlock.Image>().single().image
        assertEquals(16, image.width)
        assertEquals(2, image.height)
        assertTrue(image.isBlack(15, 1))
    }

    @Test
    fun `GS open paren L stored raster is parsed`() {
        val p = parser()
        val width = 8
        val height = 2
        val data = ByteArray(2) { 0xFF.toByte() }
        val payloadLength = 10 + data.size
        val command = bytes(
            0x1D, 0x28, 0x4C,
            payloadLength and 0xFF, (payloadLength shr 8) and 0xFF,
            0x30, 0x70, 0x30, 0x01, 0x01, 0x31,
            width and 0xFF, 0, height and 0xFF, 0,
        ) + data
        p.feed(command)
        val image = p.document.snapshot().blocks.filterIsInstance<ReceiptBlock.Image>().single().image
        assertEquals(8, image.width)
        assertEquals(2, image.height)
    }

    @Test
    fun `QR store and print creates QR block`() {
        val p = parser()
        fun qr(payload: ByteArray) = bytes(0x1D, 0x28, 0x6B, payload.size and 0xFF, payload.size shr 8) + payload
        p.feed(
            qr(bytes(49, 67, 6)) +
                qr(bytes(49, 69, 49)) +
                qr(bytes(49, 80, 48) + text("hello")) +
                qr(bytes(49, 81, 48)),
        )
        val block = p.document.snapshot().blocks.filterIsInstance<ReceiptBlock.QrCode>().single()
        assertEquals("hello", block.data)
        assertEquals(6, block.moduleSize)
        assertEquals(QrErrorCorrection.M, block.errorCorrection)
    }

    @Test
    fun `barcode length form produces barcode block`() {
        val p = parser()
        p.feed(bytes(0x1D, 0x68, 0x40, 0x1D, 0x77, 2, 0x1D, 0x6B, 73, 5) + text("AB123"))
        val block = p.document.snapshot().blocks.filterIsInstance<ReceiptBlock.Barcode>().single()
        assertEquals(BarcodeSymbology.CODE_128, block.symbology)
        assertEquals("AB123", block.data)
        assertEquals(0x40, block.heightDots)
    }

    @Test
    fun `GS P motion units do not consume the next command`() {
        val p = parser()
        p.feed(bytes(0x1D, 0x50, 0xCB, 0xCB, 0x48, 0x69, 0x0A))
        assertEquals("Hi", p.document.snapshot().plainText())
    }

    @Test
    fun `GS exclamation width and height nibbles map correctly`() {
        val p = parser()
        p.feed(bytes(0x1D, 0x21, 0x10) + text("W\n"))
        var style = (p.document.snapshot().blocks.first() as ReceiptBlock.Text).runs.first().style
        assertEquals(2, style.widthMultiplier)
        assertEquals(1, style.heightMultiplier)

        val q = parser()
        q.feed(bytes(0x1D, 0x21, 0x01) + text("H\n"))
        style = (q.document.snapshot().blocks.first() as ReceiptBlock.Text).runs.first().style
        assertEquals(1, style.widthMultiplier)
        assertEquals(2, style.heightMultiplier)
    }

    @Test
    fun `code page 437 decodes extended characters`() {
        val p = parser()
        p.feed(bytes(0x82, 0x0A))
        assertTrue((p.document.snapshot().blocks.first() as ReceiptBlock.Text).runs.first().text.contains('é'))
    }

    @Test
    fun `printer ID reports TM m30 model and information`() {
        val p = parser()
        var outcome = p.feed(bytes(0x1D, 0x49, 0x01))
        assertArrayEquals(byteArrayOf(0x01), outcome.responses.single())

        outcome = p.feed(bytes(0x1D, 0x49, 0x02))
        assertArrayEquals(byteArrayOf(0x02), outcome.responses.single())

        outcome = p.feed(bytes(0x1D, 0x49, 0x43))
        assertEquals(0x5F, outcome.responses.single().first().toInt() and 0xFF)
        assertEquals("TM-m30", String(outcome.responses.single().copyOfRange(1, 7), Charsets.US_ASCII))

        outcome = p.feed(bytes(0x1D, 0x49, 0x6E))
        assertArrayEquals(byteArrayOf(0x3D, 0x6E, 0x00), outcome.responses.single())
    }

    @Test
    fun `TM m30III profile reports its model name`() {
        val p = EscPosParser(profile = PrinterProfile.TM_M30III)
        val outcome = p.feed(bytes(0x1D, 0x49, 0x43))
        val response = outcome.responses.single()
        assertEquals("TM-m30III", String(response.copyOfRange(1, response.size - 1), Charsets.US_ASCII))
    }

    @Test
    fun `each cut completes a separate receipt segment`() {
        val p = parser()
        val outcome = p.feed(
            text("FIRST\n") + bytes(0x1D, 0x56, 0) +
                text("SECOND\n") + bytes(0x1D, 0x56, 0) +
                text("THIRD\n"),
        )
        assertEquals(2, outcome.completed.size)
        assertTrue(outcome.completed[0].plainText().contains("FIRST"))
        assertTrue(outcome.completed[1].plainText().contains("SECOND"))
        assertTrue(p.document.snapshot().plainText().contains("THIRD"))
    }

    @Test
    fun `GS r reports paper sensor status instead of realtime status`() {
        assertArrayEquals(bytes(0x00), parser().feed(bytes(0x1D, 0x72, 1)).responses.single())
        val out = parser(status = { PrinterStatus(paperOut = true) })
        assertArrayEquals(bytes(0x0F), out.feed(bytes(0x1D, 0x72, 1)).responses.single())
    }

    @Test
    fun `ASB encodes simulated cover and paper state`() {
        val cover = parser(status = { PrinterStatus(coverOpen = true) })
        val coverAsb = cover.feed(bytes(0x1D, 0x61, 0xFF)).responses.single()
        assertEquals(0x3C, coverAsb[0].toInt() and 0xFF)
        assertEquals(0x00, coverAsb[2].toInt() and 0xFF)

        val paper = parser(status = { PrinterStatus(paperOut = true) })
        val paperAsb = paper.feed(bytes(0x1D, 0x61, 0xFF)).responses.single()
        assertEquals(0x14, paperAsb[0].toInt() and 0xFF)
        assertEquals(0x0F, paperAsb[2].toInt() and 0xFF)
    }

    @Test
    fun `customized paper width setting is answered`() {
        val p = parser()
        val outcome = p.feed(bytes(0x1D, 0x28, 0x45, 0x02, 0x00, 0x06, 0x03))
        assertArrayEquals(
            byteArrayOf(0x37, 0x27, 0x33, 0x1F, 0x36, 0x00),
            outcome.responses.single(),
        )
    }

    @Test
    fun `Epson POS status handshake is parsed without printing`() {
        val handshake = bytes(
            0x10, 0x14, 0x07, 0x05,
            0x1B, 0x3D, 0x01,
            0x1D, 0x61, 0xFF,
            0x1B, 0x3D, 0x01,
            0x1D, 0x28, 0x45, 0x02, 0x00, 0x06, 0x03,
            0x1D, 0x49, 0x6E,
            0x1B, 0x3D, 0x01,
            0x1D, 0x28, 0x45, 0x02, 0x00, 0x06, 0x0B,
            0x1B, 0x3D, 0x01,
            0x1D, 0x28, 0x48, 0x02, 0x00, 0x40, 0x01,
            0x1B, 0x3D, 0x01,
            0x1D, 0x28, 0x42, 0x07, 0x00, 0x61, 0x31, 0x2C, 0x33, 0x2D, 0x46, 0x37,
            0x1B, 0x3D, 0x01,
            0x1D, 0x49, 0x01,
        )
        assertEquals(64, handshake.size)
        val p = parser()
        val outcome = p.feed(handshake)
        val snapshot = p.document.snapshot()
        assertTrue("handshake must not print text", snapshot.blocks.isEmpty())
        assertEquals("", snapshot.plainText())
        assertEquals(5, outcome.responses.size)
        assertArrayEquals(byteArrayOf(0x14, 0x00, 0x00, 0x0F), outcome.responses[0])
        assertArrayEquals(byteArrayOf(0x37, 0x27, 0x33, 0x1F, 0x36, 0x00), outcome.responses[1])
        assertArrayEquals(byteArrayOf(0x3D, 0x6E, 0x00), outcome.responses[2])
        assertArrayEquals(byteArrayOf(0x37, 0x27, 0x31, 0x31, 0x1F, 0x30, 0x00), outcome.responses[3])
        assertArrayEquals(byteArrayOf(0x01), outcome.responses[4])
    }

    @Test
    fun `ASB bit customization command is accepted`() {
        val p = parser()
        p.feed(bytes(0x1D, 0x61, 0xFF))
        val outcome = p.feed(
            bytes(0x1D, 0x28, 0x42, 0x07, 0x00, 0x61, 0x31, 0x2C, 0x33, 0x2D, 0x46, 0x37),
        )
        assertTrue(outcome.responses.isEmpty())
        assertEquals(0, p.unsupportedCount)
    }

    @Test
    fun `vendor job status probe is ignored like real hardware`() {
        val p = parser()
        val outcome = p.feed(bytes(0x1D, 0x28, 0x48, 0x02, 0x00, 0x40, 0x01))
        assertTrue(outcome.responses.isEmpty())
        assertTrue(p.document.snapshot().blocks.isEmpty())
        assertEquals(0, p.unsupportedCount)
    }

    @Test
    fun `interface status request consumes its parameter byte`() {
        val p = parser()
        val outcome = p.feed(bytes(0x10, 0x04, 0x12, 0x01))
        assertArrayEquals(byteArrayOf(0x12), outcome.responses.single())
        assertEquals(0, p.unsupportedCount)
    }

    @Test
    fun `Epson clear response is answered`() {
        val p = parser()
        val outcome = p.feed(bytes(0x10, 0x14, 0x08, 0x01, 0x03, 0x14, 0x01, 0x06, 0x02, 0x08))
        assertArrayEquals(byteArrayOf(0x37, 0x25, 0x00), outcome.responses.single())
        assertTrue(p.document.snapshot().blocks.isEmpty())
        assertEquals(0, p.unsupportedCount)
    }

    @Test
    fun `Epson status handshake works split byte by byte`() {
        val handshake = bytes(
            0x10, 0x14, 0x07, 0x05, 0x1B, 0x3D, 0x01, 0x1D, 0x61, 0xFF,
            0x1D, 0x28, 0x45, 0x02, 0x00, 0x06, 0x03, 0x1D, 0x49, 0x6E,
            0x1D, 0x28, 0x45, 0x02, 0x00, 0x06, 0x0B, 0x1D, 0x28, 0x48,
            0x02, 0x00, 0x40, 0x01, 0x1D, 0x28, 0x42, 0x07, 0x00, 0x61,
            0x31, 0x2C, 0x33, 0x2D, 0x46, 0x37, 0x1D, 0x49, 0x01,
        )
        val p = parser()
        for (b in handshake) p.feed(byteArrayOf(b))
        assertTrue(p.document.snapshot().blocks.isEmpty())
        assertTrue(p.document.snapshot().plainText().isEmpty())
    }

    @Test
    fun `unsupported commands are logged and skipped safely`() {
        val p = parser(verbose = true)
        p.feed(bytes(0x1B, 0x7E, 0x41, 0x0A))
        val snapshot = p.document.snapshot()
        assertTrue(p.unsupportedCount >= 1)
        assertTrue(snapshot.plainText().contains("A"))
    }

    @Test
    fun `absolute position creates a positioned run`() {
        val p = parser()
        p.feed(text("A") + bytes(0x1B, 0x24, 100, 0) + text("B\n"))
        val line = p.document.snapshot().blocks.first() as ReceiptBlock.Text
        assertEquals("A", line.runs[0].text)
        assertEquals(100, line.runs[1].style.absoluteXDots)
    }

    @Test
    fun `initialize resets style state`() {
        val p = parser()
        p.feed(bytes(0x1B, 0x45, 1) + text("Bold\n") + bytes(0x1B, 0x40) + text("Normal\n"))
        val blocks = p.document.snapshot().blocks.filterIsInstance<ReceiptBlock.Text>()
        assertTrue(blocks[0].runs.first().style.bold)
        assertFalse(blocks[1].runs.first().style.bold)
    }
}
