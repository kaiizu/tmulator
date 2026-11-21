package dev.tmulator.escpos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptDocumentTest {

    private fun blank(width: Int, height: Int): MonochromeImage {
        val rowBytes = (width + 7) / 8
        return MonochromeImage.fromRaster(width, height, ByteArray(rowBytes * height))
    }

    @Test
    fun `adjacent image bands are reassembled`() {
        val document = ReceiptDocument(576)
        document.add(ReceiptBlock.Image(blank(150, 128), Align.CENTER))
        document.add(ReceiptBlock.Image(blank(150, 22), Align.CENTER))
        val blocks = document.snapshot().blocks
        assertEquals(1, blocks.size)
        val image = blocks.single() as ReceiptBlock.Image
        assertEquals(150, image.image.width)
        assertEquals(150, image.image.height)
    }

    @Test
    fun `different widths and alignments are not merged`() {
        val document = ReceiptDocument(576)
        document.add(ReceiptBlock.Image(blank(150, 128), Align.CENTER))
        document.add(ReceiptBlock.Image(blank(151, 22), Align.CENTER))
        document.add(ReceiptBlock.Image(blank(150, 22), Align.LEFT))
        assertEquals(3, document.snapshot().blocks.size)
    }

    @Test
    fun `text between bands prevents merging`() {
        val document = ReceiptDocument(576)
        document.add(ReceiptBlock.Image(blank(150, 128), Align.CENTER))
        document.add(ReceiptBlock.Text(listOf(TextRun("gap", TextStyle())), Align.CENTER, 0))
        document.add(ReceiptBlock.Image(blank(150, 22), Align.CENTER))
        assertTrue(document.snapshot().blocks.size == 3)
    }
}
