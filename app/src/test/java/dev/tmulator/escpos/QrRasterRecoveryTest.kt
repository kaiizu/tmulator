package dev.tmulator.escpos

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrRasterRecoveryTest {

    private val payload = "https://example.com/r/tmulator-test-42"

    private fun matrix(text: String = payload): BitMatrix = MultiFormatWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        1,
        1,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )

    /**
     * Renders a QR at [module] dots per module. When [dither] is true, black
     * modules get sparse white pinholes to mimic a halftone raster from a POS
     * driver.
     */
    private fun raster(matrix: BitMatrix, module: Int = 4, dither: Boolean = false): MonochromeImage {
        val width = matrix.width * module
        val height = matrix.height * module
        val rowBytes = (width + 7) / 8
        val data = ByteArray(rowBytes * height)
        for (my in 0 until matrix.height) {
            for (mx in 0 until matrix.width) {
                if (!matrix.get(mx, my)) continue
                for (dy in 0 until module) {
                    for (dx in 0 until module) {
                        val x = mx * module + dx
                        val y = my * module + dy
                        if (dither && (x + y) % 4 == 0) continue
                        data[y * rowBytes + (x ushr 3)] =
                            (data[y * rowBytes + (x ushr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                }
            }
        }
        return MonochromeImage.fromRaster(width, height, data)
    }

    @Test
    fun `recovers a clean raster QR`() {
        assertEquals(payload, QrRasterRecovery.recover(raster(matrix())))
    }

    @Test
    fun `recovers a dithered raster QR`() {
        assertEquals(payload, QrRasterRecovery.recover(raster(matrix(), dither = true)))
    }

    @Test
    fun `leaves a non-QR image alone`() {
        val width = 150
        val height = 150
        val rowBytes = (width + 7) / 8
        val data = ByteArray(rowBytes * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if ((x / 12 + y / 12) % 2 == 0) {
                    data[y * rowBytes + (x ushr 3)] =
                        (data[y * rowBytes + (x ushr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        assertNull(QrRasterRecovery.recover(MonochromeImage.fromRaster(width, height, data)))
    }
}
