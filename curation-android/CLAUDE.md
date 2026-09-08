# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in `curation-android/`.

## Project overview

Standalone, **internal-only** Android app (never published to a store) for a single trusted
**curator** to turn raw uploaded YOLO packages in S3 `uploads/` into a verified, training-ready
dataset in S3 `done/`. It is a separate Gradle project from `android/` with **no build
dependency** on it — shared concepts (Cognito auth, YOLO label parsing, the box editor) are
reimplemented here, not shared.

Requirements: **REQ-022** (foundation), **REQ-023** (package workflow), **REQ-025** (vehicle-type
categories + typed boxes), **REQ-024** (precise box geometry editing + pinch-zoom/pan) — all done,
all in `../requirements/`.

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
| `YoloLabel` | YOLO `.txt` parse/format (comma-decimal + blank-line tolerant); `format(boxes, classOverrides)` for the curator's chosen classes |
| `VehicleCategories` | REQ-025 class list — `fromJson`/`fromJsonObject` + `bundledDefault(context)` (`assets/vehicle-categories.json`). `id` = YOLO class id, pinned. `toJsonObject()` embeds the full list, not just its version — what a package snapshots at Start |
| `CurationManifest` / `DoneManifest` | JSON models for `curation/<id>/manifest.json` and `done/<id>/_manifest.json`; `withDecision`, `withItemBoxes` (working geometry + `box_classes`), `reviewers()`, `classCounts()`, count helpers. `CurationManifest.categories` is the package's own category-list snapshot (`category_list` JSON key) |
| `PackageCache` | `filesDir/packages/<id>/` — zip-slip-guarded unzip, item listing, label read/write, delete |
| `CurationRepository` | All S3 (paginated `ListObjectsV2`, get/put/delete): `listNotProcessed` / `listInProgress` (→ `InProgressItem` with last-activity ms) / `listDone`, `startPackage`, `putManifest`, `ensureLocalCopy`, `completePackage`, `releasePackage`. Also `checkAccess()` (REQ-022). |
| `CurationViewModel` | Tab states, blocking `busy` progress, review `session`, `decide()` (stamps `decided_by`/`decided_at`, rewrites manifest, auto-advances), `complete`/`release`, ~3 min manifest heartbeat |
| `CurationHomeScreen` | Bottom-nav tabs + busy dialog + error snackbar; hosts `ReviewScreen` full-screen when a session is open |
| `PackageTabs` | `NotProcessedTab` / `InProgressTab` (stale rows → Discard / Take over) / `DoneTab` |
| `CurationRepository` (REQ-025) | `fetchCategories()` (S3 `config/vehicle-categories.json` → bundled fallback, used only to embed a snapshot at `startPackage` / as a legacy-manifest fallback); `completePackage` regenerates `data.yaml` header from its `categories` param and `check()`s its version matches the manifest's |
| `BoxGeometry` (REQ-024) | Pure, Compose-free geometry in normalized `[0,1]` box space, unit-tested: `hitTest` (selected box's 8 handles first, else topmost box body), `applyHandle` (move/resize one box, clamped to image bounds + a min size) |
| `CurationViewModel` (REQ-025/024) | `currentBoxes()` (boxes + chosen classes), `setBoxClass` / `addBox` / `deleteBox` / `moveBox` (all debounced manifest saves via `withItemBoxes`), `canAcceptCurrent()`, `accept()`/`reject()`, `sessionCategories` (the open session's own snapshot, else the app-level fallback) |
| `ReviewScreen` | Image + YOLO overlay (amber=unclassified, cyan=classified, pink=selected), a per-box **class dropdown** list, top-bar **add-box** (drag a rectangle), **drag to move / drag a handle to resize** the selected box, **pinch-zoom/pan** + double-tap reset, Prev/Reject(+reason)/Accept/Next, jump-to-item sheet. Accept blocked until every box is classified. Four always-attached `pointerInput` blocks on one `Canvas` (add-box / double-tap / move-resize / pinch-zoom), each a no-op outside its mode — mirrors `android/.../FrameDetailScreen.kt`'s chaining. |
| `Format` | `nowIso()` (top-level), date/size/subset formatting |

**Auth → credentials flow:** sign-in caches sub + email → `getIdToken()` (SDK auto-refresh,
throws `SessionExpiredException` when the refresh token is dead) → `newCredentialsProvider()` sets
the Identity Pool `logins` map → because the ID token carries the `curators` group role claim, the
pool's **token-based role mapping** returns `CuratorRole` credentials.

**Workflow state is entirely S3-file-derived** — no status field / DB. Not Processed = an
`uploads/**/*.zip` with no `curation/<id>/manifest.json` and no `done/<id>/_manifest.json`;
In Progress = the curation manifest exists; Done = the done manifest exists. Transitions are
PUT/DELETE of those files.

**Vehicle classes (REQ-025).** The YOLO class-id column carries the vehicle type. Scheme comes
from `config/vehicle-categories.json` in the bucket (`0` license_plate, `1` civil, `2` police,
`3` fire, `4` medical, `5` other), fetched on workflow entry, bundled fallback in assets. Every
box must be classified before Accept; `done/` label files carry the ids and `data.yaml` is
regenerated with `nc`/`names` from the list. Operator seeds the file with `aws s3 cp`.

**Package category snapshot.** A package embeds the *full* category list (not just its version) in
its own manifest at Start (`CurationManifest.categories`, JSON key `category_list`) — review and
Complete always use that snapshot (`CurationViewModel.sessionCategories` / `manifest.categories`),
never a freshly-fetched one. This is what makes Complete safe fully offline and immune to the
canonical list moving on mid-package (e.g. `config/vehicle-categories.json` bumped to a version
with a new class while this package is still reviewing an older version) — without it, a stale or
mismatched fetch at Complete time could regenerate `data.yaml`'s `nc`/`names` from a different list
than the one the labels were actually classified against. `CurationRepository.completePackage`
additionally `check()`s the version matches as a backstop. A manifest from before this existed has
no snapshot and falls back to a fresh fetch/bundled list, same risk as before.

## Infra

`../infra/aws/template.yaml` — `CuratorRole`, `CuratorGroup`, and a `Type: Token` entry in
`DeviceIdentityPoolRoleAttachment.RoleMappings`. Deploy with `cd ../infra/aws && sam build &&
sam deploy`. `DeviceAuthRole` (the main app) is untouched.

## Not yet built

Nothing outstanding in REQ-022/023/024/025. Changing an already-decided item's boxes stays out of
scope for v1 (Release the package to redo); a full on-device run (start → review, incl. move/
resize/pinch-zoom → complete) against a real uploaded package hasn't been done yet.
