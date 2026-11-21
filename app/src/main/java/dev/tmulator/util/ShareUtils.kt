package dev.tmulator.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.tmulator.escpos.ReceiptSnapshot
import dev.tmulator.render.ReceiptRenderer
import dev.tmulator.render.RenderOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ShareUtils {
    suspend fun shareSnapshot(context: Context, snapshot: ReceiptSnapshot): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val rendered = ReceiptRenderer().render(snapshot, RenderOptions())
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
                val file = File(dir, "receipt-${System.currentTimeMillis()}.png")
                file.outputStream().use { rendered.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                shareFileNow(context, file)
                Result.success(Unit)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    suspend fun shareFile(context: Context, file: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) error("Receipt image no longer exists")
            shareFileNow(context, file)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun shareFileNow(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "TMulator receipt")
            clipData = ClipData.newRawUri("receipt", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            context.startActivity(Intent.createChooser(intent, "Share receipt"))
        }
    }

    fun copyText(context: Context, label: String, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun latestJobFile(context: Context, fileName: String?): File? {
        if (fileName.isNullOrBlank()) return null
        val file = File(File(context.filesDir, "jobs"), fileName)
        return if (file.exists()) file else null
    }
}
