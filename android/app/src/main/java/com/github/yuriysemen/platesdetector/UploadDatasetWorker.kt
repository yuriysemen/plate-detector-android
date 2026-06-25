package com.github.yuriysemen.platesdetector

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UploadDatasetWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_ZIP_PATH   = "zip_path"
        const val KEY_DEVICE_ID  = "device_id"
        const val KEY_UPLOAD_URL = "upload_url"
        const val MAX_ATTEMPTS   = 5
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val zipPath   = inputData.getString(KEY_ZIP_PATH)   ?: return@withContext Result.failure()
        val deviceId  = inputData.getString(KEY_DEVICE_ID)  ?: return@withContext Result.failure()
        val uploadUrl = inputData.getString(KEY_UPLOAD_URL) ?: return@withContext Result.failure()

        val zipFile = File(zipPath)
        if (!zipFile.exists()) return@withContext Result.failure()

        val exporter = DatasetExporter(applicationContext)
        exporter.writeUploadStatus(zipFile, UploadStatus.UPLOADING)

        return@withContext try {
            var presignedUrl = requestPresignedUrl(uploadUrl, zipFile.name, deviceId)
            var putSucceeded = putZip(presignedUrl, zipFile)

            if (!putSucceeded) {
                // 403: presigned URL expired — re-request once and retry the PUT
                presignedUrl = requestPresignedUrl(uploadUrl, zipFile.name, deviceId)
                putSucceeded = putZip(presignedUrl, zipFile)
            }

            if (putSucceeded) {
                exporter.deleteExport(zipFile)
                Result.success()
            } else {
                retry(exporter, zipFile)
            }
        } catch (e: Exception) {
            retry(exporter, zipFile)
        }
    }

    private fun retry(exporter: DatasetExporter, zipFile: File): Result =
        if (runAttemptCount < MAX_ATTEMPTS - 1) {
            exporter.writeUploadStatus(zipFile, UploadStatus.PENDING)
            Result.retry()
        } else {
            exporter.writeUploadStatus(zipFile, UploadStatus.FAILED)
            Result.failure()
        }

    // Returns the presigned upload URL from the Lambda endpoint.
    // Throws on network error or non-2xx response.
    private fun requestPresignedUrl(baseUrl: String, filename: String, deviceId: String): String {
        val conn = URL("$baseUrl/get-upload-url").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            val body = JSONObject()
                .put("filename", filename)
                .put("device_id", deviceId)
                .toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                throw Exception("get-upload-url HTTP ${conn.responseCode}")
            }
            return JSONObject(
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            ).getString("upload_url")
        } finally {
            conn.disconnect()
        }
    }

    // Returns true on HTTP 200, false on 403 (expired URL), throws on other failures.
    private fun putZip(presignedUrl: String, zipFile: File): Boolean {
        val conn = URL(presignedUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Content-Type", "application/zip")
            conn.setRequestProperty("Content-Length", zipFile.length().toString())
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            zipFile.inputStream().use { input ->
                conn.outputStream.use { output -> input.copyTo(output) }
            }
            return when (conn.responseCode) {
                200  -> true
                403  -> false
                else -> throw Exception("S3 PUT HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }
}