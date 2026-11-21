package dev.tmulator.escpos

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.WeakHashMap

/**
 * Recovers QR codes that POS software sends as dithered raster images instead
 * of native ESC/POS QR commands (iPos does this). The source image is sampled
 * on several candidate module grids; a valid decode is re-rendered as a clean
 * QR by the receipt renderer.
 */
object QrRasterRecovery {
    private const val MIN_SIDE = 80
    private const val MAX_SIDE = 2048
    private const val MAX_TEXT = 2048
    private const val QUIET_ZONE = 4
    private const val MAX_PAD = 8

    private val MODULE_COUNTS = intArrayOf(
        21, 25, 29, 33, 37, 41, 45, 49, 53, 57, 61, 65, 69, 73, 77, 81,
    )
    private val BLACK_FRACTIONS = doubleArrayOf(0.35, 0.44, 0.53)
    private val HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    private val cache = WeakHashMap<MonochromeImage, String>()

    /** Returns the QR payload if [image] is a decodable QR raster, else null. */
    @Synchronized
    fun recover(image: MonochromeImage): String? {
        if (image.width < MIN_SIDE || image.height < MIN_SIDE) return null
        if (image.width > MAX_SIDE || image.height > MAX_SIDE) return null
        val aspect = image.width.toFloat() / image.height.toFloat()
        if (aspect < 0.70f || aspect > 1.40f) return null

        if (cache.containsKey(image)) {
            return cache[image]?.takeIf { it.isNotEmpty() }
        }
        val recovered = detect(image)
        cache[image] = recovered ?: ""
        return recovered
    }

    private fun detect(image: MonochromeImage): String? {
        decode(ImageLuminanceSource(image))?.let { return it }

        val integral = buildIntegral(image)
        for (pad in 0..MAX_PAD) {
            for (modules in MODULE_COUNTS) {
                val cells = sample(image, integral, pad, modules) ?: continue
                for (minBlack in BLACK_FRACTIONS) {
                    val grid = Array(modules) { y ->
                        BooleanArray(modules) { x -> cells[y][x] >= minBlack }
                    }
                    decode(GridLuminanceSource(grid, QUIET_ZONE))?.let { return it }
                }
            }
        }
        return null
    }

    /** Prefixed sum of black dots so each candidate cell is a constant-time lookup. */
    private fun buildIntegral(image: MonochromeImage): IntArray {
        val stride = image.width + 1
        val integral = IntArray(stride * (image.height + 1))
        for (y in 0 until image.height) {
            var row = 0
            val current = (y + 1) * stride
            val above = y * stride
            for (x in 0 until image.width) {
                if (image.isBlack(x, y)) row++
                integral[current + x + 1] = integral[above + x + 1] + row
            }
        }
        return integral
    }

    private fun sample(
        image: MonochromeImage,
        integral: IntArray,
        pad: Int,
        modules: Int,
    ): Array<DoubleArray>? {
        val paddedWidth = image.width + pad * 2
        val paddedHeight = image.height + pad * 2
        if (modules > paddedWidth || modules > paddedHeight) return null
        val stride = image.width + 1
        val result = Array(modules) { DoubleArray(modules) }
        for (my in 0 until modules) {
            val py0 = my * paddedHeight / modules
            val py1 = (my + 1) * paddedHeight / modules
            val iy0 = (py0 - pad).coerceIn(0, image.height)
            val iy1 = (py1 - pad).coerceIn(0, image.height)
            for (mx in 0 until modules) {
                val px0 = mx * paddedWidth / modules
                val px1 = (mx + 1) * paddedWidth / modules
                val ix0 = (px0 - pad).coerceIn(0, image.width)
                val ix1 = (px1 - pad).coerceIn(0, image.width)
                val area = (px1 - px0) * (py1 - py0)
                if (area <= 0 || ix1 <= ix0 || iy1 <= iy0) {
                    result[my][mx] = 0.0
                    continue
                }
                val black = integral[iy1 * stride + ix1] -
                    integral[iy0 * stride + ix1] -
                    integral[iy1 * stride + ix0] +
                    integral[iy0 * stride + ix0]
                result[my][mx] = black.toDouble() / area.toDouble()
            }
        }
        return result
    }

    private fun decode(source: LuminanceSource): String? = try {
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), HINTS)
        result.text?.takeIf { it.isNotBlank() && it.length <= MAX_TEXT }
    } catch (_: Exception) {
        null
    }

    private class ImageLuminanceSource(
        private val image: MonochromeImage,
    ) : LuminanceSource(image.width, image.height) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row != null && row.size >= width) row else ByteArray(width)
            for (x in 0 until width) out[x] = if (image.isBlack(x, y)) 0 else 255.toByte()
            return out
        }

        override fun getMatrix(): ByteArray {
            val out = ByteArray(width * height)
            var index = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[index++] = if (image.isBlack(x, y)) 0 else 255.toByte()
                }
            }
            return out
        }
    }

    private class GridLuminanceSource(
        private val grid: Array<BooleanArray>,
        private val border: Int,
    ) : LuminanceSource(grid.size + border * 2, grid.size + border * 2) {
        private fun blackAt(x: Int, y: Int): Boolean {
            val gx = x - border
            val gy = y - border
            return gx in grid.indices && gy in grid.indices && grid[gy][gx]
        }

        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row != null && row.size >= width) row else ByteArray(width)
            for (x in 0 until width) out[x] = if (blackAt(x, y)) 0 else 255.toByte()
            return out
        }

        override fun getMatrix(): ByteArray {
            val out = ByteArray(width * height)
            var index = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[index++] = if (blackAt(x, y)) 0 else 255.toByte()
                }
            }
            return out
        }
    }
}
