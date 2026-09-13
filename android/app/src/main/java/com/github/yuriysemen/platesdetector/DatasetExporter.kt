package com.github.yuriysemen.platesdetector

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.BufferedOutputStream
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
        val uploadStatus: UploadStatus = UploadStatus.NOT_QUEUED,
        val statusUpdatedAt: Long = createdAt,
        /** Human-readable reason for the current status — mainly the failure cause. */
        val statusDetail: String? = null
    )

    data class SidecarInfo(
        val status: UploadStatus,
        val updatedAt: Long,
        val detail: String? = null
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

    /** Total on-device size of the upload buffer (images + labels). `manifest.json` is negligible. */
    fun trainingUsageBytes(): Long {
        val imgBytes = imagesDir.listFiles()?.sumOf { it.length() } ?: 0L
        val lblBytes = labelsDir.listFiles()?.sumOf { it.length() } ?: 0L
        return imgBytes + lblBytes
    }

    fun listExports(): List<ExportFile> {
        if (!exportsDir.exists()) return emptyList()
        val files = exportsDir.listFiles().orEmpty()
        // Half-written sidecars from a killed worker (see writeUploadStatus) — never keep them.
        files.filter { it.name.endsWith(".upload.json.tmp") }.forEach { it.delete() }
        // Sidecars whose ZIP is gone are leftovers (e.g. from a prior app version); clean them up.
        files.filter { it.name.endsWith(".upload.json") }
            .forEach { sidecar ->
                val zip = File(exportsDir, sidecar.name.removeSuffix(".upload.json") + ".zip")
                if (!zip.exists()) sidecar.delete()
            }
        return files.filter { it.extension.equals("zip", ignoreCase = true) }
            .map { f ->
                val info = readSidecarInfo(f)
                ExportFile(
                    f, f.nameWithoutExtension, f.length(), f.lastModified(),
                    info.status, info.updatedAt, info.detail
                )
            }
            .sortedByDescending { it.createdAt }
    }

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

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmpFile))).use { zos ->
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

    fun deleteExport(file: File) {
        file.delete()
        sidecarFor(file).delete()
    }

    /** Deletes every export ZIP and sidecar on disk — used to discard in-flight uploads before restarting. */
    fun deleteAllExports() {
        exportsDir.listFiles()?.forEach { it.delete() }
    }

    /** Called on upload success: no local record is kept — both the ZIP and its sidecar are deleted. */
    fun onUploadSuccess(zipFile: File) {
        sidecarFor(zipFile).delete()
        zipFile.delete()
    }

    fun readUploadStatus(zipFile: File): UploadStatus = readSidecarInfo(zipFile).status

    /**
     * Stamps `updated_at` with the current time — this is the "last operation time" used to detect
     * stuck sessions. When [detail] is null the previous detail (if any) is preserved, so a plain
     * UPLOADING → PENDING transition doesn't erase the reason a prior attempt failed.
     */
    fun writeUploadStatus(zipFile: File, status: UploadStatus, detail: String? = null) {
        val effectiveDetail = detail ?: readSidecarInfo(zipFile).detail
        val payload = JSONObject()
            .put("status", status.name)
            .put("updated_at", System.currentTimeMillis())
            .apply { if (effectiveDetail != null) put("detail", effectiveDetail) }
            .toString()
        // Write-then-rename so a worker killed mid-write can't leave a half-written sidecar —
        // a corrupt sidecar reads back as NOT_QUEUED and the failed upload vanishes from the UI.
        val sidecar = sidecarFor(zipFile)
        val tmp = File(sidecar.parentFile, "${sidecar.name}.tmp")
        runCatching {
            tmp.writeText(payload)
            if (!tmp.renameTo(sidecar)) {
                sidecar.writeText(payload)
                tmp.delete()
            }
        }.onFailure { runCatching { tmp.delete() } }
    }

    private fun readSidecarInfo(zipFile: File): SidecarInfo {
        val sidecar = sidecarFor(zipFile)
        if (!sidecar.exists()) return SidecarInfo(UploadStatus.NOT_QUEUED, zipFile.lastModified())
        return runCatching {
            val json = JSONObject(sidecar.readText())
            val status = UploadStatus.valueOf(json.getString("status"))
            // Older sidecars have no "updated_at" — fall back to the sidecar file's own mtime.
            val updatedAt = json.optLong("updated_at", sidecar.lastModified())
            val detail = json.optString("detail").takeIf { it.isNotEmpty() }
            SidecarInfo(status, updatedAt, detail)
        }.getOrDefault(SidecarInfo(UploadStatus.NOT_QUEUED, zipFile.lastModified()))
    }

    private fun sidecarFor(zipFile: File) = File(zipFile.parent, "${zipFile.nameWithoutExtension}.upload.json")

    companion object {
        /** A session with no status update in this long is considered stuck and eligible for restart. */
        const val STALE_UPLOAD_TIMEOUT_MS = 30 * 60 * 1000L

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
