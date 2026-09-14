---
id: REQ-027
title: Auth-Failure Detection and Always-Available Re-Sign-In
status: done
priority: high
depends_on: REQ-014, REQ-016
---

> **Implemented in `android/` originally; as of [REQ-031](REQ-031-done-split-detection-and-training-apps.md) this functionality lives in `training-android/`, not `android/`.**

## Summary

When an authenticated API call (`get-model-url`, `get-upload-url`) is rejected with **HTTP 401/403**
while the local Cognito session still *looks* valid, the app previously did nothing useful: it
logged `"Model check failed: get-model-url HTTP 403"` and carried on believing the user was fully
signed in. The auth-status UI kept showing **"Sign out"** (not "Sign in"), and the only way to get
fresh tokens was a full sign-out — which **deletes the downloaded model** and, with no bundled
model, drops the user onto `NoModelsScreen`, whose only control is "Retry". Net result: a user
with a wedged session **cannot sign in or sign out**.

This requirement makes an API 401/403 a recognised auth-failure signal, adds a non-destructive
"sign in again", and guarantees a sign-in path from every screen including `NoModelsScreen`.

---

## Motivation

Real report: `Model check failed: get-model-url HTTP 403`, user unable to log in or out.

Root causes in the code:

1. Only a `SessionExpiredException` from the **token-refresh** path (`getIdToken()` reaching
   `getAuthenticationDetails`) flipped the app into `sessionExpired` state. An HTTP 403 from API
   Gateway — stale cached STS credentials after a silent ID-token refresh, a dead session the SDK
   cache hasn't noticed, clock skew, or an IAM/permissions problem — was swallowed as a generic
   error.
2. `signOut()` deletes `filesDir/models/downloaded/` (REQ-016). With no bundled asset model that
   yields `NoModelsScreen`, which had no auth controls → dead end.
3. No way to re-authenticate without signing out first.

---

## Changes

### 1. HTTP 401/403 from an authed endpoint → `ApiUnauthorizedException`

New exception `ApiUnauthorizedException` (in `CognitoAuthManager.kt`, alongside
`SessionExpiredException`). The workers treat it the same as `SessionExpiredException` — ask the
user to sign in again — but it stays a distinct type so logs can tell "token refresh said expired"
from "the server said no".

- `ModelCheckWorker.sigV4Get`: response code `401` or `403` → throw `ApiUnauthorizedException`
  (other non-2xx → generic `Exception`, unchanged).
- `UploadDatasetWorker.requestPresignedUrl` (`get-upload-url`): same.
- **Not changed:** the S3 presigned-PUT `403` in `UploadDatasetWorker.putZip` — that genuinely
  means "presigned URL expired" and already has its own re-request-once retry.

### 2. One forced-credentials-refresh retry before giving up

New `CognitoAuthManager.getAwsCredentials(forceRefresh: Boolean = false)`. When `true` it calls
`CognitoCachingCredentialsProvider.clearCredentials()` + `refresh()` before returning, discarding
the cached STS credentials kept in `SharedPreferences`.

On the first `ApiUnauthorizedException`:

1. `getAwsCredentials(forceRefresh = true)` — mint brand-new STS credentials.
   - If this throws `SessionExpiredException` (refresh token dead too) → `markSessionExpired()`,
     rethrow. Done.
2. Retry the endpoint call **once** with the fresh credentials.
   - Success → carry on normally (it was just a stale cache).
   - `ApiUnauthorizedException` again → `markSessionExpired()`, rethrow.

`ModelCheckWorker.doWork` maps both `SessionExpiredException` and `ApiUnauthorizedException` to
`Result.failure()` (no WorkManager retry). `UploadDatasetWorker.doWork` maps both to
`writeUploadStatus(FAILED)` + `Result.failure()`.

### 3. Stop hammering a known-dead session

`ModelCheckWorker.performCheck` returns early (with one ERROR log line) when
`CognitoAuthManager.isSessionExpired()` is already `true` — the hourly worker no longer re-hits the
endpoint or spams the activity log every hour while the user hasn't re-authenticated yet.

### 4. Non-destructive "Sign in again"

- **ContributeScreen** auth-status row, when `isSignedIn`: now shows **"Sign in again"** above
  **"Sign out"**. "Sign in again" re-runs `AuthScreen` for the same account → fresh tokens via
  `signIn()` (which also clears `sessionExpired`) — the downloaded model is **not** touched.
- The existing `sessionExpired` branch (red banner + "Sign in") is unchanged and is now also
  reached by an API 401/403, not only by a token-refresh failure.

### 5. `NoModelsScreen` is no longer a dead end

`NoModelsScreen` gains auth controls:

| State | Buttons | Body text |
|---|---|---|
| signed out | **Retry**, **Sign in** | "No model was bundled… Sign in to download one…" |
| signed in | **Retry**, **Sign out** | "No model is available yet. Tap Retry…" |
| session expired | **Retry**, **Sign in again** | "Your sign-in has expired, so no model could be downloaded…" |

When `models.isEmpty()`, `LivePlateDetectionScreen` now renders `AuthScreen` if `showAuth` is set
(previously the early `return` for the no-model case pre-empted it).

### 6. Foreground re-sync of auth state

`LivePlateDetectionScreen` observes `Lifecycle.Event.ON_START` and re-reads
`isSignedIn` / `signedInEmail` / `sessionExpired` from `CognitoAuthManager`. A session marked
expired by a background worker now surfaces on the camera screen when the app is next resumed,
not only after navigating to ContributeScreen. `onCheckNow` (Settings "Check now") also re-syncs
these after running `performCheck` inline.

### 7. Settings expired card

`SettingsScreen` takes a `sessionExpired` param and, when `true`, shows a card directly under the
title: *"Your sign-in has expired. Model updates and uploads are paused until you sign in again."*
+ a **Sign in** button that opens ContributeScreen. (Independent of the "Contribute data" toggle —
model updates need auth regardless of whether the user contributes frames.)

### Shared sign-in / sign-out handlers

`LivePlateDetectionScreen` now has single `handleSignedIn` / `handleSignOut` lambdas used by
`AuthScreen`, `ContributeScreen`, and `NoModelsScreen`, so the state transition is identical
everywhere.

---

## Acceptance criteria

- [x] `get-model-url` / `get-upload-url` returning HTTP 401 or 403 throws `ApiUnauthorizedException`
      (S3 presigned-PUT 403 handling unchanged).
- [x] On the first API 401/403, the worker mints fresh STS credentials
      (`getAwsCredentials(forceRefresh = true)`) and retries the call exactly once.
- [x] If the retry also gets 401/403 (or the credential refresh throws `SessionExpiredException`),
      `markSessionExpired()` is called and the worker fails without a WorkManager retry.
- [x] `ModelCheckWorker.performCheck` returns early when the session is already known expired — no
      hourly endpoint hit, one log line.
- [x] ContributeScreen shows **"Sign in again"** (non-destructive) alongside "Sign out" when
      signed in; it re-runs the sign-in flow and keeps the downloaded model.
- [x] `NoModelsScreen` always offers a working sign-in / sign-out path; `AuthScreen` renders over
      the no-model state when `showAuth` is set.
- [x] Auth state re-syncs on `ON_START` and after Settings "Check now".
- [x] `SettingsScreen` shows an expired card with a Sign-in shortcut when `sessionExpired`.
- [x] Signing out from `NoModelsScreen` (no bundled model) leaves the user on `NoModelsScreen`
      with a **Sign in** button — no dead end.
- [x] Built + unit-tested: `:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:testDebugUnitTest`
      all pass (JDK via Android Studio's bundled JBR).

> **Verification gap:** no on-device run — the real 403 scenario needs a wedged session to
> reproduce. The forced-refresh path and the `NoModelsScreen` / re-sign-in flows are build- and
> unit-verified only.

---

## Notes / trade-offs

- An HTTP 403 is not *provably* an expired session — it can be an IAM/permissions or API-Gateway
  config problem. The forced-refresh retry distinguishes "stale cache" (fixed automatically) from
  "still denied". A persistent server-side 403 will keep flipping the user to `sessionExpired`
  after each hourly check until they re-auth; the activity-log message
  (`"still not authorized after refresh — sign in again"`) points at that. This is an accepted
  trade-off: the re-auth is cheap and non-destructive, and the alternative (silent failure) is
  what caused this ticket.
- `markSessionExpired()` is deliberately lighter than `signOut()` — it keeps the cached
  email + the downloaded model, so re-auth is one screen and the model survives.
