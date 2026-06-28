package com.github.yuriysemen.platesdetector

import android.content.Context
import androidx.core.content.edit

internal object DownloadedModelPrefs {
    private const val PREFS = "model_prefs"

    private const val KEY_VERSION   = "downloaded_model_version"
    private const val KEY_S3_KEY    = "downloaded_model_s3_key"

    private const val KEY_PENDING_VERSION      = "pending_model_version"
    private const val KEY_PENDING_S3_KEY       = "pending_model_s3_key"
    private const val KEY_PENDING_DOWNLOAD_URL = "pending_model_download_url"
    private const val KEY_PENDING_DESCRIPTION  = "pending_model_description"

    private const val KEY_LATEST_VERSION  = "latest_model_version"
    private const val KEY_LAST_CHECK_TIME = "model_last_check_time"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getVersion(ctx: Context): String  = prefs(ctx).getString(KEY_VERSION, "") ?: ""
    fun getS3Key(ctx: Context): String    = prefs(ctx).getString(KEY_S3_KEY, "") ?: ""

    fun setActive(ctx: Context, version: String, s3Key: String) {
        prefs(ctx).edit { putString(KEY_VERSION, version); putString(KEY_S3_KEY, s3Key) }
    }

    fun clearActive(ctx: Context) {
        prefs(ctx).edit { remove(KEY_VERSION); remove(KEY_S3_KEY) }
    }

    fun getPendingVersion(ctx: Context): String     = prefs(ctx).getString(KEY_PENDING_VERSION, "") ?: ""
    fun getPendingS3Key(ctx: Context): String       = prefs(ctx).getString(KEY_PENDING_S3_KEY, "") ?: ""
    fun getPendingDownloadUrl(ctx: Context): String = prefs(ctx).getString(KEY_PENDING_DOWNLOAD_URL, "") ?: ""
    fun getPendingDescription(ctx: Context): String = prefs(ctx).getString(KEY_PENDING_DESCRIPTION, "") ?: ""

    fun setPending(ctx: Context, version: String, s3Key: String, url: String, desc: String) {
        prefs(ctx).edit {
            putString(KEY_PENDING_VERSION, version)
            putString(KEY_PENDING_S3_KEY, s3Key)
            putString(KEY_PENDING_DOWNLOAD_URL, url)
            putString(KEY_PENDING_DESCRIPTION, desc)
        }
    }

    fun clearPending(ctx: Context) {
        prefs(ctx).edit {
            remove(KEY_PENDING_VERSION)
            remove(KEY_PENDING_S3_KEY)
            remove(KEY_PENDING_DOWNLOAD_URL)
            remove(KEY_PENDING_DESCRIPTION)
        }
    }

    fun getLatestVersion(ctx: Context): String = prefs(ctx).getString(KEY_LATEST_VERSION, "") ?: ""

    fun setLatestInfo(ctx: Context, version: String) {
        prefs(ctx).edit { putString(KEY_LATEST_VERSION, version) }
    }

    fun getLastCheckTime(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_CHECK_TIME, 0L)

    fun setLastCheckTime(ctx: Context) {
        prefs(ctx).edit { putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()) }
    }
}
