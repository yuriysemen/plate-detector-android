package com.github.yuriysemen.platesdetector.training

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrainingDataSaver(context: Context) {
    private val root = File(context.filesDir, "training_data")
    private val imagesDir = File(root, "images")
    private val labelsDir = File(root, "labels")
    private val manifestFile = File(root, "manifest.json")

    private var nextSeq = 1
    private var totalFrames = 0
    private var totalDetections = 0
    private var multiDetectionFrames = 0
    private var collectedFrom: String? = null
    private var initialized = false

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val filenameDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)

    private fun ensureInit() {
        if (initialized) return
        imagesDir.mkdirs()
        labelsDir.mkdirs()
        if (manifestFile.exists()) {
            runCatching {
                val json = JSONObject(manifestFile.readText())
                nextSeq = json.optInt("next_seq", 1).coerceAtLeast(1)
                totalFrames = json.optInt("total_frames", 0)
                totalDetections = json.optInt("total_detections", 0)
                collectedFrom = json.optString("collected_from").takeIf { it.isNotEmpty() }
                multiDetectionFrames = json.optInt("multi_detection_frames", 0)
            }
        }
        initialized = true
    }

    fun saveFrame(bitmap: Bitmap, detections: List<Detection>, appVersion: String, modelId: String) {
        if (detections.isEmpty()) return
        ensureInit()

        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val lines = detections.mapNotNull { det ->
            val bw = (det.rightPx - det.leftPx) / w
            val bh = (det.bottomPx - det.topPx) / h
            if (bw <= 0f || bh <= 0f) return@mapNotNull null
            val xc = ((det.leftPx + det.rightPx) / 2f) / w
            val yc = ((det.topPx + det.bottomPx) / 2f) / h
            "${det.classId} %.6f %.6f %.6f %.6f".format(
                Locale.US,
                xc.coerceIn(0f, 1f),
                yc.coerceIn(0f, 1f),
                bw.coerceIn(0f, 1f),
                bh.coerceIn(0f, 1f)
            )
        }
        if (lines.isEmpty()) return

        val seq = nextSeq++
        val captureTime = Date()
        val name = "${filenameDateFormat.format(captureTime)}_${"%06d".format(seq)}"
        val now = isoFormat.format(captureTime)
        if (collectedFrom == null) collectedFrom = now

        FileOutputStream(File(imagesDir, "$name.jpg")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        File(labelsDir, "$name.txt").writeText(lines.joinToString("\n"))

        totalFrames++
        totalDetections += lines.size
        if (lines.size >= 2) multiDetectionFrames++

        val json = JSONObject()
        json.put("app_version", appVersion)
        json.put("model_id", modelId)
        json.put("collected_from", collectedFrom)
        json.put("collected_to", now)
        json.put("total_frames", totalFrames)
        json.put("total_detections", totalDetections)
        json.put("multi_detection_frames", multiDetectionFrames)
        json.put("next_seq", nextSeq)
        manifestFile.writeText(json.toString(2))
    }

    /** Saves a frame with an empty label file — used for manually captured missed-plate cases. */
    fun saveFrameManual(bitmap: Bitmap, appVersion: String, modelId: String) {
        ensureInit()
        val seq = nextSeq++
        val captureTime = Date()
        val name = "${filenameDateFormat.format(captureTime)}_${"%06d".format(seq)}"
        val now = isoFormat.format(captureTime)
        if (collectedFrom == null) collectedFrom = now

        FileOutputStream(File(imagesDir, "$name.jpg")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        File(labelsDir, "$name.txt").writeText("")

        totalFrames++

        val json = JSONObject()
        json.put("app_version", appVersion)
        json.put("model_id", modelId)
        json.put("collected_from", collectedFrom)
        json.put("collected_to", now)
        json.put("total_frames", totalFrames)
        json.put("total_detections", totalDetections)
        json.put("multi_detection_frames", multiDetectionFrames)
        json.put("next_seq", nextSeq)
        manifestFile.writeText(json.toString(2))
    }

    fun reset() {
        imagesDir.listFiles()?.forEach { it.delete() }
        labelsDir.listFiles()?.forEach { it.delete() }
        nextSeq = 1
        totalFrames = 0
        totalDetections = 0
        multiDetectionFrames = 0
        collectedFrom = null
        initialized = true
    }
}