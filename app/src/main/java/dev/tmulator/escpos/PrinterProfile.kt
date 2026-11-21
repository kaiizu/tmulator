package dev.tmulator.escpos

/**
 * Selectable emulation profiles. All use 203 dpi-class metrics; model ID values
 * are approximate and only used to answer GS I / status identification.
 */
enum class PrinterProfile(
    val displayName: String,
    val paperWidthDots: Int,
    val paperMillimetres: Int,
    val modelId: Int,
    val supportsEpsonExtensions: Boolean,
    val defaultCodePage: CodePage = CodePage.CP437,
) {
    TM_M30(
        displayName = "Epson TM-m30",
        paperWidthDots = 576,
        paperMillimetres = 80,
        modelId = 0x01,
        supportsEpsonExtensions = true,
    ),
    TM_M30III(
        displayName = "Epson TM-m30III",
        paperWidthDots = 576,
        paperMillimetres = 80,
        modelId = 0x01,
        supportsEpsonExtensions = true,
    ),
    TM_T88VI(
        displayName = "Epson TM-T88VI",
        paperWidthDots = 576,
        paperMillimetres = 80,
        modelId = 0x01,
        supportsEpsonExtensions = true,
    ),
    GENERIC_80(
        displayName = "Generic 80 mm ESC/POS",
        paperWidthDots = 576,
        paperMillimetres = 80,
        modelId = 0x20,
        supportsEpsonExtensions = false,
    ),
    GENERIC_58(
        displayName = "Generic 58 mm ESC/POS",
        paperWidthDots = 384,
        paperMillimetres = 58,
        modelId = 0x20,
        supportsEpsonExtensions = false,
    ),
    ;

    val wireModelName: String
        get() = when (this) {
            TM_M30 -> "TM-m30"
            TM_M30III -> "TM-m30III"
            TM_T88VI -> "TM-T88VI"
            GENERIC_80, GENERIC_58 -> "TM-m30"
        }

    companion object {
        fun fromId(id: String): PrinterProfile = entries.firstOrNull { it.name == id } ?: TM_M30III
    }
}

data class PrinterStatus(
    val paperOut: Boolean = false,
    val coverOpen: Boolean = false,
    val drawerOpen: Boolean = false,
)
