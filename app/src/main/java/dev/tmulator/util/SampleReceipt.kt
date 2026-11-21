package dev.tmulator.util

import java.io.ByteArrayOutputStream

/** A realistic ESC/POS job used by the in-app "test print" action and unit tests. */
object SampleReceipt {
    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        fun raw(vararg bytes: Int) = bytes.forEach { out.write(it) }
        fun text(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
        fun cmd(vararg bytes: Int) = raw(*bytes)

        cmd(0x1B, 0x40) // initialize
        cmd(0x1B, 0x61, 1) // center
        cmd(0x1D, 0x21, 0x11) // double width + height
        cmd(0x1B, 0x45, 1)
        text("TMULATOR\n")
        cmd(0x1B, 0x45, 0)
        cmd(0x1D, 0x21, 0x00)
        cmd(0x1B, 0x45, 1)
        text("VIRTUAL RECEIPT PRINTER\n")
        cmd(0x1B, 0x45, 0)
        cmd(0x1B, 0x2D, 1)
        text("Epson TM-m30 / ESC-POS\n")
        cmd(0x1B, 0x2D, 0)

        // Raster decoration: validates GS v 0 end to end.
        val widthBytes = 48
        val height = 10
        cmd(0x1D, 0x76, 0x30, 0x00, widthBytes and 0xFF, widthBytes shr 8, height and 0xFF, height shr 8)
        for (y in 0 until height) {
            for (x in 0 until widthBytes) {
                val bits = when {
                    y == 0 || y == height - 1 -> 0xFF
                    y == 4 && x % 8 == 0 -> 0xF0
                    y == 5 && x % 8 == 4 -> 0x0F
                    else -> 0x00
                }
                out.write(bits)
            }
        }

        cmd(0x0A)
        cmd(0x1B, 0x61, 0) // left
        text("Order      #1042\n")
        text("Date       2026-11-13 10:24\n")
        text("Server     Alex\n")
        text("Terminal   iPad POS / Table 7\n")
        text("------------------------------------------\n")
        text("2x  Espresso Tonic                 9.50\n")
        text("1x  Truffle Fries                  7.25\n")
        text("1x  Wagyu Burger                  18.00\n")
        text("1x  Sparkling Water                4.00\n")
        text("------------------------------------------\n")
        cmd(0x1B, 0x45, 1)
        text("TOTAL                             38.75\n")
        cmd(0x1B, 0x45, 0)
        text("VISA **** 4242                    38.75\n")
        cmd(0x1B, 0x61, 1)
        cmd(0x0A)

        // QR: model 2, 6-dot modules, ECC M, store + print.
        qr(out, "https://tmulator.local/r/1042", moduleSize = 6)

        cmd(0x0A)
        cmd(0x1D, 0x68, 0x50) // barcode height 80
        cmd(0x1D, 0x77, 0x02) // module width 2
        cmd(0x1D, 0x48, 0x02) // HRI below
        val code = "TM1042"
        cmd(0x1D, 0x6B, 73, code.length)
        text(code)
        cmd(0x0A)
        cmd(0x1B, 0x61, 0)
        text("Thank you for testing TMulator.\n")
        text("Network 9100 / ENPC 3289\n")
        cmd(0x0A)
        cmd(0x0A)
        cmd(0x1D, 0x56, 0x00) // full cut
        return out.toByteArray()
    }

    private fun qr(out: ByteArrayOutputStream, data: String, moduleSize: Int) {
        fun p(payload: ByteArray) {
            out.write(0x1D)
            out.write(0x28)
            out.write(0x6B)
            out.write(payload.size and 0xFF)
            out.write((payload.size shr 8) and 0xFF)
            out.write(payload)
        }
        p(byteArrayOf(49, 65, 50)) // model 2
        p(byteArrayOf(49, 67, moduleSize.toByte())) // module size
        p(byteArrayOf(49, 69, 49)) // error correction M
        val bytes = data.toByteArray(Charsets.UTF_8)
        val store = ByteArrayOutputStream().apply {
            write(49)
            write(80)
            write(48)
            write(bytes)
        }.toByteArray()
        p(store)
        p(byteArrayOf(49, 81, 48)) // print
    }
}
