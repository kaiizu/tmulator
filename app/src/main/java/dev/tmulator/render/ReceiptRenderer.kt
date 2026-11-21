package dev.tmulator.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.tmulator.escpos.Align
import dev.tmulator.escpos.BarcodeSymbology
import dev.tmulator.escpos.HriPosition
import dev.tmulator.escpos.MonochromeImage
import dev.tmulator.escpos.QrErrorCorrection
import dev.tmulator.escpos.QrRasterRecovery
import dev.tmulator.escpos.ReceiptBlock
import dev.tmulator.escpos.ReceiptSnapshot
import dev.tmulator.escpos.TextRun
import dev.tmulator.escpos.TextStyle
import kotlin.math.max
import kotlin.math.min

data class RenderOptions(
    val maxHeightDots: Int = 14_000,
)

data class RenderedReceipt(
    val bitmap: Bitmap,
    val warnings: List<String>,
)

/**
 * Draws a receipt into a 1-bit-style thermal raster. Text is positioned on a
 * fixed monospace dot grid so alignment and wrapping follow printer metrics
 * rather than Android font metrics.
 */
class ReceiptRenderer {

    private val ink = Color.rgb(22, 20, 17)
    private val paper = Color.rgb(252, 249, 242)
    private val paperEdge = Color.rgb(238, 231, 217)
    private val paintCache = HashMap<TextStyle, Paint>()
    private val warnings = ArrayList<String>()

    fun render(snapshot: ReceiptSnapshot, options: RenderOptions = RenderOptions()): RenderedReceipt {
        warnings.clear()
        paintCache.clear()

        val contentWidth = snapshot.paperWidthDots
        val side = if (contentWidth <= 400) 40 else 32
        val paperWidth = contentWidth + side * 2
        val top = 28

        val layouts = ArrayList<TextLayout?>(snapshot.blocks.size)
        var measured = top
        for (block in snapshot.blocks) {
            if (block is ReceiptBlock.Text) {
                val layout = layoutText(block, contentWidth)
                layouts.add(layout)
                measured += layout.height
            } else {
                layouts.add(null)
                measured += blockHeight(block, contentWidth)
            }
        }
        val bottom = 52
        var height = max(measured + bottom, 380)
        if (height > options.maxHeightDots) {
            warnings.add("Receipt truncated at ${options.maxHeightDots} px for preview")
            height = options.maxHeightDots
        }

        val bitmap = Bitmap.createBitmap(paperWidth, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(paper)

        val edgePaint = Paint()
        edgePaint.shader = LinearGradient(
            0f, 0f, 12f, 0f,
            intArrayOf(0x18000000, 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, 12f, height.toFloat(), edgePaint)
        edgePaint.shader = LinearGradient(
            paperWidth.toFloat(), 0f, paperWidth - 12f, 0f,
            intArrayOf(0x18000000, 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(paperWidth - 12f, 0f, paperWidth.toFloat(), height.toFloat(), edgePaint)
        edgePaint.shader = null
        edgePaint.color = paperEdge
        edgePaint.strokeWidth = 1f
        canvas.drawLine(1f, 0f, 1f, height.toFloat(), edgePaint)
        canvas.drawLine(paperWidth - 1f, 0f, paperWidth - 1f, height.toFloat(), edgePaint)

        var y = top
        for ((index, block) in snapshot.blocks.withIndex()) {
            if (y >= height - 8) break
            y = when (block) {
                is ReceiptBlock.Text -> drawText(canvas, block, layouts[index], side, y, contentWidth)
                is ReceiptBlock.Image -> drawImage(canvas, block, side, y, contentWidth)
                is ReceiptBlock.Barcode -> drawBarcode(canvas, block, side, y, contentWidth)
                is ReceiptBlock.QrCode -> drawQr(canvas, block, side, y, contentWidth)
                is ReceiptBlock.Feed -> y + block.dots
                is ReceiptBlock.Cut -> drawCut(canvas, block, side, y, contentWidth)
            }
        }

        return RenderedReceipt(bitmap, warnings.toList())
    }

    // --------------------------------------------------------------- measuring

    private fun blockHeight(block: ReceiptBlock, contentWidth: Int): Int = when (block) {
        is ReceiptBlock.Text -> layoutText(block, contentWidth).height
        is ReceiptBlock.Image -> {
            val target = imageTarget(block.image, contentWidth)
            target.second + 2
        }
        is ReceiptBlock.Barcode -> block.heightDots + if (block.hri == HriPosition.NONE) 12 else 36
        is ReceiptBlock.QrCode -> {
            val modules = qrMatrix(block)?.width ?: 21
            min(contentWidth, modules * block.moduleSize) + 16
        }
        is ReceiptBlock.Feed -> block.dots
        is ReceiptBlock.Cut -> 34
    }

    private fun imageTarget(image: MonochromeImage, contentWidth: Int): Pair<Int, Int> {
        val scale = min(1f, contentWidth.toFloat() / image.width.toFloat())
        return (image.width * scale).toInt().coerceAtLeast(1) to
            (image.height * scale).toInt().coerceAtLeast(1)
    }

    private fun layoutText(block: ReceiptBlock.Text, contentWidth: Int): TextLayout {
        if (block.runs.isEmpty()) {
            return TextLayout(listOf(VisualLine(emptyList(), 0, 0, false)), block.lineSpacingDots)
        }

        val hasAbsolute = block.runs.any { it.style.absoluteXDots != null }
        if (hasAbsolute) {
            val glyphs = ArrayList<Glyph>()
            var x = 0
            var maxHeight = 0
            for (run in block.runs) {
                val absolute = run.style.absoluteXDots
                if (absolute != null) x = absolute
                for (ch in run.text) {
                    val cell = run.style.cellWidthDots
                    glyphs.add(Glyph(ch, run.style, x))
                    x += cell
                    maxHeight = max(maxHeight, run.style.cellHeightDots)
                }
            }
            val line = VisualLine(glyphs, x, maxHeight, true)
            return TextLayout(listOf(line), line.advance(block.lineSpacingDots))
        }

        val lines = ArrayList<VisualLine>()
        var glyphs = ArrayList<Glyph>()
        var width = 0
        var maxHeight = 0
        var gapBefore = false

        fun finishLine() {
            if (glyphs.isNotEmpty() || lines.isEmpty()) {
                lines.add(VisualLine(glyphs, width, maxHeight, false))
            }
            glyphs = ArrayList()
            width = 0
            maxHeight = 0
            gapBefore = false
        }

        for (run in block.runs) {
            for (ch in run.text) {
                val cell = run.style.cellWidthDots
                if (width > 0 && width + cell > contentWidth) {
                    finishLine()
                }
                glyphs.add(Glyph(ch, run.style, width))
                width += cell
                maxHeight = max(maxHeight, run.style.cellHeightDots)
                gapBefore = false
            }
        }
        finishLine()
        val total = lines.sumOf { it.advance(block.lineSpacingDots) }
        return TextLayout(lines, total)
    }

    private fun drawText(
        canvas: Canvas,
        block: ReceiptBlock.Text,
        layout: TextLayout?,
        left: Int,
        top: Int,
        contentWidth: Int,
    ): Int {
        val lines = layout?.lines ?: return top
        var baselineTop = top
        for (line in lines) {
            val advance = line.advance(block.lineSpacingDots)
            val alignOffset = when {
                line.absolute -> 0
                block.align == Align.CENTER -> ((contentWidth - line.width) / 2).coerceAtLeast(0)
                block.align == Align.RIGHT -> (contentWidth - line.width).coerceAtLeast(0)
                else -> 0
            }
            val baseline = baselineTop + (advance - line.maxHeight) / 2 + (line.maxHeight * 0.79f)
            for (glyph in line.glyphs) {
                val paint = paintFor(glyph.style)
                val x = (left + alignOffset + glyph.x).toFloat()
                val cellW = glyph.style.cellWidthDots.toFloat()
                val cellH = glyph.style.cellHeightDots.toFloat()
                if (glyph.style.reverse) {
                    canvas.drawRect(x, baselineTop.toFloat(), x + cellW, baselineTop + cellH, fillPaint())
                    paint.color = paper
                } else {
                    paint.color = ink
                }
                paint.alpha = 255
                canvas.drawText(glyph.ch.toString(), x + cellW / 2f, baseline, paint)
                if (glyph.style.doubleStrike) {
                    canvas.drawText(glyph.ch.toString(), x + cellW / 2f + 1.1f, baseline, paint)
                }
                if (glyph.style.underline) {
                    val underlinePaint = fillPaint()
                    underlinePaint.color = if (glyph.style.reverse) paper else ink
                    val uy = (baseline + 2f).coerceAtMost((baselineTop + cellH).coerceAtLeast(baseline + 1f))
                    canvas.drawRect(x, uy, x + cellW, uy + 1.6f, underlinePaint)
                }
            }
            baselineTop += advance
        }
        return baselineTop
    }

    private fun paintFor(style: TextStyle): Paint {
        val key = style.copy(absoluteXDots = null)
        return paintCache.getOrPut(key) {
            val cellHeight = style.cellHeightDots.toFloat()
            val targetWidth = (style.font.widthDots * style.widthMultiplier).toFloat()
            val paint = Paint().apply {
                isAntiAlias = true
                isDither = false
                typeface = Typeface.MONOSPACE
                textSize = cellHeight * 0.84f
                textScaleX = 1f
                textSkewX = if (style.italic) -0.18f else 0f
                isFakeBoldText = style.bold
                color = ink
                textAlign = Paint.Align.CENTER
            }
            val measured = paint.measureText("M").coerceAtLeast(1f)
            paint.textScaleX = (targetWidth / measured).coerceIn(0.2f, 8f)
            paint
        }
    }

    // ------------------------------------------------------------------ images

    private fun drawImage(
        canvas: Canvas,
        block: ReceiptBlock.Image,
        left: Int,
        top: Int,
        contentWidth: Int,
    ): Int {
        val image = block.image
        val scale = min(1f, contentWidth.toFloat() / image.width.toFloat())
        val targetWidth = (image.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (image.height * scale).toInt().coerceAtLeast(1)
        if (image.width.toLong() * image.height > MAX_IMAGE_DOTS) {
            warnings.add("Skipped oversized image ${image.width}x${image.height}")
            return top
        }
        if (targetWidth > 2048 || targetHeight > 12_000) {
            warnings.add("Skipped oversized image ${image.width}x${image.height}")
            return top
        }

        // Some POS apps rasterize QR codes with a halftone screen. Recover the
        // payload and draw a clean, scannable QR instead of the noisy bitmap.
        val qrText = QrRasterRecovery.recover(image)
        if (qrText != null) {
            val matrix = qrMatrix(qrText, QrErrorCorrection.M)
            if (matrix != null && matrix.width <= targetWidth && matrix.height <= targetHeight) {
                val module = max(1, min(targetWidth, targetHeight) / matrix.width)
                val size = matrix.width * module
                val imageX = when (block.align) {
                    Align.LEFT -> left
                    Align.CENTER -> left + (contentWidth - targetWidth) / 2
                    Align.RIGHT -> left + contentWidth - targetWidth
                }
                drawQrMatrix(
                    canvas = canvas,
                    matrix = matrix,
                    x0 = imageX + (targetWidth - size) / 2,
                    y0 = top + (targetHeight - size) / 2,
                    module = module,
                )
                return top + targetHeight + 2
            }
        }

        val source = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(image.width * image.height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.isBlack(x, y)) pixels[y * image.width + x] = ink
            }
        }
        source.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val target = if (targetWidth != image.width || targetHeight != image.height) {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, false).also { source.recycle() }
        } else {
            source
        }
        val x = when (block.align) {
            Align.LEFT -> left
            Align.CENTER -> left + (contentWidth - targetWidth) / 2
            Align.RIGHT -> left + contentWidth - targetWidth
        }
        canvas.drawBitmap(target, x.toFloat(), top.toFloat(), null)
        target.recycle()
        return top + targetHeight + 2
    }

    // ---------------------------------------------------------------- barcodes

    private fun drawBarcode(
        canvas: Canvas,
        block: ReceiptBlock.Barcode,
        left: Int,
        top: Int,
        contentWidth: Int,
    ): Int {
        val matrix = try {
            val format = when (block.symbology) {
                BarcodeSymbology.UPC_A -> BarcodeFormat.UPC_A
                BarcodeSymbology.UPC_E -> BarcodeFormat.UPC_E
                BarcodeSymbology.EAN_13 -> BarcodeFormat.EAN_13
                BarcodeSymbology.EAN_8 -> BarcodeFormat.EAN_8
                BarcodeSymbology.CODE_39 -> BarcodeFormat.CODE_39
                BarcodeSymbology.CODE_93 -> BarcodeFormat.CODE_93
                BarcodeSymbology.CODE_128 -> BarcodeFormat.CODE_128
                BarcodeSymbology.ITF -> BarcodeFormat.ITF
                BarcodeSymbology.CODABAR -> BarcodeFormat.CODABAR
            }
            val hints = mapOf(EncodeHintType.MARGIN to 4)
            MultiFormatWriter().encode(block.data, format, 1, 1, hints)
        } catch (t: Throwable) {
            warnings.add("Barcode ${block.symbology} failed: ${t.message}")
            return drawFallbackText(canvas, "[${block.symbology.name}: ${block.data}]", left, top, contentWidth)
        }

        var module = block.moduleWidth.coerceAtLeast(1)
        if (matrix.width * module > contentWidth) {
            module = max(1, contentWidth / matrix.width)
        }
        val width = matrix.width * module
        val x0 = when (block.align) {
            Align.LEFT -> left
            Align.CENTER -> left + (contentWidth - width) / 2
            Align.RIGHT -> left + contentWidth - width
        }
        val barTop = if (block.hri == HriPosition.ABOVE) top + 20 else top
        val paint = fillPaint()
        paint.color = ink
        for (x in 0 until matrix.width) {
            if (!matrix.get(x, 0)) continue
            canvas.drawRect(
                (x0 + x * module).toFloat(),
                barTop.toFloat(),
                (x0 + x * module + module).toFloat(),
                (barTop + block.heightDots).toFloat(),
                paint,
            )
        }
        if (block.hri != HriPosition.NONE) {
            val hriY = if (block.hri == HriPosition.ABOVE) top + 15f else (barTop + block.heightDots + 16).toFloat()
            drawHri(canvas, block.data, x0, hriY, width)
        }
        return barTop + block.heightDots + if (block.hri == HriPosition.NONE) 12 else 36
    }

    private fun drawHri(canvas: Canvas, text: String, x: Int, baseline: Float, width: Int) {
        val p = Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            textSize = 17f
            color = ink
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, x + width / 2f, baseline, p)
    }

    private fun drawFallbackText(
        canvas: Canvas,
        text: String,
        left: Int,
        top: Int,
        contentWidth: Int,
    ): Int {
        val p = Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            textSize = 18f
            color = ink
        }
        canvas.drawText(text.take(46), left.toFloat(), top + 20f, p)
        return top + 30
    }

    // --------------------------------------------------------------------- QR

    private fun qrMatrix(block: ReceiptBlock.QrCode): BitMatrix? =
        qrMatrix(block.data, block.errorCorrection)

    private fun qrMatrix(data: String, errorCorrection: QrErrorCorrection): BitMatrix? {
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.valueOf(errorCorrection.name),
                EncodeHintType.MARGIN to 4,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            MultiFormatWriter().encode(data, BarcodeFormat.QR_CODE, 1, 1, hints)
        } catch (t: Throwable) {
            null
        }
    }

    private fun drawQr(
        canvas: Canvas,
        block: ReceiptBlock.QrCode,
        left: Int,
        top: Int,
        contentWidth: Int,
    ): Int {
        val matrix = qrMatrix(block)
        if (matrix == null) {
            warnings.add("QR encoding failed for \"${block.data.take(32)}\"")
            return drawFallbackText(canvas, "[QR: ${block.data.take(40)}]", left, top, contentWidth)
        }
        var module = block.moduleSize.coerceIn(1, 16)
        if (matrix.width * module > contentWidth) module = max(1, contentWidth / matrix.width)
        val size = matrix.width * module
        val x0 = when (block.align) {
            Align.LEFT -> left
            Align.CENTER -> left + (contentWidth - size) / 2
            Align.RIGHT -> left + contentWidth - size
        }
        drawQrMatrix(canvas, matrix, x0, top, module)
        return top + size + 16
    }

    private fun drawQrMatrix(
        canvas: Canvas,
        matrix: BitMatrix,
        x0: Int,
        y0: Int,
        module: Int,
    ) {
        val p = fillPaint()
        p.color = ink
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    canvas.drawRect(
                        (x0 + x * module).toFloat(),
                        (y0 + y * module).toFloat(),
                        (x0 + x * module + module).toFloat(),
                        (y0 + y * module + module).toFloat(),
                        p,
                    )
                }
            }
        }
    }

    // --------------------------------------------------------------------- cut

    private fun drawCut(
        canvas: Canvas,
        block: ReceiptBlock.Cut,
        left: Int,
        top: Int,
        contentWidth: Int,
    ): Int {
        val y = top + 14f
        val start = if (block.partial) left + contentWidth * 0.12f else left.toFloat()
        val end = if (block.partial) left + contentWidth * 0.88f else (left + contentWidth).toFloat()
        val p = Paint().apply {
            isAntiAlias = false
            color = Color.rgb(120, 112, 100)
            strokeWidth = 1.6f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(7f, 5f), 0f)
        }
        canvas.drawLine(start, y, end, y, p)
        p.pathEffect = null
        p.strokeWidth = 1f
        canvas.drawLine(left.toFloat(), y + 1f, (left + contentWidth).toFloat(), y + 1f, p)
        return top + 34
    }

    private fun fillPaint(): Paint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }

    // ------------------------------------------------------------------ layout

    private data class Glyph(val ch: Char, val style: TextStyle, val x: Int)

    private data class VisualLine(
        val glyphs: List<Glyph>,
        val width: Int,
        val maxHeight: Int,
        val absolute: Boolean,
    ) {
        fun advance(lineSpacing: Int): Int = if (maxHeight == 0) lineSpacing else max(lineSpacing, maxHeight + 4)
    }

    private data class TextLayout(val lines: List<VisualLine>, val height: Int)

    companion object {
        private const val MAX_IMAGE_DOTS = 4_000_000L
    }
}
