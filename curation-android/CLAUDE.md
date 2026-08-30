# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in `curation-android/`.

## Project overview

Standalone, **internal-only** Android app (never published to a store) for a single trusted
**curator** to turn raw uploaded YOLO packages in S3 `uploads/` into a verified, training-ready
dataset in S3 `done/`. It is a separate Gradle project from `android/` with **no build
dependency** on it — shared concepts (Cognito auth, YOLO label parsing, the box editor) are
reimplemented here, not shared.

Requirements: **REQ-022** (this app's foundation — done), **REQ-023** (package workflow — draft),
**REQ-024** (review editor — draft), all in `../requirements/`.

Key design decisions (see REQ-022):
- **Direct S3 access from the device** via a scoped IAM role (`CuratorRole`), not a Lambda-mediated
  API — justified by the single-trusted-curator, unpublished-APK threat model.
- **Single curator, one session at a time** — no claim/lock markers (deferred; see REQ-022
  "Future: multi-curator").
- **Working copy lives on-device** — the unzipped package is a local cache; only
  `curation/<package_id>/manifest.json` is synced to S3. This is what makes review progress
  survive app kill / reboot / reinstall.

## Build commands

Run from `curation-android/`. A JDK 17+ is required; if none is on `PATH`, use Android Studio's:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # unsigned release APK (signed if ANDROID_KEYSTORE_* env set)
./gradlew :app:testDebugUnitTest      # unit tests
```

## Configuration

`local.properties` (committed, same convention as `android/local.properties`) supplies
`buildConfigField`s via a `localProp()` helper in `app/build.gradle.kts`:

| Key | Notes |
|---|---|
| `COGNITO_USER_POOL_ID` | Same shared `PlateDetectorUsers` pool as the main app |
| `COGNITO_APP_CLIENT_ID` | Same app client |
| `COGNITO_IDENTITY_POOL_ID` | Same identity pool; **AWS region is derived from its `<region>:` prefix** |
| `DATASET_BUCKET_NAME` | `plate-dataset-uploads` (stack output `DatasetBucketName`) |

The curator's Cognito account must be in the **`curators`** group
(`aws cognito-idp admin-add-user-to-group ... --group-name curators`); the group's IAM role
assignment must exist *before* the token used for S3 is minted, so a fresh sign-in is required
after being added.

## Architecture

Single-Activity Compose. `MainActivity` → `CurationApp()` (a `Surface`-wrapped route state
machine).

| File | Responsibility |
|---|---|
| `CurationConfig` | `BuildConfig` accessors; `region` derived from the identity-pool-id prefix; `cognitoLoginKey` |
| `CuratorAuthManager` | Cognito SRP **sign-in only** (no sign-up/confirm — manual provisioning), token refresh, `groups()` / `isCurator()` (decodes `cognito:groups`), `newCredentialsProvider()` (STS via the Identity Pool; clears stale cached creds), `signOut()` |
| `CuratorPrefs` | SharedPreferences: cached curator email/sub, session-expired flag |
| `S3Access` | `AmazonS3Client` from `CuratorRole` STS creds; `checkAccess()` = one `ListObjectsV2`. **Seed for REQ-023's package-listing repository.** |
| `CurationApp` | Routes: `AuthScreen` → (`AccessDeniedScreen` if not a curator \| `HomeScreen`). Makes **no S3 call** for a non-curator. |
| `AuthScreen` / `AccessDeniedScreen` / `HomeScreen` | Sign-in; not-authorized + sign-out; placeholder home with the S3 access-check card + the three future REQ-023 screens |
| `ui/theme/` | `CurationTheme` (copied from `android/`) |

**Auth → credentials flow:** sign-in caches sub + email → `getIdToken()` (SDK auto-refresh,
throws `SessionExpiredException` when the refresh token is dead) → `newCredentialsProvider()` sets
the Identity Pool `logins` map → because the ID token carries the `curators` group role claim, the
pool's **token-based role mapping** returns `CuratorRole` credentials.

## Infra

`../infra/aws/template.yaml` — `CuratorRole`, `CuratorGroup`, and a `Type: Token` entry in
`DeviceIdentityPoolRoleAttachment.RoleMappings`. Deploy with `cd ../infra/aws && sam build &&
sam deploy`. `DeviceAuthRole` (the main app) is untouched.

## Not yet built (REQ-023 / REQ-024)

Not Processed / In Progress / Done screens, package start/complete/release, the per-image box
editor, any `curation/` or `done/` writes. `S3Access` is the extension point.
