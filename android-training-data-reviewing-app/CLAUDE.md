# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in `android-training-data-reviewing-app/`.

## Project overview

Standalone, **internal-only** Android app (never published to a store) for a single trusted
**curator** to turn raw uploaded YOLO packages in S3 `uploads/` into a verified, training-ready
dataset in S3 `done/`. It is a separate Gradle project from `android-end-user-app/` with **no build
dependency** on it — shared concepts (Cognito auth, YOLO label parsing, the box editor) are
reimplemented here, not shared.

Requirements: **REQ-022** (foundation), **REQ-023** (package workflow), **REQ-024** (precise box
geometry editing + pinch-zoom/pan) — all done, all in `../requirements/`. **REQ-025** (vehicle-type
categories + typed boxes) shipped but was rolled back — see below, "Vehicle classification removed".
See **REQ-041** for this app's purpose/scope/deployment-status summary (one of three separate
apps in this repo — never published to Google Play, and why).

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

Run from `android-training-data-reviewing-app/`. A JDK 17+ is required; if none is on `PATH`, use Android Studio's:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # unsigned release APK (signed if ANDROID_KEYSTORE_* env set)
./gradlew :app:testDebugUnitTest      # unit tests
```

## Configuration

`local.properties` (gitignored; copy `local.properties.example` and fill in real values, same
convention as `android-end-user-app/local.properties`) supplies `buildConfigField`s via a `localProp()` helper
in `app/build.gradle.kts`:

| Key | Notes |
|---|---|
| `COGNITO_USER_POOL_ID` | Same shared `PlateDetectorUsers` pool as `android-training-data-collection-app` |
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
| `ui/theme/` | `CurationTheme` (copied from `android-end-user-app/`) |

**Workflow (REQ-023)**

| File | Responsibility |
|---|---|
| `PackageId` / `UploadRef` | `packageIdOf(sub, device, filename)`; parse `uploads/<sub>/<device>/<file>.zip` |
| `YoloLabel` | YOLO `.txt` parse/format (comma-decimal + blank-line tolerant); `format(boxes, classOverrides)` for the curator's chosen classes |
| `VehicleCategories` | Category-list infrastructure kept from REQ-025, now a single `license_plate` entry — `fromJson`/`fromJsonObject` + `bundledDefault(context)` (`assets/vehicle-categories.json`). `id` = YOLO class id, pinned. `toJsonObject()` embeds the full list, not just its version — what a package snapshots at Start |
| `CurationManifest` / `DoneManifest` | JSON models for `curation/<id>/manifest.json` and the `done/`/`rejected/` completion sidecar/archives (REQ-035, see below); `withDecision`, `withItemBoxes` (working geometry + `box_classes`), `reviewers()`, `classCounts()`, count helpers. `CurationManifest.categories` is the package's own category-list snapshot (`category_list` JSON key). `DoneManifest.doneZipKey`/`.rejectedZipKey` point at the sibling archives |
| `PackageCache` | `filesDir/packages/<id>/` — zip-slip-guarded unzip, item listing, label read/write, delete |
| `CurationRepository` | All S3 (paginated `ListObjectsV2`, get/put/delete): `listNotProcessed` / `listInProgress` (→ `InProgressItem` with last-activity ms) / `listDone`, `startPackage`, `putManifest`, `ensureLocalCopy`, `completePackage`, `releasePackage`. Also `checkAccess()` (REQ-022). |
| `CurationViewModel` | Tab states, blocking `busy` progress, review `session`, `decide()` (stamps `decided_by`/`decided_at`, rewrites manifest, auto-advances), `complete`/`release`, ~3 min manifest heartbeat |
| `CurationHomeScreen` | Bottom-nav tabs + busy dialog + error snackbar; hosts `ReviewScreen` full-screen when a session is open |
| `PackageTabs` | `NotProcessedTab` / `InProgressTab` (stale rows → Discard / Take over) / `DoneTab` |
| `CurationRepository` | `fetchCategories()` (S3 `config/vehicle-categories.json` → bundled fallback, used only to embed a snapshot at `startPackage` / as a legacy-manifest fallback); `completePackage` builds and uploads compressed archives (REQ-035, see below), regenerating `data.yaml`'s header from its `categories` param inside the accepted-items zip and `check()`ing its version matches the manifest's |
| `BoxGeometry` (REQ-024) | Pure, Compose-free geometry in normalized `[0,1]` box space, unit-tested: `hitTest` (selected box's 8 handles first, else topmost box body), `applyHandle` (move/resize one box, clamped to image bounds + a min size) |
| `CurationViewModel` (REQ-024) | `currentBoxes()` (boxes, all implicitly `LICENSE_PLATE_CLASS_ID`) / `addBox` / `deleteBox` / `moveBox` (all debounced manifest saves via `withItemBoxes`), `canAcceptCurrent()`, `accept()`/`reject()` |
| `ReviewScreen` | Image + box overlay (cyan, pink when selected — no more amber/unclassified state), top-bar **add-box** (drag a rectangle), **drag to move / drag a handle to resize** the selected box, **pinch-zoom/pan** + double-tap reset, Prev/Reject/Accept/Next when pending, Prev/**Change decision**/Next when already decided (REQ-034 — no reason prompt on reject, no confirmation on change-decision), jump-to-item sheet. Accept blocked only while there are zero boxes. Four always-attached `pointerInput` blocks on one `Canvas` (add-box / double-tap / move-resize / pinch-zoom), each a no-op outside its mode — mirrors `android-end-user-app/.../FrameDetailScreen.kt`'s chaining. |
| `Format` | `nowIso()` (top-level), date/size/subset formatting |
| `CurationRepository` / `CurationViewModel` (REQ-036) | `loadDoneImages()`/`cleanupDoneViewer()` and `openDoneViewer()`/`closeDoneViewer()` — download+unzip (or old-scheme fallback) a Done package's accepted images into a temp dir for viewing, deleted on close |
| `DoneViewerScreen` (REQ-036) | Read-only: `LazyVerticalGrid` of downsampled thumbnails grouped by subset; tapping one opens a full-size dialog with boxes overlaid (reuses `ReviewScreen.fitRect()`). No editing, no Accept/Reject — nothing here changes a decision |

**Auth → credentials flow:** sign-in caches sub + email → `getIdToken()` (SDK auto-refresh,
throws `SessionExpiredException` when the refresh token is dead) → `newCredentialsProvider()` sets
the Identity Pool `logins` map → because the ID token carries the `curators` group role claim, the
pool's **token-based role mapping** returns `CuratorRole` credentials.

**Workflow state is entirely S3-file-derived** — no status field / DB. Not Processed = an
`uploads/**/*.zip` with no `curation/<id>/manifest.json` and no done manifest sidecar anywhere
under `done/`; In Progress = the curation manifest exists; Done = a done manifest sidecar exists.
Transitions are PUT/DELETE of those files. "A done manifest sidecar exists" checks both naming
schemes — see "Completion output (REQ-035)" below.

**Vehicle classification removed.** REQ-025 originally required curators to pick a vehicle type
(civil / police / fire / medical / other / license_plate) per box before Accept. That's gone —
every box is now automatically class `0` (`license_plate`, `CurationViewModel.LICENSE_PLATE_CLASS_ID`),
never curator-chosen, and there's no dropdown in `ReviewScreen` anymore. This was a scope decision
to focus purely on plate detection, not vehicle-type classification, "for now" — the underlying
category-list infrastructure below was kept rather than deleted, specifically so this is easy to
re-expand later if needed.

**Category list infrastructure (kept, now single-class).** `config/vehicle-categories.json` in the
bucket (fetched on workflow entry, bundled fallback in assets) still drives `data.yaml`'s `nc`/
`names` at Complete — it's just a one-entry list now (`{"version": 2, "classes": [{"id": 0,
"key": "license_plate", "label": "License plate"}]}`). Operator seeds the file with `aws s3 cp`
(see `aws-training-infra/aws/README.md`); the bundled asset must match whatever the operator has last pushed to
S3 if you want a fresh package's local fallback to agree with what's already live there.

**Package category snapshot.** A package embeds the *full* category list (not just its version) in
its own manifest at Start (`CurationManifest.categories`, JSON key `category_list`) — Complete
always regenerates `data.yaml` from that snapshot (`CurationRepository.completePackage`), never a
freshly-fetched one. This is what makes Complete safe fully offline and immune to the canonical
list moving on mid-package, and it's also what keeps a package **started before this change**
consistent — it still snapshots and completes against the old 6-class list, so an in-progress
package isn't disrupted by the version bump to a single class. `completePackage` additionally
`check()`s the version matches as a backstop. A manifest from before the snapshot existed has no
snapshot and falls back to a fresh fetch/bundled list, same risk as before.

**Completion output (REQ-035).** `completePackage()` uploads compressed archives, not one S3
object per image/label — a 100-item package's output is a small, constant number of `PUT`s instead
of 200+. Layout, mirroring `uploads/<sub>/<device>/<filename>.zip`'s nested path shape rather than
the flat `packageId` used for `curation/` and the local cache:

```
done/<sub>/<device>/<filename-stem>-curated-<YYYYMMDD>_<HHmmss>.zip            ← accepted items: <subset>/images/, <subset>/labels/, data.yaml
done/<sub>/<device>/<filename-stem>-curated-<YYYYMMDD>_<HHmmss>._manifest.json  ← sidecar, NOT zipped in
rejected/<sub>/<device>/<filename-stem>-rejected-<YYYYMMDD>_<HHmmss>.zip        ← rejected items: <subset>/images/, <subset>/labels/ — no manifest, nothing lists rejected/
```

The manifest stays an un-zipped sidecar, deliberately: `listDone()` populates the Done tab by
listing every `_manifest.json` under `done/` and reading each one directly (small, individually
fetchable). If it moved inside the zip, showing the Done tab would mean downloading and unzipping
every completed package. `DoneManifest.doneZipKey` / `.rejectedZipKey` record the sibling
archive's S3 key (null if that side had nothing — e.g. a fully-rejected package has no
`doneZipKey`) so later tooling doesn't have to guess it from the manifest's own key. Neither zip's
existence is retroactive — a package completed **before** this shipped keeps living under the old
flat `done/<packageId>/<subset>/{images,labels}/` layout with loose files and no per-item archive;
`listNotProcessed()`/`listDone()` recognize both the old and new manifest-sidecar shapes
(`PackageId.packageIdFromDoneManifestKey` for the new one, a string suffix-strip for the old one)
so neither an old nor a new completed package is mistaken for un-processed.

`ReviewScreen`'s top app bar has an always-visible "Complete" action (a checkmark icon), enabled
only once `session.manifest.pending == 0` — the same trigger `PackageTabs.kt`'s list-row Complete
button already used, just reachable without leaving the review screen. `CurationViewModel.complete()`
drops the open session directly on success when it's the one just completed, rather than relying
on the caller to separately call `closeSession()` — otherwise a stale heartbeat/flush could
resurrect the `curation/<id>/manifest.json` that `completePackage()` just deleted.

**Done package viewer (REQ-036).** Tapping a `DoneTab` card opens `DoneViewerScreen` — read-only,
no Accept/Reject/box-editing anywhere in it, since a Done package has nothing left to decide and
its working cache is already gone (deleted on Complete). `CurationViewModel.openDoneViewer()` calls
`CurationRepository.loadDoneImages()`, which downloads and unzips `DoneManifest.doneZipKey` into a
fresh temp dir (`filesDir`'s cache, not `filesDir/packages/` — this isn't a working copy); for a
pre-REQ-035 package with no `doneZipKey`, it falls back to listing and downloading the loose
objects under the old flat `done/<packageId>/` shape instead. Images are shown in a
`LazyVerticalGrid` grouped by subset, each thumbnail decoded downsampled
(`BitmapFactory.Options.inSampleSize`, targeting ~200px) so a package with hundreds of images
doesn't decode everything at full resolution just to render a grid; tapping one opens it full-size
in a dialog with its final YOLO boxes overlaid (reusing `ReviewScreen`'s `fitRect()` letterbox math
and the same box-drawing approach, just non-interactive). `CurationViewModel.closeDoneViewer()`
deletes the temp download — nothing about viewing a Done package leaves a lasting local copy.

## Infra

`../aws-training-infra/aws/template.yaml` — `CuratorRole`, `CuratorGroup`, and a `Type: Token` entry in
`DeviceIdentityPoolRoleAttachment.RoleMappings`. Deploy with `cd ../aws-training-infra/aws && sam build &&
sam deploy`. `DeviceAuthRole` (`android-training-data-collection-app`) is untouched.

## Not yet built

Nothing outstanding in REQ-022/023/024/025. A full on-device run (start → review, incl. move/
resize/pinch-zoom → complete) against a real uploaded package hasn't been done yet.

**Changing an already-decided item is now supported (REQ-034)** — this was previously out of scope
for v1 ("Release the package to redo"). `CurationViewModel.undecide()` reverts the current item's
Accept/Reject back to `PENDING` (clearing `decidedBy`/`decidedAt`/`labelContent`/`reason`, but
leaving `workingLabel` — the curator's box edits — untouched, so re-opening for edit shows exactly
what was there before) without advancing the session index, so the curator stays on the item to
fix it. `ReviewScreen` shows a **"Change decision"** button instead of Accept/Reject on a decided
item.
