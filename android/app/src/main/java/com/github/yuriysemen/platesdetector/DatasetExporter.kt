package com.github.yuriysemen.platesdetector

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

class DatasetExporter(context: Context) {
    private val appContext = context.applicationContext
    private val trainingRoot = File(appContext.filesDir, "training_data")
    private val imagesDir = File(trainingRoot, "images")
    private val labelsDir = File(trainingRoot, "labels")
    private val manifestFile = File(trainingRoot, "manifest.json")
    private val exportsDir = File(appContext.filesDir, "exports")

    data class Stats(
        val totalFrames: Int,
        val totalDetections: Int,
        val collectedFrom: String?,
        val collectedTo: String?
    )

    data class ExportFile(
        val file: File,
        val name: String,
        val sizeBytes: Long,
        val createdAt: Long
    )

    data class SplitConfig(val trainRatio: Float = 0.70f, val valRatio: Float = 0.20f) {
        val testRatio: Float get() = (1f - trainRatio - valRatio).coerceAtLeast(0f)
    }

    fun readStats(): Stats {
        if (!manifestFile.exists()) return Stats(0, 0, null, null)
        return runCatching {
            val json = JSONObject(manifestFile.readText())
            Stats(
                totalFrames = json.optInt("total_frames", 0),
                totalDetections = json.optInt("total_detections", 0),
                collectedFrom = json.optString("collected_from").takeIf { it.isNotEmpty() },
                collectedTo = json.optString("collected_to").takeIf { it.isNotEmpty() }
            )
        }.getOrDefault(Stats(0, 0, null, null))
    }

    fun listExports(): List<ExportFile> =
        exportsDir.listFiles()
            ?.filter { it.extension.equals("zip", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f -> ExportFile(f, f.nameWithoutExtension, f.length(), f.lastModified()) }
            .orEmpty()

    fun exportSync(splitConfig: SplitConfig = SplitConfig()): File {
        exportsDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(exportsDir, "plates_dataset_$timestamp.zip")
        val tmpFile = File(exportsDir, "plates_dataset_$timestamp.zip.tmp")

        try {
            val allImages = (imagesDir.listFiles()?.sortedBy { it.name } ?: emptyList()).shuffled()
            val total = allImages.size
            val trainCount = (total * splitConfig.trainRatio).roundToInt()
            val valCount = (total * splitConfig.valRatio).roundToInt().coerceAtMost(total - trainCount)

            val splits = mapOf(
                "train" to allImages.subList(0, trainCount),
                "val" to allImages.subList(trainCount, trainCount + valCount),
                "test" to allImages.subList(trainCount + valCount, total)
            )

            ZipOutputStream(FileOutputStream(tmpFile)).use { zos ->
                for ((splitName, images) in splits) {
                    for (img in images) {
                        zos.putNextEntry(ZipEntry("$splitName/images/${img.name}"))
                        img.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()

                        val lbl = File(labelsDir, "${img.nameWithoutExtension}.txt")
                        if (lbl.exists()) {
                            zos.putNextEntry(ZipEntry("$splitName/labels/${lbl.name}"))
                            lbl.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
                zos.putNextEntry(ZipEntry("data.yaml"))
                zos.write(DATASET_YAML.toByteArray())
                zos.closeEntry()
            }

            if (!tmpFile.renameTo(zipFile)) error("Failed to finalize ZIP")
            resetCollectedData()
            return zipFile
        } catch (e: Exception) {
            runCatching { tmpFile.delete() }
            runCatching { zipFile.delete() }
            throw e
        }
    }

    fun resetCollectedData() {
        imagesDir.listFiles()?.forEach { it.delete() }
        labelsDir.listFiles()?.forEach { it.delete() }
        val json = if (manifestFile.exists()) {
            runCatching { JSONObject(manifestFile.readText()) }.getOrDefault(JSONObject())
        } else JSONObject()
        json.put("total_frames", 0)
        json.put("total_detections", 0)
        json.put("next_seq", 1)
        json.remove("collected_from")
        json.remove("collected_to")
        trainingRoot.mkdirs()
        manifestFile.writeText(json.toString(2))
    }

    fun renameExport(file: File, newName: String): Boolean {
        val target = File(exportsDir, "$newName.zip")
        if (target.exists()) return false
        return file.renameTo(target)
    }

    fun deleteExport(file: File) {
        file.delete()
    }

    fun getShareUri(file: File): Uri =
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)

    companion object {
        private val DATASET_YAML =
            "train: train/images\nval: val/images\ntest: test/images\nnc: 1\nnames: ['License_Plate']\n"
    }
}
