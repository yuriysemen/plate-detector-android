package com.github.yuriysemen.platesdetector

import android.content.Context
import android.util.Base64
import com.amazonaws.auth.AWSSessionCredentials
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoDevice
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUser
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserAttributes
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserPool
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserSession
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationDetails
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.ChallengeContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.MultiFactorAuthenticationContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.AuthenticationHandler
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.GenericHandler
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.SignUpHandler
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.VerificationHandler
import com.amazonaws.services.cognitoidentityprovider.model.SignUpResult
import com.amazonaws.regions.Regions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CognitoAuthManager(private val context: Context) {

    private var pool: CognitoUserPool? = null
    private var cachedPoolId: String = ""

    // ── Configuration ──────────────────────────────────────────────────────

    fun isConfigured(): Boolean =
        UploadPrefs.getUserPoolId(context).isNotEmpty() &&
        UploadPrefs.getAppClientId(context).isNotEmpty() &&
        UploadPrefs.getIdentityPoolId(context).isNotEmpty()

    fun isSignedIn(): Boolean = UploadPrefs.getCognitoUserId(context).isNotEmpty()

    fun currentUserEmail(): String = UploadPrefs.getCognitoUserEmail(context)

    fun currentUserId(): String = UploadPrefs.getCognitoUserId(context)

    private fun requirePool(): CognitoUserPool {
        val poolId     = UploadPrefs.getUserPoolId(context)
        val clientId   = UploadPrefs.getAppClientId(context)
        val identPoolId = UploadPrefs.getIdentityPoolId(context)
        check(poolId.isNotEmpty() && clientId.isNotEmpty() && identPoolId.isNotEmpty()) {
            "Cognito not configured — set User Pool ID, App Client ID and Identity Pool ID in Settings"
        }
        val region = identPoolId.substringBefore(":")
        if (pool == null || cachedPoolId != poolId) {
            pool = CognitoUserPool(context, poolId, clientId, null, Regions.fromName(region))
            cachedPoolId = poolId
        }
        return pool!!
    }

    // ── Sign up ────────────────────────────────────────────────────────────

    suspend fun signUp(email: String, password: String): Unit = withContext(Dispatchers.IO) {
        val p = requirePool()
        val attrs = CognitoUserAttributes().apply { addAttribute("email", email) }
        suspendCancellableCoroutine { cont ->
            p.signUpInBackground(email, password, attrs, null, object : SignUpHandler {
                override fun onSuccess(user: CognitoUser, result: SignUpResult) {
                    cont.resume(Unit)
                }
                override fun onFailure(exception: Exception) {
                    cont.resumeWithException(exception)
                }
            })
        }
    }

    // ── Resend confirmation code ───────────────────────────────────────────

    suspend fun resendConfirmationCode(email: String): Unit = withContext(Dispatchers.IO) {
        val user = requirePool().getUser(email)
        suspendCancellableCoroutine { cont ->
            user.resendConfirmationCodeInBackground(object : VerificationHandler {
                override fun onSuccess(verificationCodeDeliveryMedium: com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserCodeDeliveryDetails) {
                    cont.resume(Unit)
                }
                override fun onFailure(exception: Exception) { cont.resumeWithException(exception) }
            })
        }
    }

    // ── Confirm sign up (email verification code) ──────────────────────────

    suspend fun confirmSignUp(email: String, code: String): Unit = withContext(Dispatchers.IO) {
        val user = requirePool().getUser(email)
        suspendCancellableCoroutine { cont ->
            user.confirmSignUpInBackground(code, false, object : GenericHandler {
                override fun onSuccess() { cont.resume(Unit) }
                override fun onFailure(exception: Exception) { cont.resumeWithException(exception) }
            })
        }
    }

    // ── Sign in ────────────────────────────────────────────────────────────

    // Returns the Cognito sub (user ID) on success; saves email + sub to prefs.
    suspend fun signIn(email: String, password: String): String = withContext(Dispatchers.IO) {
        val user = requirePool().getUser(email)
        val session = suspendCancellableCoroutine { cont ->
            user.getSessionInBackground(object : AuthenticationHandler {
                override fun onSuccess(userSession: CognitoUserSession, newDevice: CognitoDevice?) {
                    cont.resume(userSession)
                }
                override fun getAuthenticationDetails(
                    authContinuation: AuthenticationContinuation,
                    userId: String
                ) {
                    authContinuation.setAuthenticationDetails(
                        AuthenticationDetails(userId, password, null)
                    )
                    authContinuation.continueTask()
                }
                override fun getMFACode(mfaContinuation: MultiFactorAuthenticationContinuation) {
                    cont.resumeWithException(UnsupportedOperationException("MFA not supported"))
                }
                override fun authenticationChallenge(continuation: ChallengeContinuation) {
                    cont.resumeWithException(
                        UnsupportedOperationException("Auth challenge not supported: ${continuation.challengeName}")
                    )
                }
                override fun onFailure(exception: Exception) {
                    cont.resumeWithException(exception)
                }
            })
        }
        val sub = subFromIdToken(session.idToken.jwtToken)
        UploadPrefs.setCognitoUserId(context, sub)
        UploadPrefs.setCognitoUserEmail(context, email)
        sub
    }

    // ── Token refresh ──────────────────────────────────────────────────────

    // Returns a fresh ID token; the SDK refreshes it automatically if expired.
    // Throws SessionExpiredException if the refresh token has also expired
    // (user must sign in again).
    suspend fun getIdToken(): String = withContext(Dispatchers.IO) {
        val currentUser = requirePool().currentUser
            ?: throw SessionExpiredException("No current user — please sign in")
        val session = suspendCancellableCoroutine { cont ->
            currentUser.getSessionInBackground(object : AuthenticationHandler {
                override fun onSuccess(userSession: CognitoUserSession, newDevice: CognitoDevice?) {
                    cont.resume(userSession)
                }
                // If we reach getAuthenticationDetails, the refresh token has expired.
                override fun getAuthenticationDetails(
                    authContinuation: AuthenticationContinuation,
                    userId: String
                ) {
                    cont.resumeWithException(SessionExpiredException("Session expired — please sign in again"))
                }
                override fun getMFACode(mfaContinuation: MultiFactorAuthenticationContinuation) {
                    cont.resumeWithException(UnsupportedOperationException("MFA not supported"))
                }
                override fun authenticationChallenge(continuation: ChallengeContinuation) {
                    cont.resumeWithException(UnsupportedOperationException("Auth challenge not supported"))
                }
                override fun onFailure(exception: Exception) {
                    cont.resumeWithException(exception)
                }
            })
        }
        session.idToken.jwtToken
    }

    // ── STS credentials ────────────────────────────────────────────────────

    // Exchanges the current ID token for short-lived STS credentials via the
    // Cognito Identity Pool. Used by UploadDatasetWorker to SigV4-sign requests.
    suspend fun getAwsCredentials(): AWSSessionCredentials = withContext(Dispatchers.IO) {
        val identityPoolId = UploadPrefs.getIdentityPoolId(context)
        val userPoolId     = UploadPrefs.getUserPoolId(context)
        check(identityPoolId.isNotEmpty()) { "Identity Pool ID not configured" }

        val region  = identityPoolId.substringBefore(":")
        val idToken = getIdToken()

        val provider = CognitoCachingCredentialsProvider(
            context,
            identityPoolId,
            Regions.fromName(region)
        )
        val loginKey = "cognito-idp.$region.amazonaws.com/$userPoolId"
        provider.logins = mapOf(loginKey to idToken)

        provider.credentials
    }

    // ── Sign out ───────────────────────────────────────────────────────────

    fun signOut() {
        runCatching { pool?.currentUser?.signOut() }
        UploadPrefs.clearCognitoUserId(context)
        UploadPrefs.clearCognitoUserEmail(context)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    // Decodes the `sub` claim from a JWT ID token without verifying the signature.
    // Signature verification is handled server-side by the Identity Pool.
    private fun subFromIdToken(jwtToken: String): String {
        val payload = jwtToken.split(".").getOrNull(1) ?: return ""
        return try {
            val json = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING)
                .toString(Charsets.UTF_8)
            JSONObject(json).optString("sub", "")
        } catch (_: Exception) {
            ""
        }
    }
}

class SessionExpiredException(message: String) : Exception(message)
