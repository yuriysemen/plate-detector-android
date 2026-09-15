package com.github.yuriysemen.platesdetector.curation

import android.content.Context

/**
 * Small SharedPreferences store for the signed-in curator's identity. Mirrors the subset of
 * the main app's `UploadPrefs` this app needs. The cached email/sub are kept even after the
 * refresh token expires so the UI can say *who* needs to sign in again.
 */
internal object CuratorPrefs {
    private const val FILE = "curator_prefs"
    private const val KEY_USER_ID = "cognito_user_id"
    private const val KEY_USER_EMAIL = "cognito_user_email"
    private const val KEY_SESSION_EXPIRED = "session_expired"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getUserId(context: Context): String =
        prefs(context).getString(KEY_USER_ID, "") ?: ""

    fun setUserId(context: Context, value: String) =
        prefs(context).edit().putString(KEY_USER_ID, value).apply()

    fun getUserEmail(context: Context): String =
        prefs(context).getString(KEY_USER_EMAIL, "") ?: ""

    fun setUserEmail(context: Context, value: String) =
        prefs(context).edit().putString(KEY_USER_EMAIL, value).apply()

    fun getSessionExpired(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SESSION_EXPIRED, false)

    fun setSessionExpired(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SESSION_EXPIRED, value).apply()

    fun clear(context: Context) =
        prefs(context).edit()
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_SESSION_EXPIRED)
            .apply()
}
