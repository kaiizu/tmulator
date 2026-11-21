package dev.tmulator.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tmulator.data.JobSource
import dev.tmulator.data.PrintJobSummary
import dev.tmulator.ui.theme.Ink
import dev.tmulator.ui.theme.Paper
import dev.tmulator.util.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
@Composable
fun JobsScreen(
    jobs: List<PrintJobSummary>,
    canTest: Boolean,
    onOpenJob: (PrintJobSummary) -> Unit,
    onDeleteJob: (PrintJobSummary) -> Unit,
    onClearHistory: () -> Unit,
    onTest: () -> Unit,
    onSnackbar: (String) -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Print history", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (jobs.isEmpty()) "Captured receipts will appear here" else "${jobs.size} saved receipt${if (jobs.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (jobs.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
            }
        }
        if (jobs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyHint(
                        title = "No receipts yet",
                        body = if (canTest) {
                            "Send a receipt from your POS app or print the built-in test job. The full pipeline is exercised over TCP 9100."
                        } else {
                            "Enable network emulation in Settings, then send a receipt from your POS app."
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onTest, enabled = canTest) {
                        Icon(Icons.Rounded.Print, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Print test receipt")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(jobs, key = { it.id }) { job ->
                    JobCard(job, onClick = { onOpenJob(job) })
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear print history?") },
            text = { Text("This removes all saved receipt images and metadata from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearHistory()
                    onSnackbar("Print history cleared")
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun JobCard(job: PrintJobSummary, onClick: () -> Unit) {
    val context = LocalContext.current
    val file = remember(job.previewFile) { ShareUtils.latestJobFile(context, job.previewFile) }
    val thumb = rememberJobBitmap(file, 160)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .width(56.dp)
                    .height(74.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Paper),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Rounded.History, null, tint = StatusGrey, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (job.source == JobSource.NETWORK) Icons.Rounded.Wifi else Icons.Rounded.Bluetooth,
                        null,
                        tint = if (job.source == JobSource.NETWORK) MaterialTheme.colorScheme.primary else dev.tmulator.ui.theme.Moss,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (job.source == JobSource.NETWORK) "Network" else "Bluetooth",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatTime(job.startedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    job.remote,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(formatBytes(job.bytes))
                        append(" · ${job.blocks} blocks")
                        if (job.hadCut) append(" · cut")
                        if (job.unsupportedCommands > 0) append(" · ${job.unsupportedCommands} unsupported")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (job.textPreview.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        job.textPreview.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun JobDetailScreen(
    job: PrintJobSummary,
    onBack: () -> Unit,
    onDelete: (PrintJobSummary) -> Unit,
    onSnackbar: (String) -> Unit,
) {
    val context = LocalContext.current
    val file = remember(job.previewFile) { ShareUtils.latestJobFile(context, job.previewFile) }
    val bitmap = rememberJobBitmap(file, 1400)
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }

    val share: () -> Unit = {
        val f = file
        if (f == null) {
            onSnackbar("No receipt image saved")
        } else {
            scope.launch {
                ShareUtils.shareFile(context, f).onFailure {
                    onSnackbar("Could not share receipt: ${it.message ?: "unknown error"}")
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text("Receipt", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatTime(job.startedAt)} · ${job.remote}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete receipt", tint = MaterialTheme.colorScheme.error)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { showText = !showText }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (showText) "Hide text" else "Captured text")
            }
            OutlinedButton(onClick = share, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Share, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Share")
            }
        }

        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Ink,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(Modifier.fillMaxSize().padding(10.dp)) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Paper)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Saved receipt",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth,
                                filterQuality = FilterQuality.None,
                            )
                        } else {
                            Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                                Text("Receipt image unavailable", color = Color.Gray)
                            }
                        }
                    }
                    if (showText && job.textPreview.isNotBlank()) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
                            Text(
                                job.textPreview,
                                modifier = Modifier.padding(10.dp).height(90.dp).verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${formatBytes(job.bytes)} · ${job.blocks} blocks · ${job.profileName}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this receipt?") },
            text = { Text("The saved image and metadata will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(job)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun rememberJobBitmap(file: File?, targetWidth: Int): Bitmap? {
    val result = remember(file, targetWidth) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file, targetWidth) {
        result.value = withContext(Dispatchers.IO) {
            if (file == null) null else decodeSampled(file, targetWidth)
        }
    }
    return result.value
}

private fun decodeSampled(file: File, targetWidth: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
    } catch (_: Throwable) {
        null
    }
}
