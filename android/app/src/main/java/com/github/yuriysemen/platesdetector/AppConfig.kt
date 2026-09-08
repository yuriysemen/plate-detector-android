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

    /**
     * Reconciles `UploadPrefs` with the build-time Cognito/API constants.
     *
     * Beyond first-launch seeding this also **re-seeds** when the APK was rebuilt against a
     * different backend (e.g. the CloudFormation stack was torn down and redeployed): without
     * this the app keeps calling a User/Identity Pool that no longer exists and every auth call
     * fails with `ResourceNotFoundException` forever, fixable only by "Clear storage".
     *
     * A build-time value is never allowed to overwrite a stored one with the empty string
     * (release builds without `local.properties` would otherwise wipe a working config).
     *
     * @return true if the **identity** configuration (user pool / app client / identity pool)
     *         changed — the caller must then treat the user as signed out, because the cached
     *         tokens and STS identity belong to the old backend.
     */
    fun seedPrefsIfNeeded(context: Context): Boolean {
        if (!isConfigured) return false

        var identityChanged = false
        fun reconcile(current: String, wanted: String, isIdentity: Boolean, set: (String) -> Unit) {
            if (wanted.isNotEmpty() && wanted != current) {
                set(wanted)
                if (isIdentity && current.isNotEmpty()) identityChanged = true
            }
        }

        reconcile(UploadPrefs.getUserPoolId(context), userPoolId, true) { UploadPrefs.setUserPoolId(context, it) }
        reconcile(UploadPrefs.getAppClientId(context), appClientId, true) { UploadPrefs.setAppClientId(context, it) }
        reconcile(UploadPrefs.getIdentityPoolId(context), identityPoolId, true) { UploadPrefs.setIdentityPoolId(context, it) }
        reconcile(UploadPrefs.getUploadUrl(context), uploadServiceUrl, false) { UploadPrefs.setUploadUrl(context, it) }

        if (identityChanged) {
            // Clears the session, the SDK's token/identity caches, AND the downloaded model —
            // its S3 keys/URLs belonged to the old backend too.
            CognitoAuthManager(context).signOutAndWipeModel()
        }
        return identityChanged
    }
}
