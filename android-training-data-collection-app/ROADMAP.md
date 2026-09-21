# Roadmap

> This app (`android-training-data-collection-app/`) is the unpublished data-collection counterpart split out of
> `android-end-user-app/` per [REQ-031](../requirements/REQ-031-done-split-detection-and-training-apps.md).
> This history is inherited from `android-end-user-app/` as of the split — everything below still applies here;
> `android-end-user-app/`'s own roadmap now only covers the detection-only app.

## Done

### Backend configuration (requirements: REQ-032)
- [x] **In-app backend configuration screen** — `BackendConfigScreen` edits `COGNITO_USER_POOL_ID` / `COGNITO_APP_CLIENT_ID` / `COGNITO_IDENTITY_POOL_ID` / `UPLOAD_SERVICE_URL` on-device instead of requiring a rebuild; each field "pins" independently once manually saved, permanently opting out of build-time reconciliation; reachable from Settings, from `NoModelsScreen`'s always-visible "Configure backend…" link, and auto-routed to from a sign-in attempt while unconfigured; validates the Identity Pool ID's `<region>:<uuid>` shape so a placeholder/malformed value is never silently treated as configured
- [x] **Model-discovery validation** — `PlateDetector.isValidModel()` filters candidate models by their actual tensor shape before they're selectable, and the asset scan is scoped to `assets/models/` only (an earlier whole-assets-root fallback could surface an unrelated `.tflite` bundled by a dependency like ML Kit and crash on construction instead of showing "No detection model found")

### Camera
- [x] Live camera preview (CameraX, back camera)
- [x] Processing guard: pauses when app goes to background
- [x] Pinch-to-zoom (`detectTransformGestures` → `setZoomRatio`)
- [x] Tap-to-focus (`detectTapGestures` → `FocusMeteringAction`) with animated focus ring
- [x] Zoom level indicator in top bar
- [x] Torch toggle (flashlight button in top bar; auto-off on background; hidden when no flash unit)
- [x] Zoom shortcut buttons — 1×/2×/3× pill buttons at bottom center; filtered to camera's max zoom; highlights active level
- [x] Exposure compensation — EV slider above zoom buttons; reads `ExposureState` range; shows computed EV value; hidden when unsupported
- [x] Analysis resolution control — `LOW (640×480)`/`HD (1280×720)` picker in Settings (no "Default"; REQ-042); uses `ResolutionSelector` with pinned aspect ratio; camera rebinds on change; export `data.yaml` records `frame_sizes`
- [x] Scan interval control — RadioButton list in Settings; options: 5 s / 2 s / 1 s / ½ s / No delay (default 1 s); throttle applied per-frame via `rememberUpdatedState`; takes effect immediately without camera rebind

### Detection
- [x] TFLite YOLO inference on each camera frame (~8 fps)
- [x] Letterbox preprocessing (aspect-ratio-preserving resize with black padding)
- [x] Bounding box overlay (fit-center coordinate mapping)
- [x] Confidence threshold slider (per model, persisted in prefs)
- [x] Beep on detection

### OCR
- [x] ML Kit text recognizer on cropped plate region
- [x] OCR result shown in bounding box label
- [x] OCR always enabled — runs automatically on every detection

### Model management
- [x] Bundled default models (downloaded at build time from GitHub Releases via `model_v*` tag; graceful fallback when no release found)
- [x] Multiple models selectable in Settings
- [x] Model description from `.txt` sidecar file
- [x] Delete downloaded model (auto-replaced when an update is applied; removed on a **different-user** sign-in or backend reconfigure — no longer on a plain sign-out, REQ-029)
- ~~Custom model import from device storage~~ — removed in REQ-016; server download is now the only runtime update path

### UI
- [x] Settings screen (model picker, confidence slider, scan interval picker, analysis resolution picker, model activity log with "Check now" + "Details"; "Contribute data" row when signed in, "Sign in" row when signed out; "session expired" card)
- [x] "No models" error screen — Retry + Sign in / Sign out (REQ-027)

### Training data collection
- [x] Opt-in "Contribute data" row in Settings (Switch + summary + tap-to-navigate to ContributeScreen); default off; persisted in SharedPreferences (`collect_training_data`)
- [x] First-time consent dialog on first enable — explains what is saved and that data is uploaded to a private server; acknowledgement persisted (`collect_first_time_shown`); subsequent toggles skip the dialog
- [x] `manifest.json` tracks `next_seq`, `total_frames`, `total_detections`, `multi_detection_frames`, and collection date range; updated after every saved frame; survives app restarts
- [x] Dataset upload — creates `plates_dataset_<timestamp>.zip` in `filesDir/exports/` with frames randomly shuffled and split into `train/`, `val/`, `test/` subdirectories (fixed 70/20/10 split); auto-resets collected data on success; enqueues WorkManager upload job; ZIP deleted from device after successful upload
- [x] Configurable storage quota — editable via ✏ icon on the "Storage used" line in ContributeScreen stats card (default 500 MB, min 100 MB, MB only); 80% yellow warning banner on camera + ContributeScreen; collection paused (red banner) at 100%
- ~~Dataset Editor / Frame Detail Editor~~ — **removed from the generic app in REQ-026**; all box review/editing moved to `android-training-data-reviewing-app`. REQ-012 superseded.
- [x] Frame file names include capture date, time, and 6-digit sequence (`<YYYYMMDD>_<HHmmss>_<NNNNNN>`); image and label always share the same base name (REQ-013)
- [x] Exported `data.yaml` includes a `device:` metadata block: phone model, manufacturer, Android version, SDK, app version, model ID, anonymised device ID (SHA-256 hash, first 16 hex chars), export timestamp, and collection date range; block is ignored by `yolo train` (REQ-013)
- [x] **Manual frame capture** — `CameraAlt` button in top bar; visible only when collecting **and signed in** (REQ-026); saves latest analyzed frame via `TrainingDataSaver.saveFrameManual()` with empty label file (missed-plate marker, `total_frames` +1); toast "Frame saved"; 1 s cooldown with 35% alpha dimming; quota-full guard shows toast without cooldown; empty-label frames distinguishable from auto-detected frames (always ≥ 1 annotation) (REQ-020)
- [x] **Burst frame collection** — `BurstMode` button in top bar (visible when collecting is on); setup dialog to configure count (default 100, min 1); captures every analyzed frame (YOLO labels when detections present, empty label file otherwise); regular auto-save suspended during burst to prevent double-saves; yellow icon tint + `"Burst: N / M"` progress line in subtitle while active; single-shot capture button disabled during burst; tapping the button while active cancels immediately; completion dialog offers "Send to server" (enqueues `UploadDatasetWorker` + resets counter + starts next round) or "Stop collecting"; falls back to "Go to upload screen" when not signed in / URL not configured (REQ-021)

### Model distribution and auto-update (requirements: REQ-016)
- [x] **S3 model storage** — models in bucket under `models/v<semver>/` with `metadata.json` (version, min/max app version, description) (REQ-016)
- [x] **GetModelUrl Lambda** — `GET /get-model-url?app_version=` returns compatible + latest pre-signed download URLs; SigV4 auth; HTTP 400/403/404 error cases (REQ-016)
- [x] **Build fix** — GitHub API release discovery for `model_v*` tags by semver; Bearer token auth for private repo; graceful `[WARN]` if no release found; `MODEL_DOWNLOAD_TOKEN` in `local.properties` for local dev (REQ-016)
- [x] **On-device auto-update** — startup one-shot + 1 h periodic `ModelCheckWorker`; confirmation dialog; downloaded model auto-selected after install; old model deleted on update; all downloaded models deleted on sign-out; bundled asset never deleted (REQ-016)
- [x] **Manual "Check now"** — button in Settings (signed-in only) triggers inline `ModelCheckWorker.performCheck()` with spinner; picks up pending update immediately on return (REQ-016)
- [x] **Model update activity log** — in-memory `ModelUpdateLog` singleton records check, result, and download events per session; Settings shows latest entry (colour-coded) + "Details" button for full timestamped list (REQ-016)
- [x] **Latest-model version banner** — Settings card when `latest.model_version > compatible.model_version` (newer model requires app update) (REQ-016)
- [x] **Mobile data toggle renamed** — "Use mobile data" (covers uploads and model downloads); pref key unchanged (REQ-016)
- [x] **Custom model import removed** — `.tflite` import from device storage removed; server download is the only runtime update path (REQ-016)

### Generic-app cleanup + auth robustness (requirements: REQ-026 – REQ-029)
- [x] **On-device review/editing removed** — Dataset Editor + Frame Detail deleted; capture path unchanged (auto/manual/burst still ship the model's predicted YOLO boxes); upload ZIP format unchanged so `android-training-data-reviewing-app` needs no change. Capture now requires **signed in AND `collect_training_data`** — nothing is written to disk otherwise. ContributeScreen simplified (no "View dataset"). (REQ-026)
- [x] **Auth-failure detection + recovery** — API HTTP 401/403 recognised (was swallowed); one forced-credential-refresh retry; `NoModelsScreen` gets Sign in / Sign out so a fresh install / broken auth isn't a dead end; auth state re-syncs on `ON_START`. (REQ-027)
- [x] **Upload diagnostics** — failure reason stored per-ZIP and shown on the history card; persistent `UploadLog` (`filesDir/upload_log.json`, capped 100) + "Upload activity" line & Details dialog on ContributeScreen. (REQ-028)
- [x] **Auth robustness** — `CuratorRole` granted `execute-api:Invoke` (a `curators` member's token resolves the *main* app to `CuratorRole`; needs `sam deploy`). App-side: terminal Cognito errors → `SessionExpiredException` (were infinite retry); a persistent 401/403 fails the job with a message and **keeps the session** (was an aggressive logout loop); `getAwsCredentials()` serialized process-wide; `AppConfig.seedPrefsIfNeeded` re-seeds on backend change; `signOut()` clears the SDK token caches; sign-out keeps the downloaded model; MFA/`NEW_PASSWORD_REQUIRED` handled; sidecar writes atomic; `ModelUpdateLog` capped. (REQ-029)
- [x] **Auto Backup disabled** — `android:allowBackup="false"` + `data_extraction_rules.xml` excludes `training_data/`, `exports/`, `models/`, `upload_log.json` and the Cognito token prefs from D2D transfer. Nothing this app stores leaves the device. (REQ-007 §5)

---

## Backlog

### Camera — easy
- [ ] **Front/back camera toggle** — change `CameraSelector`, rebind. ~1h

### Camera — medium
- [ ] **Faster YUV→Bitmap** — replace current YUV→NV21→JPEG→Bitmap (lossy) with direct YUV→RGB pixel copy. ~half day

### Camera — hard / device-specific
- [ ] **Physical lens switching** (ultrawide/telephoto) — enumerate physical cameras via `Camera2CameraInfo`, custom `CameraSelector` per lens. Multi-day, Samsung-specific.
- [ ] **Night/HDR mode** — CameraX Extensions (`camera-extensions` dep). Device-dependent.

### Detection
- [ ] **Plate-specific model** — current model is generic YOLO; training a license-plate-only model would improve accuracy significantly
- [ ] **NMS / deduplication** — current output may produce overlapping boxes for the same plate; add IoU-based suppression client-side if the exported model doesn't include it

### OCR
- [ ] **OCR confidence filtering** — discard low-confidence reads (ML Kit doesn't expose per-char confidence; heuristic: minimum text length, alphanumeric ratio)
- [ ] **Plate format validation** — filter OCR output by known regional formats (e.g. `[A-Z]{2}[0-9]{3}[A-Z]{2}`)
- [ ] **OCR history / log** — show a scrollable list of recently detected plate texts with timestamps

### Events / integrations
- [ ] **Detection event format** — emit structured events (timestamp, plate text, bounding box) for downstream consumers. Format TBD.
- [ ] **Notification on detection** — system notification when a plate is detected (background use case)

### Settings
- [ ] **Per-model class filter** — let user pin detection to a specific class ID (e.g. class 0 = plates only)

### Training data collection (requirements: REQ-038, REQ-040)
- [x] **Capture guidelines onboarding** — one-time, full-screen "Capture guidelines" shown before the camera is ever enabled (gates `LiveDetectionUi` ahead of the CAMERA permission prompt): use Manual Capture for what auto-capture misses, vary angle/distance, seek out hard cases, favor more vehicles over duplicate shots, stay public/respectful. Persisted via `ModelPrefs.capture_guidelines_shown` (REQ-038)

**Not planned: Google Play publishing.** This app is internal-only and there is no plan to ever
submit it to Google Play — see [REQ-040](../requirements/REQ-040-done-collection-app-scope-and-deployment.md)
for the full reasoning (account-deletion + ongoing Data Safety upkeep isn't compliance overhead
worth carrying for a tool only the project's own data collectors run). REQ-030's release-readiness
checklist applies to [`android-end-user-app/`](../android-end-user-app/ROADMAP.md) (the published,
detection-only app), not this one — see [REQ-039](../requirements/REQ-039-done-end-user-app-scope-and-deployment.md).

### Cloud dataset upload (requirements: REQ-014, REQ-015, REQ-018)
- [x] **Unified Contribute data flow** — single "Contribute data" row in Settings; ContributeScreen owns stats card (frames/detections/storage with ✏ quota edit), upload config, upload action, and "Upload history" section; no mode selection — cloud upload is the only path; ZIP deleted from device after successful upload (REQ-005, REQ-014)
- [x] **Cloud upload via pre-signed URL** — WorkManager `UploadDatasetWorker` POSTs to configurable Lambda endpoint, receives S3 pre-signed URL, streams the ZIP directly via `setFixedLengthStreamingMode` (bounded ~64 KB memory use per upload instead of buffering the whole file, and fixes an intermittent `SocketException: Broken pipe`); Wi-Fi only by default; retry with exponential backoff (max 5 attempts); upload status (Pending / Uploading / Failed) with a live progress percentage while uploading, shown in ContributeScreen "Upload history" section (REQ-014)
- [x] **Restartable upload — global + per-item** — global "Upload collected data" button (always tappable except a 60 s post-tap cooldown) cancels every outstanding `UploadDatasetWorker` job and deletes their ZIPs/sidecars before exporting and uploading fresh; each Upload history row additionally has its own **Restart** button, restartable immediately if `FAILED`, or once `PENDING`/`UPLOADING` for 30+ minutes with no sidecar `updated_at` change (stuck job), re-enqueueing only that entry via an atomic `ExistingWorkPolicy.REPLACE` without disturbing other in-flight uploads; on success the ZIP and sidecar are both deleted immediately — no local record of past uploads is kept, to minimize device storage (REQ-005, REQ-014)
- [x] **Upload history display cap** — section shows at most 10 active entries; footer "+ N more uploads not shown" when exceeded (rare, since restarting an upload clears stale entries) (REQ-005, REQ-014)
- [x] **Startup memory robustness** — `android:largeHeap="true"`; `DatasetExporter.exportSync()`'s ZIP output buffered; the on-app-start auto-upload catch-up check deferred until the camera pipeline (`LiveDetectionUi`) has actually composed instead of firing at raw process start — closes an OOM crash caused by camera startup, a WorkManager-resumed stale upload, and a same-launch catch-up export/upload all competing for memory at once (REQ-014, REQ-015)
- [x] **Scheduled daily auto-upload** — `AutoUploadWorker` as `PeriodicWorkRequest` (24 h, ±30 min flex); configurable time via `TimePicker` dialog (default 02:00); `auto_upload_last_date` tracks last run; on-start catch-up if missed; respects "Upload on mobile data" toggle for network constraint; notification with frame count on success; `POST_NOTIFICATIONS` runtime permission requested on Android 13+ (REQ-015)
- [x] **AWS infrastructure** — SAM template deploys private S3 bucket (Block Public Access, AES-256), Lambda (generates pre-signed PUT URLs, sanitises inputs), and HTTP API Gateway; two operator inputs: bucket name + admin IAM principal ARN; stack output `UploadServiceUrl` pasted into app Settings; deploy instructions in `aws-training-infra/aws/README.md` (REQ-018)
- [x] **Upload authentication — AWS infrastructure** — Cognito User Pool (email + password, self-registration, email verification required); Identity Pool linked to User Pool (`AllowUnauthenticatedIdentities: false`); API Gateway requires SigV4; S3 path now `uploads/<user_sub>/<device_id>/<filename>` (REQ-018)
- [x] **Upload authentication — Android client** — `AuthScreen` with sign-up / sign-in / verify-email flows; friendly error messages for all Cognito exception types; `UserNotConfirmedException` auto-routes to verify screen; `CognitoAuthManager` wraps SDK callbacks as `suspendCancellableCoroutine` (late callbacks after navigation are safely dropped); ID token exchanged for STS credentials via `CognitoCachingCredentialsProvider`; `UploadDatasetWorker` SigV4-signs requests using `AWS4Signer`; Cognito config and upload URL embedded via `BuildConfig` from `local.properties` (`AppConfig.seedPrefsIfNeeded()` seeds `UploadPrefs` on first launch — no UI entry fields); auth status row in ContributeScreen with Sign in / Sign out; `AutoUploadWorker` skips when user not signed in; sign-out cancels auto-upload schedule (REQ-014)
- [x] **Session-expiry detection** — `isSignedIn()` returns `false` once the refresh token has expired; a genuine `SessionExpiredException` → `markSessionExpired()` (keeps cached email + downloaded model). A persistent API 401/403 does **not** expire the session (REQ-029 — it's authorization, not expiry). ContributeScreen shows a red "session expired" banner distinct from "not signed in"; state re-synced on `ON_START` / 30 s poll / screen open (REQ-014, REQ-027, REQ-029)
- [x] **Manual upload bypasses network preference** — "Upload collected data", including a restart of a stuck/failed job, always uses `CONNECTED` (any network including mobile data); only the scheduled auto-upload respects the "Upload on mobile data" toggle (REQ-014)

### Auto-parking settings (requirements: REQ-017)
- [ ] **Auto-parking settings auto-configuration** — detect device capability on first enable; apply High-quality / Balanced / Efficient preset based on camera resolution and CPU cores; one-time informational banner; "Reset to recommended defaults" button in Settings (REQ-017)

### Parking access control (requirements: REQ-009 – REQ-010)
- [ ] **Vehicle type classifier** — MobileNetV2 TFLite model; classifies full frame as civilian / police / ambulance / fire_truck / military; runs in parallel with plate detector
- [ ] **Access decision UI** — AUTO-ALLOW (green) / CHECK PLATE (blue) / HOLD (orange) banner; majority-vote stability filter; configurable uncertain-type behavior and military auto-allow toggle in Settings
- [ ] **Access decision log** — local CSV, 90-day retention, exportable via share sheet; excluded from Auto Backup
- [ ] **Civilian plate database** — lookup collected plate against an allowed list (local SQLite or remote API); design TBD
