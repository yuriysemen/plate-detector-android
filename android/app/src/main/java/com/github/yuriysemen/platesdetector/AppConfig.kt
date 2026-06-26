package com.github.yuriysemen.platesdetector

import android.content.Context

object AppConfig {
    val userPoolId: String      get() = BuildConfig.COGNITO_USER_POOL_ID
    val appClientId: String     get() = BuildConfig.COGNITO_APP_CLIENT_ID
    val identityPoolId: String  get() = BuildConfig.COGNITO_IDENTITY_POOL_ID
    val uploadServiceUrl: String get() = BuildConfig.UPLOAD_SERVICE_URL

    val isConfigured: Boolean
        get() = userPoolId.isNotEmpty() && appClientId.isNotEmpty() &&
                identityPoolId.isNotEmpty() && uploadServiceUrl.isNotEmpty()

    // Seeds UploadPrefs from build-time constants if the prefs haven't been set yet.
    // Calling this on every app start is safe — it's a no-op if already seeded.
    fun seedPrefsIfNeeded(context: Context) {
        if (!isConfigured) return
        if (UploadPrefs.getUserPoolId(context).isEmpty())
            UploadPrefs.setUserPoolId(context, userPoolId)
        if (UploadPrefs.getAppClientId(context).isEmpty())
            UploadPrefs.setAppClientId(context, appClientId)
        if (UploadPrefs.getIdentityPoolId(context).isEmpty())
            UploadPrefs.setIdentityPoolId(context, identityPoolId)
        if (UploadPrefs.getUploadUrl(context).isEmpty())
            UploadPrefs.setUploadUrl(context, uploadServiceUrl)
    }
}
