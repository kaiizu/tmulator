package dev.tmulator.data

enum class JobSource { NETWORK, BLUETOOTH }

data class PrintJobSummary(
    val id: Long,
    val startedAt: Long,
    val finishedAt: Long,
    val source: JobSource,
    val remote: String,
    val bytes: Long,
    val blocks: Int,
    val hadCut: Boolean,
    val previewFile: String?,
    val previewWidth: Int,
    val previewHeight: Int,
    val textPreview: String,
    val profileName: String,
    val unsupportedCommands: Int,
)
