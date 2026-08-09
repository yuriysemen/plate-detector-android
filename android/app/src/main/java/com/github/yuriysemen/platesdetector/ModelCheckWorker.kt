package com.github.yuriysemen.platesdetector

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.amazonaws.DefaultRequest
import com.amazonaws.auth.AWS4Signer
import com.amazonaws.auth.AWSSessionCredentials
import com.amazonaws.http.HttpMethodName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ModelCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            performCheck(applicationContext)
            Result.success()
        } catch (e: SessionExpiredException) {
            Result.failure()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME    = "model_check_periodic"
        private const val STARTUP_NAME = "model_check_startup"

        suspend fun performCheck(context: Context) = withContext(Dispatchers.IO) {
            val ctx = context
            if (UploadPrefs.getCognitoUserId(ctx).isEmpty()) return@withContext
            val baseUrl = UploadPrefs.getUploadUrl(ctx)
            if (baseUrl.isBlank()) return@withContext

            ModelUpdateLog.log("Checking for model updates…")
            DownloadedModelPrefs.setLastCheckTime(ctx)

            val credentials = try {
                CognitoAuthManager(ctx).getAwsCredentials()
            } catch (e: SessionExpiredException) {
                CognitoAuthManager(ctx).markSessionExpired()
                ModelUpdateLog.log("Model check: session expired — sign in again", ModelUpdateLog.Level.ERROR)
                throw e
            } catch (e: Exception) {
                ModelUpdateLog.log("Model check failed: ${e.message}", ModelUpdateLog.Level.ERROR)
                throw e
            }

            @Suppress("DEPRECATION")
            val appVersion = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
            }.getOrDefault("")
            if (appVersion.isEmpty()) return@withContext

            val region = regionFromUrl(baseUrl).ifEmpty {
                UploadPrefs.getIdentityPoolId(ctx).substringBefore(":")
            }

            val response = try {
                sigV4Get("$baseUrl/get-model-url", "app_version", appVersion, credentials, region)
            } catch (e: Exception) {
                ModelUpdateLog.log("Model check failed: ${e.message}", ModelUpdateLog.Level.ERROR)
                return@withContext
            }

            val latestVersion = response.optJSONObject("latest")?.optString("model_version") ?: ""
            DownloadedModelPrefs.setLatestInfo(ctx, latestVersion)

            val compatible = response.optJSONObject("compatible") ?: run {
                ModelUpdateLog.log("No compatible model for this app version")
                return@withContext
            }
            val newS3Key = compatible.getString("s3_key")
            if (newS3Key == DownloadedModelPrefs.getS3Key(ctx)) {
                val ver = compatible.getString("model_version")
                ModelUpdateLog.log("Model is up to date (v$ver)", ModelUpdateLog.Level.SUCCESS)
                return@withContext
            }

            val newVersion = compatible.getString("model_version")
            DownloadedModelPrefs.setPending(
                ctx,
                version = newVersion,
                s3Key   = newS3Key,
                url     = compatible.getString("download_url"),
                desc    = compatible.optString("description", "")
            )
            ModelUpdateLog.log("Update available: model v$newVersion")
        }

        private fun sigV4Get(
            endpointUrl: String,
            paramName: String,
            paramValue: String,
            credentials: AWSSessionCredentials,
            region: String
        ): JSONObject {
            val fullUrl   = "$endpointUrl?$paramName=${URLEncoder.encode(paramValue, "UTF-8")}"
            val parsedUrl = URL(fullUrl)

            val sdkRequest = DefaultRequest<Any>("execute-api").apply {
                httpMethod   = HttpMethodName.GET
                endpoint     = URI("${parsedUrl.protocol}://${parsedUrl.host}")
                resourcePath = parsedUrl.path
                addParameter(paramName, paramValue)
            }
            AWS4Signer().apply {
                setServiceName("execute-api")
                setRegionName(region)
            }.sign(sdkRequest, credentials)

            val conn = URL(fullUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                sdkRequest.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.connectTimeout = 15_000
                conn.readTimeout    = 15_000
                if (conn.responseCode !in 200..299) {
                    throw Exception("get-model-url HTTP ${conn.responseCode}")
                }
                return JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            } finally {
                conn.disconnect()
            }
        }

        private fun regionFromUrl(url: String): String =
            Regex("""execute-api\.([a-z0-9-]+)\.amazonaws\.com""")
                .find(url)?.groupValues?.get(1) ?: ""

        fun schedule(context: Context) {
            val networkType = if (UploadPrefs.getUploadOnMobileData(context))
                NetworkType.CONNECTED else NetworkType.UNMETERED
            val request = PeriodicWorkRequestBuilder<ModelCheckWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runOnce(context: Context) {
            val networkType = if (UploadPrefs.getUploadOnMobileData(context))
                NetworkType.CONNECTED else NetworkType.UNMETERED
            val request = OneTimeWorkRequestBuilder<ModelCheckWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(STARTUP_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(STARTUP_NAME)
        }
    }
}
