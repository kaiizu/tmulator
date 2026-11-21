package dev.tmulator.escpos

import java.nio.charset.Charset

/** 1-bit monochrome bitmap: bit 1 = black dot, rows padded to a whole byte. */
class MonochromeImage(
    val width: Int,
    val height: Int,
    private val rowBytes: Int,
    private val data: ByteArray,
) {
    init {
        require(width >= 0 && height >= 0) { "Invalid image size ${width}x$height" }
        require(data.size >= rowBytes * height) { "Image buffer too small" }
    }

    val sizeBytes: Int get() = data.size

    fun isBlack(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        val index = y * rowBytes + (x ushr 3)
        return (data[index].toInt() and (0x80 ushr (x and 7))) != 0
    }

    fun scaled(horizontal: Int, vertical: Int): MonochromeImage {
        if (horizontal == 1 && vertical == 1) return this
        val newWidth = width * horizontal
        val newHeight = height * vertical
        val newRowBytes = (newWidth + 7) / 8
        val out = ByteArray(newRowBytes * newHeight)
        for (y in 0 until newHeight) {
            val sourceY = y / vertical
            for (x in 0 until newWidth) {
                if (isBlack(x / horizontal, sourceY)) {
                    out[y * newRowBytes + (x ushr 3)] =
                        (out[y * newRowBytes + (x ushr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        return MonochromeImage(newWidth, newHeight, newRowBytes, out)
    }

    /**
     * Reassembles a raster image that the driver split into horizontal bands.
     * Bands must share the same width and bit packing.
     */
    fun concatVertical(other: MonochromeImage): MonochromeImage? {
        if (width != other.width || rowBytes != other.rowBytes) return null
        val newHeight = height + other.height
        if (newHeight <= 0 || newHeight > MAX_CONCAT_HEIGHT) return null
        val out = ByteArray(rowBytes * newHeight)
        System.arraycopy(data, 0, out, 0, rowBytes * height)
        System.arraycopy(other.data, 0, out, rowBytes * height, rowBytes * other.height)
        return MonochromeImage(width, newHeight, rowBytes, out)
    }

    companion object {
        private const val MAX_CONCAT_HEIGHT = 4096
        /**
         * @param pixels row-major, one bit per dot, bit 1 = black, each row padded to a byte.
         */
        fun fromRaster(width: Int, height: Int, pixels: ByteArray, offset: Int = 0): MonochromeImage {
            val rowBytes = (width + 7) / 8
            val size = rowBytes * height
            require(width > 0 && height > 0) { "Empty raster image" }
            require(pixels.size - offset >= size) { "Raster data truncated" }
            return MonochromeImage(width, height, rowBytes, pixels.copyOfRange(offset, offset + size))
        }

        /** Builds an image from 8- or 24-dot column data (ESC K / ESC * style). */
        fun fromColumns(widthDots: Int, heightDots: Int, columns: ByteArray): MonochromeImage {
            require(widthDots > 0 && heightDots > 0)
            val rowBytes = (widthDots + 7) / 8
            val out = ByteArray(rowBytes * heightDots)
            val bytesPerColumn = if (heightDots <= 8) 1 else 3
            for (x in 0 until widthDots) {
                for (bit in 0 until heightDots) {
                    val byteIndex = x * bytesPerColumn + (bit / 8)
                    if (byteIndex >= columns.size) break
                    val on = (columns[byteIndex].toInt() and (0x80 ushr (bit % 8))) != 0
                    if (on) {
                        out[bit * rowBytes + (x ushr 3)] =
                            (out[bit * rowBytes + (x ushr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                }
            }
            return MonochromeImage(widthDots, heightDots, rowBytes, out)
        }
    }
}

enum class FontStyle(val widthDots: Int, val heightDots: Int) {
    A(12, 24),
    B(9, 17),
}

enum class Align { LEFT, CENTER, RIGHT }

enum class HriPosition { NONE, ABOVE, BELOW }

enum class BarcodeSymbology {
    UPC_A,
    UPC_E,
    EAN_13,
    EAN_8,
    CODE_39,
    CODE_93,
    CODE_128,
    ITF,
    CODABAR,
}

enum class QrErrorCorrection { L, M, Q, H }

data class TextStyle(
    val font: FontStyle = FontStyle.A,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val doubleStrike: Boolean = false,
    val reverse: Boolean = false,
    val italic: Boolean = false,
    val widthMultiplier: Int = 1,
    val heightMultiplier: Int = 1,
    val characterSpacingDots: Int = 0,
    val absoluteXDots: Int? = null,
) {
    val cellWidthDots: Int get() = font.widthDots * widthMultiplier + characterSpacingDots
    val cellHeightDots: Int get() = font.heightDots * heightMultiplier
}

data class TextRun(val text: String, val style: TextStyle = TextStyle())

sealed interface ReceiptBlock {
    data class Text(
        val runs: List<TextRun>,
        val align: Align = Align.LEFT,
        val lineSpacingDots: Int = 30,
    ) : ReceiptBlock

    data class Image(
        val image: MonochromeImage,
        val align: Align = Align.LEFT,
    ) : ReceiptBlock

    data class Barcode(
        val symbology: BarcodeSymbology,
        val data: String,
        val heightDots: Int,
        val moduleWidth: Int,
        val hri: HriPosition,
        val align: Align,
    ) : ReceiptBlock

    data class QrCode(
        val data: String,
        val moduleSize: Int,
        val errorCorrection: QrErrorCorrection,
        val align: Align,
    ) : ReceiptBlock

    data class Feed(val dots: Int) : ReceiptBlock

    data class Cut(val partial: Boolean) : ReceiptBlock
}

data class ReceiptSnapshot(
    val blocks: List<ReceiptBlock>,
    val revision: Long,
    val paperWidthDots: Int,
) {
    val isEmpty: Boolean get() = blocks.isEmpty()

    val cutCount: Int get() = blocks.count { it is ReceiptBlock.Cut }

    fun plainText(): String {
        val out = StringBuilder()
        for (block in blocks) {
            when (block) {
                is ReceiptBlock.Text -> {
                    block.runs.forEach { out.append(it.text) }
                    out.append('\n')
                }
                is ReceiptBlock.Barcode -> out.append(block.data).append('\n')
                is ReceiptBlock.QrCode -> out.append(block.data).append('\n')
                is ReceiptBlock.Cut -> out.append("--- cut ---\n")
                is ReceiptBlock.Feed, is ReceiptBlock.Image -> Unit
            }
        }
        return out.toString().trimEnd()
    }
}

/** Accumulates a receipt while ESC/POS bytes are parsed. */
class ReceiptDocument(val paperWidthDots: Int) {
    private val blocks = ArrayList<ReceiptBlock>()
    private var pending: ReceiptBlock.Text? = null
    private var textChars = 0
    private var imageBytes = 0L

    var overflowed: Boolean = false
        private set

    var revision: Long = 0
        private set

    fun snapshot(): ReceiptSnapshot {
        val all = ArrayList<ReceiptBlock>(blocks.size + 1)
        all.addAll(blocks)
        pending?.let { all.add(it) }
        return ReceiptSnapshot(all, revision, paperWidthDots)
    }

    val blockCount: Int get() = blocks.size + if (pending != null) 1 else 0

    fun add(block: ReceiptBlock) {
        if (block is ReceiptBlock.Image) {
            val previous = blocks.lastOrNull() as? ReceiptBlock.Image
            if (previous != null &&
                previous.align == block.align &&
                previous.image.width == block.image.width &&
                (previous.image.height <= MAX_IMAGE_BAND_HEIGHT || block.image.height <= MAX_IMAGE_BAND_HEIGHT)
            ) {
                val merged = previous.image.concatVertical(block.image)
                if (merged != null) {
                    val oldBytes = previous.image.sizeBytes.toLong()
                    val newBytes = merged.sizeBytes.toLong()
                    if (imageBytes - oldBytes + newBytes <= MAX_IMAGE_BYTES) {
                        imageBytes = imageBytes - oldBytes + newBytes
                        blocks[blocks.lastIndex] = ReceiptBlock.Image(merged, block.align)
                        revision++
                    } else {
                        overflowed = true
                    }
                    return
                }
            }
        }
        if (blocks.size >= MAX_BLOCKS) {
            overflowed = true
            return
        }
        when (block) {
            is ReceiptBlock.Text -> {
                val chars = block.runs.sumOf { it.text.length }
                if (textChars + chars > MAX_TEXT_CHARS) {
                    overflowed = true
                    return
                }
                textChars += chars
            }
            is ReceiptBlock.Image -> {
                val bytes = block.image.sizeBytes.toLong()
                if (imageBytes + bytes > MAX_IMAGE_BYTES) {
                    overflowed = true
                    return
                }
                imageBytes += bytes
            }
            else -> Unit
        }
        blocks.add(block)
        revision++
    }

    fun setPendingLine(runs: List<TextRun>, align: Align, lineSpacingDots: Int) {
        var remaining = (MAX_TEXT_CHARS - textChars).coerceAtLeast(0)
        val bounded = ArrayList<TextRun>(runs.size)
        for (run in runs) {
            if (run.text.isEmpty()) continue
            if (remaining <= 0) {
                overflowed = true
                break
            }
            val text = if (run.text.length > remaining) {
                overflowed = true
                run.text.take(remaining)
            } else {
                run.text
            }
            if (text.isNotEmpty()) {
                bounded += run.copy(text = text)
                remaining -= text.length
            }
        }
        val newPending = if (bounded.isEmpty()) null else ReceiptBlock.Text(bounded, align, lineSpacingDots)
        if (newPending != pending) {
            pending = newPending
            revision++
        }
    }

    fun commitPending(align: Align, lineSpacingDots: Int) {
        val line = pending ?: ReceiptBlock.Text(emptyList(), align, lineSpacingDots)
        val chars = line.runs.sumOf { it.text.length }
        if (blocks.size >= MAX_BLOCKS || textChars + chars > MAX_TEXT_CHARS) {
            overflowed = true
        } else {
            blocks.add(line)
            textChars += chars
            revision++
        }
        pending = null
    }

    fun clear() {
        blocks.clear()
        pending = null
        textChars = 0
        imageBytes = 0
        overflowed = false
        revision++
    }

    companion object {
        private const val MAX_BLOCKS = 5000
        private const val MAX_TEXT_CHARS = 1_000_000
        private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        private const val MAX_IMAGE_BAND_HEIGHT = 64
    }
}
