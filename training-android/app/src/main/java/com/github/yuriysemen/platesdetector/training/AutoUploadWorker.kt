package com.github.yuriysemen.platesdetector.training

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AutoUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppConfig.seedPrefsIfNeeded(applicationContext)

        val uploadUrl     = UploadPrefs.getUploadUrl(applicationContext)
        val userId        = UploadPrefs.getCognitoUserId(applicationContext)
        val userPoolId    = UploadPrefs.getUserPoolId(applicationContext)
        val identityPoolId = UploadPrefs.getIdentityPoolId(applicationContext)

        // isSignedIn() is false once the session is expired — bail BEFORE exportSync(), which
        // would otherwise package the ZIP and wipe the local frame buffer for an upload that
        // can only fail auth.
        if (!CognitoAuthManager(applicationContext).isSignedIn()) {
            UploadLog.log(applicationContext, "Daily auto-upload skipped — signed out or session expired")
            return@withContext Result.failure()
        }
        if (uploadUrl.isBlank() || userPoolId.isBlank() || identityPoolId.isBlank()) {
            UploadLog.log(applicationContext, "Daily auto-upload skipped — upload not configured")
            return@withContext Result.failure()
        }

        val exporter = DatasetExporter(applicationContext)
        val frameCount = exporter.readStats().totalFrames
        if (frameCount == 0) return@withContext Result.success()

        // exportSync packages the ZIP and resets collected frames on success
        val zipFile = runCatching { exporter.exportSync() }
            .getOrElse {
                UploadLog.log(
                    applicationContext,
                    "Daily auto-upload: packaging failed (${it.message ?: it.javaClass.simpleName}) — will retry"
                )
                return@withContext Result.retry()
            }

        val deviceId = DatasetExporter.computeDeviceId(
            Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        )
        val networkType = if (UploadPrefs.getUploadOnMobileData(applicationContext))
            NetworkType.CONNECTED else NetworkType.UNMETERED

        val uploadRequest = OneTimeWorkRequestBuilder<UploadDatasetWorker>()
            .setInputData(workDataOf(
                UploadDatasetWorker.KEY_ZIP_PATH         to zipFile.absolutePath,
                UploadDatasetWorker.KEY_DEVICE_ID        to deviceId,
                UploadDatasetWorker.KEY_UPLOAD_URL       to uploadUrl,
                UploadDatasetWorker.KEY_USER_ID          to userId,
                UploadDatasetWorker.KEY_USER_POOL_ID     to userPoolId,
                UploadDatasetWorker.KEY_IDENTITY_POOL_ID to identityPoolId,
                UploadDatasetWorker.KEY_FRAME_COUNT      to frameCount,
                UploadDatasetWorker.KEY_IS_AUTO_UPLOAD   to true,
            ))
            .addTag(zipFile.absolutePath)
            .addTag(UploadDatasetWorker.TAG_DATASET_UPLOAD)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(zipFile.absolutePath, ExistingWorkPolicy.KEEP, uploadRequest)

        UploadLog.log(applicationContext, "Daily auto-upload queued — $frameCount frames")

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        UploadPrefs.setAutoUploadLastDate(applicationContext, today)

        Result.success()
    }

    companion object {
        private const val WORK_NAME = "auto_upload_daily"

        fun schedule(context: Context) {
            if (UploadPrefs.getUploadUrl(context).isBlank()) {
                cancel(context)
                return
            }

            val initialDelay = computeInitialDelay(UploadPrefs.getAutoUploadTime(context))
            val networkType = if (UploadPrefs.getUploadOnMobileData(context))
                NetworkType.CONNECTED else NetworkType.UNMETERED

            val request = PeriodicWorkRequestBuilder<AutoUploadWorker>(
                24, TimeUnit.HOURS,
                30, TimeUnit.MINUTES        // flex window
            )
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Called on every app start. Enqueues a one-shot AutoUploadWorker if:
         * - auto-upload is enabled and upload URL is set
         * - there are frames to upload
         * - we haven't already auto-uploaded today
         * - the network constraint is satisfied right now
         *
         * The work is enqueued with the network constraint so WorkManager runs it
         * immediately if the network is up, or waits if it drops after this check.
         */
        fun runCatchUpIfNeeded(context: Context) {
            if (UploadPrefs.getUploadUrl(context).isBlank()) return
            // Same guard as doWork(): never package/upload while the session is dead.
            if (!CognitoAuthManager(context).isSignedIn()) return

            val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            val lastDate = UploadPrefs.getAutoUploadLastDate(context)
            if (lastDate == today) return  // already ran today

            if (DatasetExporter(context).readStats().totalFrames == 0) return
            if (!isNetworkSatisfied(context)) return

            val networkType = if (UploadPrefs.getUploadOnMobileData(context))
                NetworkType.CONNECTED else NetworkType.UNMETERED

            val request = OneTimeWorkRequestBuilder<AutoUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork("auto_upload_catchup", ExistingWorkPolicy.KEEP, request)
        }

        private fun isNetworkSatisfied(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            val allowMobile = UploadPrefs.getUploadOnMobileData(context)
            return allowMobile || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        // Returns milliseconds until the next occurrence of the given HH:mm time.
        private fun computeInitialDelay(timeStr: String): Long {
            val parts  = timeStr.split(":").mapNotNull { it.toIntOrNull() }
            val hour   = parts.getOrElse(0) { 2 }
            val minute = parts.getOrElse(1) { 0 }

            val now    = Calendar.getInstance()
            val target = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (target.timeInMillis <= now.timeInMillis) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }
}
