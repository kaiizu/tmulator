package dev.tmulator.escpos

import java.nio.charset.Charset

/**
 * Epson ESC t code page selection. Only the single-byte pages that are common
 * on 80 mm receipt printers are mapped; unknown pages fall back to CP437.
 */
enum class CodePage(
    val escTValues: Set<Int>,
    val displayName: String,
    private val charsetName: String,
) {
    CP437(setOf(0, 255), "CP437 (US)", "IBM437"),
    CP850(setOf(2), "CP850 (Latin-1)", "IBM850"),
    CP852(setOf(18, 24), "CP852 (Latin-2)", "IBM852"),
    CP858(setOf(19), "CP858 (Euro)", "IBM00858"),
    CP860(setOf(3), "CP860 (Portuguese)", "IBM860"),
    CP863(setOf(4), "CP863 (Canadian)", "IBM863"),
    CP865(setOf(5), "CP865 (Nordic)", "IBM865"),
    CP866(setOf(17), "CP866 (Cyrillic)", "IBM866"),
    WINDOWS_1252(setOf(16), "Windows-1252", "windows-1252"),
    ;

    private val charset: Charset? by lazy { runCatching { Charset.forName(charsetName) }.getOrNull() }

    fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        if (length <= 0) return ""
        val cs = charset
        return if (cs != null) {
            String(bytes, offset, length, cs)
        } else {
            String(bytes, offset, length, Charsets.ISO_8859_1)
        }
    }

    companion object {
        fun fromEscT(value: Int): CodePage? = entries.firstOrNull { value in it.escTValues }

        fun defaultFor(profile: PrinterProfile): CodePage = profile.defaultCodePage
    }
}
