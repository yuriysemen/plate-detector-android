package com.github.yuriysemen.platesdetector

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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
        const val KEY_PROGRESS_BYTES    = "progress_bytes"
        const val KEY_PROGRESS_TOTAL    = "progress_total"
        const val MAX_ATTEMPTS          = 5
        const val TAG_DATASET_UPLOAD    = "dataset_upload"
        private const val NOTIFICATION_ID = 1001
        private const val PROGRESS_THROTTLE_MS = 250L
        private const val SESSION_DEAD_DETAIL =
            "your sign-in has expired. Open Contribute and tap \"Sign in again\"."

        /** Message for a 401/403 where the session is fine but the request was rejected. */
        fun notAuthorizedDetail(httpCode: Int): String = if (httpCode == 403)
            "your account isn't authorized to upload (HTTP 403). If you were just granted access, sign out and back in; otherwise the upload API needs a config fix."
        else
            "the upload request was rejected (HTTP $httpCode) — an app/server configuration mismatch."
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppConfig.seedPrefsIfNeeded(applicationContext)

        val zipPath       = inputData.getString(KEY_ZIP_PATH)         ?: return@withContext Result.failure()
        val deviceId      = inputData.getString(KEY_DEVICE_ID)        ?: return@withContext Result.failure()
        val uploadUrl     = inputData.getString(KEY_UPLOAD_URL)       ?: return@withContext Result.failure()
        val userId        = inputData.getString(KEY_USER_ID)          ?: return@withContext Result.failure()
        val identityPoolId = inputData.getString(KEY_IDENTITY_POOL_ID) ?: return@withContext Result.failure()
        val userPoolId    = inputData.getString(KEY_USER_POOL_ID)     ?: return@withContext Result.failure()

        val zipFile = File(zipPath)
        if (!zipFile.exists()) return@withContext Result.failure()

        val exporter = DatasetExporter(applicationContext)
        val frameCount = inputData.getInt(KEY_FRAME_COUNT, 0)
        exporter.writeUploadStatus(zipFile, UploadStatus.UPLOADING)
        if (runAttemptCount == 0) {
            UploadLog.log(
                applicationContext,
                "Upload started: ${zipFile.name} — $frameCount frames, ${humanSize(zipFile.length())}"
            )
        }

        // Obtain short-lived STS credentials via the Cognito Identity Pool.
        // SessionExpiredException means the user must sign in again — don't retry.
        val credentials = try {
            CognitoAuthManager(applicationContext).getAwsCredentials()
        } catch (e: SessionExpiredException) {
            CognitoAuthManager(applicationContext).markSessionExpired()
            failNow(exporter, zipFile, SESSION_DEAD_DETAIL)
            return@withContext Result.failure()
        } catch (e: Exception) {
            return@withContext retry(exporter, zipFile, "couldn't get sign-in credentials: ${e.message ?: e.javaClass.simpleName}")
        }

        val region = regionFromUrl(uploadUrl).ifEmpty { identityPoolId.substringBefore(":") }

        // Requests a presigned URL and PUTs the ZIP. The inner retry covers an *expired presigned
        // URL* (S3 PUT → 403, putZip returns false). An ApiUnauthorizedException here means the
        // signed get-upload-url request was rejected (401/403) — the caller mints fresh creds and
        // tries once more; if it still fails that's an authorization/config problem, not expiry.
        suspend fun doUpload(creds: AWSSessionCredentials): Boolean {
            var url = requestPresignedUrl(
                uploadUrl, zipFile.name, deviceId, userId, userPoolId, identityPoolId, creds, region
            ).first
            var ok = putZip(url, zipFile)
            if (!ok) {
                url = requestPresignedUrl(
                    uploadUrl, zipFile.name, deviceId, userId, userPoolId, identityPoolId, creds, region
                ).first
                ok = putZip(url, zipFile)
            }
            return ok
        }

        return@withContext try {
            val putSucceeded = try {
                doUpload(credentials)
            } catch (e: ApiUnauthorizedException) {
                // get-upload-url rejected the signed request. Cached STS credentials may be stale
                // after a silent token refresh — mint fresh ones and try exactly once more.
                val fresh = CognitoAuthManager(applicationContext).getAwsCredentials(forceRefresh = true)
                doUpload(fresh)
            }

            if (putSucceeded) {
                exporter.onUploadSuccess(zipFile)
                UploadLog.log(
                    applicationContext,
                    "Upload complete: ${zipFile.name} — $frameCount frames sent",
                    UploadLog.Level.SUCCESS
                )
                if (inputData.getBoolean(KEY_IS_AUTO_UPLOAD, false)) {
                    showUploadNotification(frameCount)
                }
                Result.success()
            } else {
                retry(exporter, zipFile, "S3 rejected the upload (HTTP 403) even after refreshing the link")
            }
        } catch (e: SessionExpiredException) {
            // Thrown by getAwsCredentials(forceRefresh = true) when the refresh token is dead too.
            CognitoAuthManager(applicationContext).markSessionExpired()
            failNow(exporter, zipFile, SESSION_DEAD_DETAIL)
            Result.failure()
        } catch (e: ApiUnauthorizedException) {
            // Rejected even with freshly-minted credentials. The session is valid — this is an
            // authorization / config problem (e.g. curator role without execute-api). Do NOT
            // sign the user out; a retry won't help either.
            failNow(exporter, zipFile, notAuthorizedDetail(e.httpCode))
            Result.failure()
        } catch (e: Exception) {
            retry(exporter, zipFile, e.message ?: e.javaClass.simpleName)
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

    /** Records a terminal failure — sidecar detail + a persistent [UploadLog] line. No retry. */
    private fun failNow(exporter: DatasetExporter, zipFile: File, reason: String) {
        exporter.writeUploadStatus(zipFile, UploadStatus.FAILED, reason)
        UploadLog.log(applicationContext, "Upload failed: $reason", UploadLog.Level.ERROR)
    }

    private fun retry(exporter: DatasetExporter, zipFile: File, reason: String): Result {
        val attempt = runAttemptCount + 1
        return if (runAttemptCount < MAX_ATTEMPTS - 1) {
            exporter.writeUploadStatus(zipFile, UploadStatus.PENDING, "Attempt $attempt failed: $reason — retrying")
            UploadLog.log(applicationContext, "Attempt $attempt failed: $reason — retrying")
            Result.retry()
        } else {
            exporter.writeUploadStatus(zipFile, UploadStatus.FAILED, "Failed after $attempt attempts: $reason")
            UploadLog.log(
                applicationContext,
                "Upload failed after $attempt attempts: $reason",
                UploadLog.Level.ERROR
            )
            Result.failure()
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
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
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = runCatching { conn.errorStream?.bufferedReader()?.readText()?.take(300) }.getOrNull().orEmpty()
                android.util.Log.w("UploadDatasetWorker", "get-upload-url HTTP $code $body")
                when {
                    code == 401 || code == 403 -> throw ApiUnauthorizedException(code, "get-upload-url HTTP $code ${body.trim()}")
                    else                       -> throw Exception("get-upload-url HTTP $code ${body.trim()}")
                }
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
    private suspend fun putZip(presignedUrl: String, zipFile: File): Boolean {
        val conn = URL(presignedUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Content-Type", "application/zip")
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            // Without this, HttpURLConnection buffers the entire body in memory before writing
            // any of it to the socket — for a large ZIP that's a big, avoidable allocation, and
            // the resulting delay before the first byte is sent can also let the connection go
            // stale server-side (observed as "SocketException: Broken pipe"). Fixed-length mode
            // streams directly to the socket in 64 KB chunks (our own read/write loop below)
            // instead, and also sets the Content-Length header itself.
            val total = zipFile.length()
            conn.setFixedLengthStreamingMode(total)

            // Manual chunked copy (rather than copyTo()) so we can report upload progress via
            // WorkManager's setProgress() — read by ContributeScreen from the same WorkInfo flow
            // it already observes. Updates are throttled to avoid a DB write per 64 KB chunk.
            var written = 0L
            var lastReportMs = 0L
            val buffer = ByteArray(64 * 1024)
            zipFile.inputStream().use { input ->
                conn.outputStream.use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (written == total || now - lastReportMs >= PROGRESS_THROTTLE_MS) {
                            lastReportMs = now
                            setProgress(workDataOf(KEY_PROGRESS_BYTES to written, KEY_PROGRESS_TOTAL to total))
                        }
                    }
                }
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