---
id: REQ-029
title: Auth Robustness — Curator API Access + Login/Logout Failure-Path Hardening
status: done
priority: high
depends_on: REQ-014, REQ-016, REQ-022, REQ-027, REQ-028
supersedes_partial: REQ-016
---

> **Implemented in `android-end-user-app/` originally; as of [REQ-031](REQ-031-done-split-detection-and-training-apps.md) this functionality lives in `android-training-data-collection-app/`, not `android-end-user-app/`.**

## Summary

Two parts, from one investigation into a `get-model-url HTTP 403` / "session expired" report:

1. **Infra root cause.** A user in the `curators` group resolves to `CuratorRole` in *both* apps
   (the Identity Pool's `Type: Token` role mapping is keyed on the shared User Pool client, not
   the app). `CuratorRole` had no `execute-api:Invoke` → the main app's `get-model-url` /
   `get-upload-url` calls got 403, then the app looped on "sign in again" forever with no fix.
   → `CuratorRole` now also grants `execute-api:Invoke` on the two API routes.

2. **App-side hardening.** A full review of every login/logout path in `android-end-user-app/` turned up 13
   failure points (silent infinite retries, traps, races). All fixed below.

---

## Part 1 — `CuratorRole` API access (`aws-training-infra/aws/template.yaml`)

Added an `InvokeUploadApi` inline policy to `CuratorRole` mirroring `DeviceAuthRole`:

```yaml
- PolicyName: InvokeUploadApi
  PolicyDocument:
    Statement:
      - Effect: Allow
        Action: execute-api:Invoke
        Resource:
          - .../${StageName}/POST/get-upload-url
          - .../${StageName}/GET/get-model-url
```

A curator is a trusted superset user, so letting a curator device also use the main app's upload
and model endpoints is acceptable. The stricter alternative — a separate User Pool client for the
curation app so the role mapping can distinguish them — is a much larger change (curation-app
rebuild + reconfig) and deferred.

**Deploy:** `cd aws-training-infra/aws && sam build && sam deploy`. No app change needed; after deploy a
curator signs in again in the main app and the 403s stop.

---

## Part 1b — correction: a persistent 401/403 must NOT sign the user out

REQ-027's first cut made `UploadDatasetWorker` / `ModelCheckWorker`, after a
forced-credential-refresh retry that still got 401/403, call `markSessionExpired()`. That was
wrong: an HTTP **403** from API Gateway means *authenticated but not authorized for this route*
(the exact curator-role case), and **401** means *signature unverifiable* (config drift). The
Cognito session is valid in both — signing the user out produced an endless loop for a curator
(sign in → `ModelCheckWorker.runOnce` → 403 → session expired → 30 s poll surfaces it → "sign in
again" → …).

Now:
- `ApiUnauthorizedException` carries `httpCode`.
- After the one forced-refresh retry, a persistent 401/403 **fails the job with an actionable
  message and leaves the session intact** — it does NOT `markSessionExpired()`. 403 → "your
  account isn't authorized to upload / fetch model updates; if you were just granted access, sign
  out and back in; otherwise the API needs a config fix." 401 → "app/server config mismatch."
- `markSessionExpired()` is now reached only from a genuine `SessionExpiredException` (token
  refresh failed / terminal Cognito error).
- Both workers log the endpoint's HTTP status **and response body** (`conn.errorStream`, first
  300 chars) via `Log.w`, and `getAwsCredentials` logs the STS access-key prefix / token presence
  via `Log.d` — so the real 401-vs-403 and the API Gateway message are visible in logcat.

---

## Part 2 — `android-end-user-app/` login/logout hardening

### Bugs

| # | Fix |
|---|---|
| 1 | **`AutoUploadWorker` / `runCatchUpIfNeeded` now bail when `!isSignedIn()`** (false once the session is expired) — *before* `exportSync()`, which was packaging the ZIP and wiping the local frame buffer for an upload that could only fail auth. |
| 2 | **Terminal auth errors → `SessionExpiredException`.** `CognitoAuthManager` now maps `NotAuthorizedException` / `UserNotFoundException` / `PasswordResetRequiredException` / `UserNotConfirmedException` / `ResourceNotFoundException` (from both the user-pool `getSession` `onFailure` path and the Identity-Pool credential exchange) to `SessionExpiredException`. Previously these fell through as generic exceptions → `Result.retry()` forever, UI stuck on "signed in". |
| 3 | **`AppConfig.seedPrefsIfNeeded` now re-seeds on backend change.** It compares each `BuildConfig` value against the stored pref and overwrites when they differ (never overwriting with an empty build value); if the *identity* config (user pool / app client / identity pool) changed it calls `signOutAndWipeModel()` and returns `true` so the caller drops the stale session. Fixes the "stack redeployed + app updated → infinite `ResourceNotFoundException`, only fix is Clear storage" trap. Now also called at the top of all three workers. |
| 4 | **`signOut()` builds the pool if needed** (`requirePool().currentUser?.signOut()`) and additionally `clear()`s the SDK's own auth SharedPreferences (`CognitoIdentityProviderCache`, `com.amazonaws.android.auth`). From a cold "still signed in" state `pool` was null → the SDK token cache survived a "sign out". |
| 5 | **`signIn()` throws if the ID token's `sub` is empty** instead of silently `setCognitoUserId("")` (which left the app permanently "not signed in" after a "successful" sign-in). |

### Gaps

| # | Fix |
|---|---|
| 6 | = #1. |
| 7 | **"Check now" wraps `performCheck` in `try/finally`** so `isSignedIn` / `sessionExpired` / `signedInEmail` / `reloadKey` are re-synced even when it throws — the Settings "expired" card and the hidden button now appear without a manual navigate-away-and-back. |
| 8 | **Sign-out no longer deletes the downloaded model** (deviation from REQ-016). It's the same model for every user, and deleting it on every sign-out made a broken-auth device unusable (no model + can't re-download). The model is removed instead when a *different* account signs in (tracked via `DownloadedModelPrefs` owner-sub) or the backend is reconfigured (`signOutAndWipeModel`). |
| 9 | **Auth state re-syncs every 30 s** via a `LaunchedEffect` poll in `LivePlateDetectionScreen`, in addition to `ON_START` — so a session a worker expires while the app stays foregrounded on one screen (camera, Contribute) is picked up without backgrounding. Three cheap `SharedPreferences` reads. |
| 13 | = #1 (workers now cheaply no-op when expired; the periodic schedule "keeps firing" is harmless). `handleSignedIn` also re-runs `AutoUploadWorker.schedule` so a prior cancel is undone. |

### Fragile

| # | Fix |
|---|---|
| 10 | **Process-wide `Mutex` around `getAwsCredentials()`** (companion-object scoped, shared across every `CognitoAuthManager` instance). `ModelCheckWorker` runs as three uncoordinated paths (periodic / startup / inline "Check now") plus `UploadDatasetWorker`; they were racing on `CognitoCachingCredentialsProvider`'s shared cache and producing spurious 403s (seen in logcat). |
| 11 | **MFA / `NEW_PASSWORD_REQUIRED` / auth-challenge handled.** New `AuthChallengeException` with a user-facing message (surfaced by `friendlyAuthError`); during a *token refresh* the same challenges map to `SessionExpiredException` (route to sign-in) instead of `UnsupportedOperationException`. |
| 12 | **429 / 5xx from `get-model-url` now retry.** New `RetryableHttpException` thrown by `sigV4Get`; `performCheck` rethrows it so `doWork` → `Result.retry()` (with backoff) instead of swallowing it and waiting a full hour. |
| 14 | **`writeUploadStatus` writes the sidecar atomically** (temp file + rename) — a worker killed mid-write no longer leaves a half-written JSON that reads back as `NOT_QUEUED` and makes the failed upload vanish from Upload history. `listExports` sweeps orphan `.tmp` files. |

### Also

- `ModelUpdateLog` is now capped at 100 entries (was an unbounded in-memory list).
- **Settings hides "Contribute data" when signed out.** REQ-026 gates capture on sign-in, so the
  row is useless without an account. Signed out (incl. session-expired), Settings shows a plain
  **"Sign in"** row → `AuthScreen` instead — sign-in stays reachable from Settings. Signed in, the
  full "Contribute data" toggle + navigation returns.

---

## Acceptance criteria

- [x] `CuratorRole` grants `execute-api:Invoke` on both API routes; `sam validate --lint` +
      `sam build` pass. (Deploy is operator-run.)
- [x] `AutoUploadWorker.doWork` and `runCatchUpIfNeeded` return without packaging when the
      session is signed-out/expired.
- [x] A dead refresh token delivered via `onFailure` (not just `getAuthenticationDetails`), a
      disabled/deleted account, and a missing Identity Pool all produce `SessionExpiredException`
      → `markSessionExpired()` → `Result.failure()`, not an infinite retry.
- [x] `AppConfig.seedPrefsIfNeeded` overwrites stale identity config and returns `true`; the
      caller wipes the session; workers call it too.
- [x] `signOut()` clears the SDK's `CognitoIdentityProviderCache` / `com.amazonaws.android.auth`
      even when no auth op ran this process; it does **not** delete the downloaded model.
- [x] A different account signing in deletes the previous owner's downloaded model.
- [x] `signIn()` with an unreadable ID token surfaces an error instead of a silent "not signed in".
- [x] "Check now" refreshes the Settings auth state even when the check throws.
- [x] `getAwsCredentials()` is serialized process-wide.
- [x] MFA / `NEW_PASSWORD_REQUIRED` show a clear message; the same during refresh routes to sign-in.
- [x] 429/5xx from `get-model-url` → `Result.retry()`.
- [x] Sidecar writes are atomic; orphan `.tmp` files are swept.
- [x] Built + unit-tested: `:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:testDebugUnitTest`
      pass (JBR via Android Studio).

> **Verification gap:** built + unit-tested only. No on-device run of the reconfigure /
> different-user / MFA paths (hard to stage). The curator-role 403 fix is verified by analysis +
> the deployed-infra diff; confirm on device after `sam deploy`.

---

## REQ-016 deviation

REQ-016 AC: "Sign-out deletes `filesDir/models/downloaded/` and clears all downloaded model
prefs." REQ-029 changes this to: sign-out keeps the model; it is deleted on a *different-user*
sign-in or a backend reconfigure. Rationale: the model is not per-user content, and unconditional
deletion turned any auth outage into a bricked app. REQ-016's doc has a pointer to here.
