# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in `curation-android/`.

## Project overview

Standalone, **internal-only** Android app (never published to a store) for a single trusted
**curator** to turn raw uploaded YOLO packages in S3 `uploads/` into a verified, training-ready
dataset in S3 `done/`. It is a separate Gradle project from `android/` with **no build
dependency** on it — shared concepts (Cognito auth, YOLO label parsing, the box editor) are
reimplemented here, not shared.

Requirements: **REQ-022** (foundation — done), **REQ-023** (package workflow — done),
**REQ-024** (box editor — draft), all in `../requirements/`.

Key design decisions (see REQ-022 / REQ-023):
- **Direct S3 access from the device** via a scoped IAM role (`CuratorRole`), not a Lambda-mediated
  API — justified by the single-trusted-curator, unpublished-APK threat model.
- **Single curator, one session at a time** — no claim/lock markers. REQ-023 adds two lightweight,
  client-only partial measures: **per-item attribution** (`decided_by` / `decided_at`) and
  **opportunistic stale cleanup** (a package idle > 2 h is offered for Discard / Take over when a
  curator next opens the app). Neither is a concurrency guarantee (full model deferred — REQ-022
  "Future: multi-curator").
- **Working copy lives on-device** — the unzipped package is a local cache under
  `filesDir/packages/<id>/`; only `curation/<id>/manifest.json` is synced to S3. This is what makes
  review progress survive app kill / reboot / reinstall. Its S3 `LastModified` is also the
  "last activity" clock for stale detection (kept fresh by a ~3 min heartbeat while reviewing).

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
machine). No `navigation-compose` — hand-rolled route/session state.

**Auth & config**

| File | Responsibility |
|---|---|
| `CurationConfig` | `BuildConfig` accessors; `region` derived from the identity-pool-id prefix; `cognitoLoginKey` |
| `CuratorAuthManager` | Cognito SRP **sign-in only** (no sign-up/confirm — manual provisioning), token refresh, `groups()` / `isCurator()` (decodes `cognito:groups`), `newCredentialsProvider()` (STS via the Identity Pool; clears stale cached creds), `signOut()` |
| `CuratorPrefs` | SharedPreferences: cached curator email/sub, session-expired flag |
| `CurationApp` | Routes: `AuthScreen` → (`AccessDeniedScreen` if not a curator \| `CurationHomeScreen`). Makes **no S3 call** for a non-curator. Owns the `CurationViewModel`. |
| `AuthScreen` / `AccessDeniedScreen` | Sign-in; not-authorized + sign-out |
| `ui/theme/` | `CurationTheme` (copied from `android/`) |

**Workflow (REQ-023)**

| File | Responsibility |
|---|---|
| `PackageId` / `UploadRef` | `packageIdOf(sub, device, filename)`; parse `uploads/<sub>/<device>/<file>.zip` |
| `YoloLabel` | YOLO `.txt` parse/format (comma-decimal + blank-line tolerant) |
| `CurationManifest` / `DoneManifest` | JSON models for `curation/<id>/manifest.json` and `done/<id>/_manifest.json`; `withDecision`, `reviewers()`, count helpers |
| `PackageCache` | `filesDir/packages/<id>/` — zip-slip-guarded unzip, item listing, label read/write, delete |
| `CurationRepository` | All S3 (paginated `ListObjectsV2`, get/put/delete): `listNotProcessed` / `listInProgress` (→ `InProgressItem` with last-activity ms) / `listDone`, `startPackage`, `putManifest`, `ensureLocalCopy`, `completePackage`, `releasePackage`. Also `checkAccess()` (REQ-022). |
| `CurationViewModel` | Tab states, blocking `busy` progress, review `session`, `decide()` (stamps `decided_by`/`decided_at`, rewrites manifest, auto-advances), `complete`/`release`, ~3 min manifest heartbeat |
| `CurationHomeScreen` | Bottom-nav tabs + busy dialog + error snackbar; hosts `ReviewScreen` full-screen when a session is open |
| `PackageTabs` | `NotProcessedTab` / `InProgressTab` (stale rows → Discard / Take over) / `DoneTab` |
| `ReviewScreen` | Image + **read-only** YOLO overlay, Prev/Reject(+reason)/Accept/Next, jump-to-item sheet, zero-box ⇒ Reject-only. REQ-024 attaches box editing here. |
| `Format` | `nowIso()`, date/size/subset formatting |

**Auth → credentials flow:** sign-in caches sub + email → `getIdToken()` (SDK auto-refresh,
throws `SessionExpiredException` when the refresh token is dead) → `newCredentialsProvider()` sets
the Identity Pool `logins` map → because the ID token carries the `curators` group role claim, the
pool's **token-based role mapping** returns `CuratorRole` credentials.

**Workflow state is entirely S3-file-derived** — no status field / DB. Not Processed = an
`uploads/**/*.zip` with no `curation/<id>/manifest.json` and no `done/<id>/_manifest.json`;
In Progress = the curation manifest exists; Done = the done manifest exists. Transitions are
PUT/DELETE of those files.

## Infra

`../infra/aws/template.yaml` — `CuratorRole`, `CuratorGroup`, and a `Type: Token` entry in
`DeviceIdentityPoolRoleAttachment.RoleMappings`. Deploy with `cd ../infra/aws && sam build &&
sam deploy`. `DeviceAuthRole` (the main app) is untouched.

## Not yet built (REQ-024)

Box editing on `ReviewScreen` — drag / resize / add / delete boxes, pinch-zoom/pan — and saving
the edited YOLO content as `label_content` on Accept. Changing an already-decided item stays
out of scope (v1: Release the package to redo). `ReviewScreen`'s `Canvas` is the attach point.
