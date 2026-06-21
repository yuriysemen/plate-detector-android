package com.github.yuriysemen.platesdetector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class FrameEntry(
    val name: String,
    val imageFile: File,
    val labelFile: File,
    val boxes: List<YoloBox>
)

data class YoloBox(
    val classId: Int,
    val xCenter: Float,
    val yCenter: Float,
    val width: Float,
    val height: Float
)

class DatasetEditor(context: Context) {
    private val trainingRoot = File(context.applicationContext.filesDir, "training_data")
    private val imagesDir = File(trainingRoot, "images")
    private val labelsDir = File(trainingRoot, "labels")
    private val manifestFile = File(trainingRoot, "manifest.json")

    fun loadFrames(): List<FrameEntry> {
        val images = imagesDir.listFiles()
            ?.filter { it.extension.equals("jpg", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: return emptyList()
        return images.map { img ->
            val name = img.nameWithoutExtension
            val lbl = File(labelsDir, "$name.txt")
            FrameEntry(name, img, lbl, parseBoxes(lbl))
        }
    }

    fun loadBitmap(imageFile: File): Bitmap? =
        runCatching { BitmapFactory.decodeFile(imageFile.absolutePath) }.getOrNull()

    fun saveBoxes(frame: FrameEntry, boxes: List<YoloBox>) {
        val text = boxes.joinToString("\n") { b ->
            "${b.classId} %.6f %.6f %.6f %.6f".format(Locale.US, b.xCenter, b.yCenter, b.width, b.height)
        }
        frame.labelFile.writeText(text)
        updateManifestDetections()
    }

    fun deleteFrames(frames: List<FrameEntry>): Int {
        var deleted = 0
        for (f in frames) {
            if (runCatching { f.imageFile.delete() }.getOrDefault(false)) {
                runCatching { f.labelFile.delete() }
                deleted++
            }
        }
        if (deleted > 0) rebuildManifestCounts()
        return deleted
    }

    fun trainingUsageBytes(): Long {
        val imgBytes = imagesDir.listFiles()?.sumOf { it.length() } ?: 0L
        val lblBytes = labelsDir.listFiles()?.sumOf { it.length() } ?: 0L
        return imgBytes + lblBytes
    }

    private fun parseBoxes(file: File): List<YoloBox> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching {
                    val p = line.trim().split(Regex("\\s+"))
                    if (p.size >= 5) YoloBox(
                        p[0].toInt(),
                        p[1].replace(',', '.').toFloat(),
                        p[2].replace(',', '.').toFloat(),
                        p[3].replace(',', '.').toFloat(),
                        p[4].replace(',', '.').toFloat()
                    ) else null
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun readManifest(): JSONObject {
        if (!manifestFile.exists()) return JSONObject()
        return runCatching { JSONObject(manifestFile.readText()) }.getOrDefault(JSONObject())
    }

    private fun writeManifest(json: JSONObject) {
        trainingRoot.mkdirs()
        manifestFile.writeText(json.toString(2))
    }

    private fun rebuildManifestCounts() {
        val json = readManifest()
        val frames = imagesDir.listFiles()?.count { it.extension.equals("jpg", ignoreCase = true) } ?: 0
        val dets = labelsDir.listFiles()?.sumOf { f ->
            runCatching { f.readLines().count { it.isNotBlank() } }.getOrDefault(0)
        } ?: 0
        json.put("total_frames", frames)
        json.put("total_detections", dets)
        writeManifest(json)
    }

    private fun updateManifestDetections() {
        val json = readManifest()
        val dets = labelsDir.listFiles()?.sumOf { f ->
            runCatching { f.readLines().count { it.isNotBlank() } }.getOrDefault(0)
        } ?: 0
        json.put("total_detections", dets)
        writeManifest(json)
    }
}