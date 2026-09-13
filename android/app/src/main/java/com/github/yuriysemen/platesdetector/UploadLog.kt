package com.github.yuriysemen.platesdetector

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent, capped activity log for dataset uploads — the counterpart to the in-memory
 * [ModelUpdateLog]. Backed by a file because [UploadDatasetWorker] and [AutoUploadWorker] run
 * headless (often after the Activity is gone), so an in-memory-only log would be empty by the
 * time the user opens the Contribute screen to see why an upload failed.
 *
 * The in-memory [entries] flow is seeded from disk on first access and kept in sync on every
 * [log] call, so a worker running in the live app process updates the open UI immediately.
 */
object UploadLog {

    enum class Level { INFO, SUCCESS, ERROR }

    data class Entry(
        val timeMs: Long = System.currentTimeMillis(),
        val message: String,
        val level: Level = Level.INFO
    )

    private const val FILE_NAME = "upload_log.json"
    private const val MAX_ENTRIES = 100

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    val latest: Entry? get() = _entries.value.lastOrNull()

    private var loadedFrom: File? = null

    @Synchronized
    fun ensureLoaded(context: Context) {
        val file = fileFor(context)
        if (loadedFrom == file) return
        loadedFrom = file
        if (!file.exists()) {
            _entries.value = emptyList()
            return
        }
        _entries.value = runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    timeMs = o.optLong("t", System.currentTimeMillis()),
                    message = o.optString("m"),
                    level = runCatching { Level.valueOf(o.optString("l", "INFO")) }.getOrDefault(Level.INFO)
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun log(context: Context, message: String, level: Level = Level.INFO) {
        ensureLoaded(context)
        val updated = (_entries.value + Entry(message = message, level = level)).takeLast(MAX_ENTRIES)
        _entries.value = updated
        runCatching {
            val arr = JSONArray()
            updated.forEach { e ->
                arr.put(JSONObject().put("t", e.timeMs).put("m", e.message).put("l", e.level.name))
            }
            fileFor(context).writeText(arr.toString())
        }
    }

    @Synchronized
    fun clear(context: Context) {
        _entries.value = emptyList()
        runCatching { fileFor(context).delete() }
    }

    private fun fileFor(context: Context) =
        File(context.applicationContext.filesDir, FILE_NAME)
}
