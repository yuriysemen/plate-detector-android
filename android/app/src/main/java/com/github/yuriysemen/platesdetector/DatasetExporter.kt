package com.github.yuriysemen.platesdetector

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

enum class UploadStatus { NOT_QUEUED, PENDING, UPLOADING, UPLOADED, FAILED }

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
        val collectedTo: String?,
        val modelId: String?,
        val appVersion: String?
    )

    data class ExportFile(
        val file: File,
        val name: String,
        val sizeBytes: Long,
        val createdAt: Long,
        val uploadStatus: UploadStatus = UploadStatus.NOT_QUEUED
    )

    data class SplitConfig(val trainRatio: Float = 0.70f, val valRatio: Float = 0.20f) {
        val testRatio: Float get() = (1f - trainRatio - valRatio).coerceAtLeast(0f)
    }

    data class DeviceMetadata(
        val phoneModel: String,
        val phoneManufacturer: String,
        val androidVersion: String,
        val androidSdk: Int,
        val deviceId: String
    )

    fun readStats(): Stats {
        if (!manifestFile.exists()) return Stats(0, 0, null, null, null, null)
        return runCatching {
            val json = JSONObject(manifestFile.readText())
            Stats(
                totalFrames = json.optInt("total_frames", 0),
                totalDetections = json.optInt("total_detections", 0),
                collectedFrom = json.optString("collected_from").takeIf { it.isNotEmpty() },
                collectedTo = json.optString("collected_to").takeIf { it.isNotEmpty() },
                modelId = json.optString("model_id").takeIf { it.isNotEmpty() },
                appVersion = json.optString("app_version").takeIf { it.isNotEmpty() }
            )
        }.getOrDefault(Stats(0, 0, null, null, null, null))
    }

    fun listExports(): List<ExportFile> =
        exportsDir.listFiles()
            ?.filter { it.extension.equals("zip", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f -> ExportFile(f, f.nameWithoutExtension, f.length(), f.lastModified(), readUploadStatus(f)) }
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

            val stats = readStats()
            val rawAndroidId = Settings.Secure.getString(
                appContext.contentResolver, Settings.Secure.ANDROID_ID
            ) ?: ""
            val meta = DeviceMetadata(
                phoneModel = Build.MODEL,
                phoneManufacturer = Build.MANUFACTURER,
                androidVersion = Build.VERSION.RELEASE,
                androidSdk = Build.VERSION.SDK_INT,
                deviceId = computeDeviceId(rawAndroidId)
            )
            val exportTimestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date())

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
                zos.write(buildDataYaml(stats, meta, exportTimestamp).toByteArray())
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
        sidecarFor(file).delete()
    }

    fun getShareUri(file: File): Uri =
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)

    fun readUploadStatus(zipFile: File): UploadStatus {
        val sidecar = sidecarFor(zipFile)
        if (!sidecar.exists()) return UploadStatus.NOT_QUEUED
        return runCatching {
            UploadStatus.valueOf(JSONObject(sidecar.readText()).getString("status"))
        }.getOrDefault(UploadStatus.NOT_QUEUED)
    }

    fun writeUploadStatus(zipFile: File, status: UploadStatus) {
        val sidecar = sidecarFor(zipFile)
        sidecar.writeText(JSONObject().put("status", status.name).toString())
    }

    private fun sidecarFor(zipFile: File) = File(zipFile.parent, "${zipFile.nameWithoutExtension}.upload.json")

    companion object {
        fun computeDeviceId(rawAndroidId: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(rawAndroidId.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }.take(16)
        }

        fun buildDataYaml(stats: Stats, meta: DeviceMetadata, exportTimestamp: String): String {
            fun esc(s: String) = s.replace("\"", "\\\"")
            return buildString {
                appendLine("train: train/images")
                appendLine("val: val/images")
                appendLine("test: test/images")
                appendLine("nc: 1")
                appendLine("names: ['License_Plate']")
                appendLine()
                appendLine("device:")
                appendLine("  phone_model: \"${esc(meta.phoneModel)}\"")
                appendLine("  phone_manufacturer: \"${esc(meta.phoneManufacturer)}\"")
                appendLine("  android_version: \"${esc(meta.androidVersion)}\"")
                appendLine("  android_sdk: ${meta.androidSdk}")
                appendLine("  app_version: \"${esc(stats.appVersion ?: "unknown")}\"")
                appendLine("  model_id: \"${esc(stats.modelId ?: "unknown")}\"")
                appendLine("  export_timestamp: \"$exportTimestamp\"")
                appendLine("  device_id: \"${meta.deviceId}\"")
                appendLine("  total_frames: ${stats.totalFrames}")
                appendLine("  total_detections: ${stats.totalDetections}")
                if (stats.collectedFrom != null) appendLine("  collected_from: \"${stats.collectedFrom}\"")
                if (stats.collectedTo != null) appendLine("  collected_to: \"${stats.collectedTo}\"")
            }
        }
    }
}
