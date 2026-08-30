package com.github.yuriysemen.platesdetector.curation

/**
 * Build-time configuration, populated from `local.properties` via `buildConfigField`
 * (see app/build.gradle.kts). Same underlying Cognito backend as the main `android/` app;
 * entered independently because this is a standalone Gradle project (REQ-022).
 */
object CurationConfig {
    val userPoolId: String     get() = BuildConfig.COGNITO_USER_POOL_ID
    val appClientId: String    get() = BuildConfig.COGNITO_APP_CLIENT_ID
    val identityPoolId: String get() = BuildConfig.COGNITO_IDENTITY_POOL_ID
    val datasetBucket: String  get() = BuildConfig.DATASET_BUCKET_NAME

    /** AWS region, derived from the Identity Pool ID prefix (e.g. `us-east-1:<uuid>`). */
    val region: String get() = identityPoolId.substringBefore(":")

    val isConfigured: Boolean
        get() = userPoolId.isNotEmpty() && appClientId.isNotEmpty() &&
                identityPoolId.isNotEmpty() && datasetBucket.isNotEmpty()

    /** Cognito login key for the Identity Pool `logins` map. */
    val cognitoLoginKey: String
        get() = "cognito-idp.$region.amazonaws.com/$userPoolId"
}
