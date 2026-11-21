package dev.tmulator.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Persists the last 20 rendered receipts as PNG + JSON metadata. */
class JobStore(
    private val context: Context,
    scope: CoroutineScope,
) {
    private val dir = File(context.filesDir, "jobs").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val mutex = Mutex()
    private val _jobs = MutableStateFlow<List<PrintJobSummary>>(emptyList())
    private val idCounter = AtomicLong(System.currentTimeMillis())

    val jobs: StateFlow<List<PrintJobSummary>> = _jobs.asStateFlow()

    init {
        scope.launch { load() }
    }

    fun newId(): Long {
        while (true) {
            val candidate = idCounter.incrementAndGet()
            if (_jobs.value.none { it.id == candidate }) return candidate
        }
    }

    fun fileFor(summary: PrintJobSummary): File? {
        val name = summary.previewFile ?: return null
        val file = File(dir, name)
        return if (file.exists()) file else null
    }

    suspend fun add(summary: PrintJobSummary, bitmap: Bitmap?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            var fileName: String? = null
            if (bitmap != null) {
                val candidate = "receipt_${summary.id}.png"
                try {
                    File(dir, candidate).outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    fileName = candidate
                } catch (t: Throwable) {
                    Log.e(TAG, "Could not save receipt PNG", t)
                }
            }
            val stored = summary.copy(previewFile = fileName)
            val next = (listOf(stored) + _jobs.value).sortedByDescending { it.startedAt }.take(MAX_JOBS)
            val kept = next.mapNotNull { it.previewFile }.toSet()
            _jobs.value.forEach { old ->
                val file = old.previewFile
                if (file != null && file !in kept) runCatching { File(dir, file).delete() }
            }
            _jobs.value = next
            persist(next)
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = _jobs.value.filterNot { it.id == id }
            _jobs.value.firstOrNull { it.id == id }?.previewFile?.let {
                runCatching { File(dir, it).delete() }
            }
            _jobs.value = next
            persist(next)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _jobs.value.forEach { it.previewFile?.let { name -> runCatching { File(dir, name).delete() } } }
            _jobs.value = emptyList()
            runCatching { indexFile.delete() }
        }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!indexFile.exists()) return@withLock
                val array = JSONArray(indexFile.readText())
                val list = ArrayList<PrintJobSummary>()
                for (i in 0 until array.length()) {
                    runCatching { fromJson(array.getJSONObject(i)) }.getOrNull()?.let { job ->
                        if (job.previewFile == null || File(dir, job.previewFile).exists()) list.add(job)
                    }
                }
                _jobs.value = list.sortedByDescending { it.startedAt }.take(MAX_JOBS)
            } catch (t: Throwable) {
                Log.e(TAG, "Could not read job history", t)
                _jobs.value = emptyList()
            }
        }
    }

    private fun persist(jobs: List<PrintJobSummary>) {
        try {
            val array = JSONArray()
            jobs.forEach { array.put(toJson(it)) }
            val temp = File(dir, "index.json.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(indexFile)) {
                indexFile.writeText(array.toString())
                temp.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not save job history", t)
        }
    }

    private fun toJson(job: PrintJobSummary): JSONObject = JSONObject().apply {
        put("id", job.id)
        put("startedAt", job.startedAt)
        put("finishedAt", job.finishedAt)
        put("source", job.source.name)
        put("remote", job.remote)
        put("bytes", job.bytes)
        put("blocks", job.blocks)
        put("hadCut", job.hadCut)
        put("previewFile", job.previewFile ?: JSONObject.NULL)
        put("previewWidth", job.previewWidth)
        put("previewHeight", job.previewHeight)
        put("textPreview", job.textPreview)
        put("profileName", job.profileName)
        put("unsupportedCommands", job.unsupportedCommands)
    }

    private fun fromJson(obj: JSONObject): PrintJobSummary = PrintJobSummary(
        id = obj.getLong("id"),
        startedAt = obj.optLong("startedAt"),
        finishedAt = obj.optLong("finishedAt"),
        source = runCatching { JobSource.valueOf(obj.optString("source", "NETWORK")) }.getOrDefault(JobSource.NETWORK),
        remote = obj.optString("remote", "unknown"),
        bytes = obj.optLong("bytes"),
        blocks = obj.optInt("blocks"),
        hadCut = obj.optBoolean("hadCut"),
        previewFile = if (obj.isNull("previewFile")) null else obj.optString("previewFile").ifBlank { null },
        previewWidth = obj.optInt("previewWidth"),
        previewHeight = obj.optInt("previewHeight"),
        textPreview = obj.optString("textPreview"),
        profileName = obj.optString("profileName", "Epson TM-m30III"),
        unsupportedCommands = obj.optInt("unsupportedCommands"),
    )

    companion object {
        private const val TAG = "JobStore"
        private const val MAX_JOBS = 20
    }
}
