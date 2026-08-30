package com.github.yuriysemen.platesdetector.curation

import android.content.Context
import android.util.Base64
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoDevice
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserPool
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserSession
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationDetails
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.ChallengeContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.MultiFactorAuthenticationContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.AuthenticationHandler
import com.amazonaws.regions.Regions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cognito sign-in for the single trusted curator. Trimmed copy of the main app's
 * `CognitoAuthManager` (REQ-022 — no shared module): SRP sign-in, token refresh, STS-credential
 * exchange via the existing Identity Pool. There is no sign-up / confirm flow — curators are
 * provisioned manually and added to the `curators` Cognito group by an operator.
 */
class CuratorAuthManager(private val context: Context) {

    private var pool: CognitoUserPool? = null

    // ── State ──────────────────────────────────────────────────────────────

    fun isConfigured(): Boolean = CurationConfig.isConfigured

    fun isSignedIn(): Boolean =
        CuratorPrefs.getUserId(context).isNotEmpty() && !CuratorPrefs.getSessionExpired(context)

    fun isSessionExpired(): Boolean = CuratorPrefs.getSessionExpired(context)

    fun currentUserEmail(): String = CuratorPrefs.getUserEmail(context)

    private fun requirePool(): CognitoUserPool {
        check(CurationConfig.isConfigured) {
            "Cognito not configured — set COGNITO_* and DATASET_BUCKET_NAME in local.properties"
        }
        return pool ?: CognitoUserPool(
            context,
            CurationConfig.userPoolId,
            CurationConfig.appClientId,
            null,
            Regions.fromName(CurationConfig.region)
        ).also { pool = it }
    }

    // ── Sign in ────────────────────────────────────────────────────────────

    /** Returns the Cognito sub on success; caches email + sub. */
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
        val sub = claimFromIdToken(session.idToken.jwtToken).optString("sub", "")
        CuratorPrefs.setUserId(context, sub)
        CuratorPrefs.setUserEmail(context, email)
        CuratorPrefs.setSessionExpired(context, false)
        sub
    }

    // ── Token refresh ──────────────────────────────────────────────────────

    /** Fresh ID token; the SDK auto-refreshes. Throws [SessionExpiredException] if the refresh
     *  token has also expired. */
    suspend fun getIdToken(): String = withContext(Dispatchers.IO) {
        val currentUser = requirePool().currentUser
            ?: throw SessionExpiredException("No current user — please sign in")
        val session = suspendCancellableCoroutine { cont ->
            currentUser.getSessionInBackground(object : AuthenticationHandler {
                override fun onSuccess(userSession: CognitoUserSession, newDevice: CognitoDevice?) {
                    cont.resume(userSession)
                }
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

    // ── Group membership ───────────────────────────────────────────────────

    /** `cognito:groups` claim from the current ID token (empty if none). */
    suspend fun groups(): List<String> =
        cognitoGroupsFromClaims(claimFromIdToken(getIdToken()))

    suspend fun isCurator(): Boolean = CURATORS_GROUP in groups()

    // ── STS credentials ────────────────────────────────────────────────────

    /** A fresh credentials provider bound to the current ID token. Callers that hold it for a
     *  while should rebuild it after a [SessionExpiredException]. Because the ID token carries
     *  the `curators` group, the Identity Pool's token-based role mapping hands back
     *  `CuratorRole` credentials. */
    suspend fun newCredentialsProvider(): CognitoCachingCredentialsProvider =
        withContext(Dispatchers.IO) {
            val idToken = getIdToken()
            CognitoCachingCredentialsProvider(
                context,
                CurationConfig.identityPoolId,
                Regions.fromName(CurationConfig.region)
            ).apply {
                logins = mapOf(CurationConfig.cognitoLoginKey to idToken)
                // Drop any STS credentials cached on disk from an earlier session (e.g. before
                // this account was granted CuratorRole) so the exchange re-runs against the
                // current token's role claims.
                clearCredentials()
            }
        }

    /** True when the current ID token actually carries a `curators`-group *role* claim
     *  (`cognito:preferred_role` / `cognito:roles`). A token that lists the group but predates
     *  the group's IAM role assignment will still resolve to the default role — the fix is a
     *  fresh sign-in. */
    suspend fun tokenHasCuratorRoleClaim(): Boolean {
        val claims = claimFromIdToken(getIdToken())
        if (claims.optString("cognito:preferred_role").contains(":role/", ignoreCase = true)) return true
        val roles = claims.optJSONArray("cognito:roles") ?: return false
        return roles.length() > 0
    }

    // ── Session expiry / sign out ──────────────────────────────────────────

    fun markSessionExpired() = CuratorPrefs.setSessionExpired(context, true)

    fun signOut() {
        runCatching { pool?.currentUser?.signOut() }
        runCatching {
            CognitoCachingCredentialsProvider(
                context,
                CurationConfig.identityPoolId,
                Regions.fromName(CurationConfig.region)
            ).clear()
        }
        CuratorPrefs.clear(context)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Decodes a JWT payload without verifying the signature — the Identity Pool verifies it
     *  server-side when exchanging the token for credentials. */
    private fun claimFromIdToken(jwtToken: String): JSONObject {
        val payload = jwtToken.split(".").getOrNull(1) ?: return JSONObject()
        return try {
            val json = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING)
                .toString(Charsets.UTF_8)
            JSONObject(json)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    companion object {
        const val CURATORS_GROUP = "curators"
    }
}

class SessionExpiredException(message: String) : Exception(message)

/** Extracts the `cognito:groups` string array from a decoded ID-token claims object. */
internal fun cognitoGroupsFromClaims(claims: JSONObject): List<String> {
    val arr = claims.optJSONArray("cognito:groups") ?: JSONArray()
    return (0 until arr.length()).mapNotNull { arr.optString(it, null) }
}
