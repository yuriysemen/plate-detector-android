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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // False once the refresh token has expired (see markSessionExpired), even though a user
    // id is still cached — that cached id/email is kept around only so the UI can tell the
    // user *who* needs to sign in again (see isSessionExpired).
    fun isSignedIn(): Boolean =
        UploadPrefs.getCognitoUserId(context).isNotEmpty() && !UploadPrefs.getSessionExpired(context)

    fun isSessionExpired(): Boolean = UploadPrefs.getSessionExpired(context)

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
                    cont.resumeWithException(
                        AuthChallengeException("This account requires multi-factor authentication, which this app doesn't support.")
                    )
                }
                override fun authenticationChallenge(continuation: ChallengeContinuation) {
                    val name = continuation.challengeName ?: "unknown"
                    val msg = if (name.equals("NEW_PASSWORD_REQUIRED", ignoreCase = true))
                        "This account must set a new password before it can be used — an administrator needs to reset it."
                    else
                        "This account can't sign in yet (identity-provider challenge: $name)."
                    cont.resumeWithException(AuthChallengeException(msg))
                }
                override fun onFailure(exception: Exception) {
                    cont.resumeWithException(exception)
                }
            })
        }
        val sub = subFromIdToken(session.idToken.jwtToken)
        if (sub.isEmpty()) {
            // A "successful" sign-in whose token we can't read would silently leave the app in a
            // "not signed in" state (empty user id). Surface it instead.
            throw IllegalStateException("Signed in, but the identity token couldn't be read. Please try again.")
        }
        // A *different* account signing in on this device: the previous user's downloaded model
        // isn't theirs to keep. (Sign-out alone no longer deletes it — see signOut().)
        val prevOwner = DownloadedModelPrefs.getOwnerSub(context)
        if (prevOwner.isNotEmpty() && prevOwner != sub) {
            java.io.File(context.filesDir, "models/downloaded").deleteRecursively()
            DownloadedModelPrefs.clearActive(context)
            DownloadedModelPrefs.clearPending(context)
        }
        UploadPrefs.setCognitoUserId(context, sub)
        UploadPrefs.setCognitoUserEmail(context, email)
        UploadPrefs.setSessionExpired(context, false)
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
                // MFA / a challenge during a *refresh* also means the cached session can't be
                // renewed silently — treat it as expiry so the UI routes to sign-in, not a crash.
                override fun getMFACode(mfaContinuation: MultiFactorAuthenticationContinuation) {
                    cont.resumeWithException(SessionExpiredException("Multi-factor sign-in required — please sign in again"))
                }
                override fun authenticationChallenge(continuation: ChallengeContinuation) {
                    cont.resumeWithException(SessionExpiredException("Sign-in needs to be renewed (${continuation.challengeName})"))
                }
                override fun onFailure(exception: Exception) {
                    // A dead/revoked refresh token, a disabled or deleted account, or a
                    // pool that no longer exists arrive here rather than via
                    // getAuthenticationDetails — all mean "re-authenticate", not "retry forever".
                    cont.resumeWithException(
                        if (isTerminalAuthFailure(exception))
                            SessionExpiredException("Sign-in is no longer valid — please sign in again (${exception.javaClass.simpleName})")
                        else exception
                    )
                }
            })
        }
        session.idToken.jwtToken
    }

    // ── STS credentials ────────────────────────────────────────────────────

    // Exchanges the current ID token for short-lived STS credentials via the
    // Cognito Identity Pool. Used by UploadDatasetWorker to SigV4-sign requests.
    //
    // `forceRefresh` clears the cached STS credentials (kept in SharedPreferences by
    // CognitoCachingCredentialsProvider) and mints new ones. Call it after an API request was
    // rejected with HTTP 401/403 while the local session still looks valid — the cached
    // credentials can go stale after a silent ID-token refresh, and a plain retry would just
    // re-send the same bad signature.
    suspend fun getAwsCredentials(forceRefresh: Boolean = false): AWSSessionCredentials =
        // Serialize across the whole process: ModelCheckWorker runs as three uncoordinated paths
        // (periodic, startup, inline "Check now") plus UploadDatasetWorker, and they otherwise
        // race on CognitoCachingCredentialsProvider's shared SharedPreferences cache — one
        // clearing/refreshing while another reads produces spurious HTTP 403s.
        credentialsMutex.withLock {
            withContext(Dispatchers.IO) {
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

                try {
                    if (forceRefresh) {
                        runCatching { provider.clearCredentials() }
                        provider.refresh()
                    }
                    provider.credentials.also { c ->
                        android.util.Log.d(
                            "CognitoAuthManager",
                            "STS creds: ak=${c.awsAccessKeyId?.take(5)}… hasSecret=${!c.awsSecretKey.isNullOrEmpty()} " +
                                "hasToken=${!c.sessionToken.isNullOrEmpty()} forceRefresh=$forceRefresh idTokenLen=${idToken.length}"
                        )
                    }
                } catch (e: SessionExpiredException) {
                    throw e
                } catch (e: Exception) {
                    // A deleted/recreated Identity Pool, or an ID token the pool no longer
                    // trusts, surfaces here — re-authentication is the only fix, so don't let
                    // the workers treat it as a transient error and retry forever.
                    if (isTerminalAuthFailure(e))
                        throw SessionExpiredException("Sign-in is no longer valid — please sign in again (${e.javaClass.simpleName})")
                    throw e
                }
            }
        }

    // ── Session expiry ─────────────────────────────────────────────────────

    // Called wherever a SessionExpiredException is caught (UploadDatasetWorker,
    // ModelCheckWorker). Deliberately lighter than signOut(): it does not delete the
    // cached user id/email (so the UI can say *who* needs to sign back in) or the
    // downloaded model — only re-authentication is required, not a full local wipe.
    fun markSessionExpired() {
        UploadPrefs.setSessionExpired(context, true)
    }

    // ── Sign out ───────────────────────────────────────────────────────────

    fun signOut() {
        // Build the pool if it isn't already so the SDK's own token store is actually cleared.
        // From a cold "still signed in" state `pool` is null and `pool?.currentUser?.signOut()`
        // is a no-op — leaving LastAuthUser + encrypted tokens behind, so getIdToken() could
        // still succeed after a "sign out".
        runCatching { requirePool().currentUser?.signOut() }
        clearSdkAuthCaches(context)
        UploadPrefs.clearCognitoUserId(context)
        UploadPrefs.clearCognitoUserEmail(context)
        UploadPrefs.setSessionExpired(context, false)
        // The downloaded model is NOT deleted here (deviation from REQ-016): it's the same model
        // for every user, and deleting it on every sign-out made a broken-auth device unusable
        // (no model + can't re-download). It's removed instead when a *different* account signs
        // in (see signIn()) or the backend is reconfigured (AppConfig.seedPrefsIfNeeded).
        DownloadedModelPrefs.clearPending(context)
        pool = null
        cachedPoolId = ""
    }

    /** Full local wipe including the downloaded model — used only when the app is reconfigured
     *  for a different Cognito backend, where the model's S3 keys/URLs are also stale. */
    fun signOutAndWipeModel() {
        signOut()
        java.io.File(context.filesDir, "models/downloaded").deleteRecursively()
        DownloadedModelPrefs.clearActive(context)
    }

    /** Wipes the AWS SDK's own auth SharedPreferences (encrypted user-pool tokens + the cached
     *  Identity-Pool identity id / STS credentials). Used on sign-out and when the app is
     *  reconfigured for a different Cognito backend. */
    private fun clearSdkAuthCaches(ctx: Context) {
        listOf("CognitoIdentityProviderCache", "com.amazonaws.android.auth").forEach { name ->
            runCatching {
                ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
            }
        }
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

    companion object {
        // Serializes STS-credential fetches across every CognitoAuthManager instance in the
        // process (workers create their own). See getAwsCredentials().
        private val credentialsMutex = Mutex()

        // Cognito exception simple-names that mean "this cached session is permanently dead —
        // re-authenticate", not "retry". Matched by simple name so both the
        // cognitoidentityprovider and cognitoidentity packages' same-named variants are caught.
        private val TERMINAL_AUTH_ERRORS = setOf(
            "NotAuthorizedException",         // wrong/revoked/expired refresh token, disabled user
            "UserNotFoundException",          // account deleted
            "UserNotConfirmedException",
            "PasswordResetRequiredException",
            "ResourceNotFoundException",      // user pool / identity pool no longer exists
        )

        internal fun isTerminalAuthFailure(e: Throwable): Boolean =
            e.javaClass.simpleName in TERMINAL_AUTH_ERRORS
    }
}

class SessionExpiredException(message: String) : Exception(message)

/** A Cognito sign-in challenge this app can't complete (MFA, NEW_PASSWORD_REQUIRED, …). Carries a
 *  user-facing message; surfaced by AuthScreen's friendlyAuthError. */
class AuthChallengeException(message: String) : Exception(message)

/**
 * An authenticated API call (API Gateway / Lambda) was rejected with HTTP 401 or 403 while the
 * Cognito session itself is valid (token refresh + STS exchange succeeded). This is an
 * *authorization* failure, NOT session expiry:
 *  - 403 = authenticated but the IAM principal isn't allowed to invoke this route (e.g. a
 *    `curators`-group member whose token resolves to `CuratorRole`, which may lack
 *    `execute-api:Invoke` until the infra is redeployed).
 *  - 401 = the signature couldn't be verified (config drift, or freshly-minted creds still
 *    rejected).
 * Neither should sign the user out — the session works fine for everything else.
 */
class ApiUnauthorizedException(val httpCode: Int, message: String) : Exception(message)

/** A non-2xx HTTP response from an authenticated endpoint that is worth retrying — 429 or 5xx.
 *  A worker that catches this should return Result.retry(), not swallow it. */
class RetryableHttpException(message: String) : Exception(message)
