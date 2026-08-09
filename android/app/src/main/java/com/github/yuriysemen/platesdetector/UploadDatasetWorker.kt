package com.github.yuriysemen.platesdetector

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.amazonaws.DefaultRequest
import com.amazonaws.auth.AWS4Signer
import com.amazonaws.auth.AWSSessionCredentials
import com.amazonaws.http.HttpMethodName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class UploadDatasetWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_ZIP_PATH          = "zip_path"
        const val KEY_DEVICE_ID         = "device_id"
        const val KEY_UPLOAD_URL        = "upload_url"
        const val KEY_USER_ID           = "user_id"
        const val KEY_IDENTITY_POOL_ID  = "identity_pool_id"
        const val KEY_USER_POOL_ID      = "user_pool_id"
        const val KEY_FRAME_COUNT       = "frame_count"
        const val KEY_IS_AUTO_UPLOAD    = "is_auto_upload"
        const val MAX_ATTEMPTS          = 5
        const val TAG_DATASET_UPLOAD    = "dataset_upload"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val zipPath       = inputData.getString(KEY_ZIP_PATH)         ?: return@withContext Result.failure()
        val deviceId      = inputData.getString(KEY_DEVICE_ID)        ?: return@withContext Result.failure()
        val uploadUrl     = inputData.getString(KEY_UPLOAD_URL)       ?: return@withContext Result.failure()
        val userId        = inputData.getString(KEY_USER_ID)          ?: return@withContext Result.failure()
        val identityPoolId = inputData.getString(KEY_IDENTITY_POOL_ID) ?: return@withContext Result.failure()
        val userPoolId    = inputData.getString(KEY_USER_POOL_ID)     ?: return@withContext Result.failure()

        val zipFile = File(zipPath)
        if (!zipFile.exists()) return@withContext Result.failure()

        val exporter = DatasetExporter(applicationContext)
        exporter.writeUploadStatus(zipFile, UploadStatus.UPLOADING)

        // Obtain short-lived STS credentials via the Cognito Identity Pool.
        // SessionExpiredException means the user must sign in again — don't retry.
        val credentials = try {
            CognitoAuthManager(applicationContext).getAwsCredentials()
        } catch (e: SessionExpiredException) {
            CognitoAuthManager(applicationContext).markSessionExpired()
            exporter.writeUploadStatus(zipFile, UploadStatus.FAILED)
            return@withContext Result.failure()
        } catch (e: Exception) {
            return@withContext retry(exporter, zipFile)
        }

        val region = regionFromUrl(uploadUrl).ifEmpty { identityPoolId.substringBefore(":") }

        val frameCount = inputData.getInt(KEY_FRAME_COUNT, 0)

        return@withContext try {
            var presignedUrl = requestPresignedUrl(
                uploadUrl, zipFile.name, deviceId, userId, userPoolId, identityPoolId, credentials, region
            ).first
            var putSucceeded = putZip(presignedUrl, zipFile)

            if (!putSucceeded) {
                // 403: presigned URL expired — re-request once and retry the PUT
                presignedUrl = requestPresignedUrl(
                    uploadUrl, zipFile.name, deviceId, userId, userPoolId, identityPoolId, credentials, region
                ).first
                putSucceeded = putZip(presignedUrl, zipFile)
            }

            if (putSucceeded) {
                exporter.onUploadSuccess(zipFile)
                if (inputData.getBoolean(KEY_IS_AUTO_UPLOAD, false)) {
                    showUploadNotification(frameCount)
                }
                Result.success()
            } else {
                retry(exporter, zipFile)
            }
        } catch (e: Exception) {
            retry(exporter, zipFile)
        }
    }

    private fun showUploadNotification(frameCount: Int) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_CONTRIBUTE, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, MainActivity.DATASET_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Dataset uploaded")
            .setContentText("$frameCount frames sent. Tap to open Contribute screen.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun retry(exporter: DatasetExporter, zipFile: File): Result =
        if (runAttemptCount < MAX_ATTEMPTS - 1) {
            exporter.writeUploadStatus(zipFile, UploadStatus.PENDING)
            Result.retry()
        } else {
            exporter.writeUploadStatus(zipFile, UploadStatus.FAILED)
            Result.failure()
        }

    // Returns (upload_url, object_key) from the Lambda endpoint.
    // The request is SigV4-signed using short-lived STS credentials from the Identity Pool.
    // Throws on network error or non-2xx response.
    private fun requestPresignedUrl(
        baseUrl: String,
        filename: String,
        deviceId: String,
        userId: String,
        userPoolId: String,
        identityPoolId: String,
        credentials: AWSSessionCredentials,
        region: String
    ): Pair<String, String> {
        val targetUrl  = "$baseUrl/get-upload-url"
        val bodyBytes  = JSONObject()
            .put("filename", filename)
            .put("device_id", deviceId)
            .put("user_id", userId)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val parsedUrl  = URL(targetUrl)
        val sdkRequest = DefaultRequest<Any>("execute-api").apply {
            httpMethod   = HttpMethodName.POST
            endpoint     = URI("${parsedUrl.protocol}://${parsedUrl.host}")
            resourcePath = parsedUrl.path
            addHeader("Content-Type", "application/json")
            content      = bodyBytes.inputStream()
        }
        AWS4Signer().apply {
            setServiceName("execute-api")
            setRegionName(region)
        }.sign(sdkRequest, credentials)

        val conn = URL(targetUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            sdkRequest.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connectTimeout = 15_000
            conn.readTimeout    = 15_000
            conn.doOutput       = true
            conn.outputStream.use { it.write(bodyBytes) }
            if (conn.responseCode !in 200..299) {
                throw Exception("get-upload-url HTTP ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            return Pair(json.getString("upload_url"), json.getString("object_key"))
        } finally {
            conn.disconnect()
        }
    }

    private fun regionFromUrl(url: String): String =
        Regex("""execute-api\.([a-z0-9-]+)\.amazonaws\.com""")
            .find(url)?.groupValues?.get(1) ?: ""

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