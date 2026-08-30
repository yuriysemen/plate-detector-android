# plate-detector-android
An Android app for on-device license-plate detection, paired with a set of model-training experiments that export TensorFlow Lite (TFLite) models. The repository is intentionally organized to let me compare different training pipelines over time.
YOLO is the first (and currently implemented) experiment, with room for additional approaches later. OCR (reading plate text) is planned.

## Features
- Real-time license plate detection (on-device, TFLite YOLO)
- Bounding box overlay with confidence score
- OCR — reads plate text using ML Kit (always active; text shown in bounding box label)
- Optional beep alert on detection
- Training data collection — opt-in toggle saves detected frames as a YOLO dataset directly on device; frame files named `<date>_<time>_<seq>` with capture timestamp; configurable storage quota (default 500 MB) with 80%/100% banners; manual single-shot capture button saves the current frame with an empty label file for cases the model misses; **burst collection mode** captures every camera frame continuously until a configurable count is reached (default 100) — frames with detections get YOLO labels, frames without get empty label files; completion dialog offers to upload and start a new round or stop
- Dataset editor — review collected frames in read-only view mode; tap ✏ to enter edit mode and correct bounding boxes (move, resize, add, delete) with pinch-to-zoom; discard or save changes explicitly
- Cloud upload — packages frames into a ZIP and uploads to a private AWS S3 bucket; users register and sign in with email + password (Cognito User Pool); upload requests are SigV4-signed using short-lived STS credentials from a Cognito Identity Pool; AWS configuration is embedded at build time (no in-app URL or key entry); manual upload button works on any network (Wi-Fi or mobile); automatic daily upload at a configurable time (default 02:00) respects the Wi-Fi / mobile data preference; on-start catch-up if a scheduled run was missed; notification on successful auto-upload; upload history shows completed uploads with frame count and date — ZIP deleted from device after upload, sidecar kept as permanent local record
- Exported `data.yaml` includes device metadata (phone model, Android version, app version, anonymised device ID) for dataset provenance tracking
- Multiple models selectable in Settings; per-model confidence threshold
- Model artifacts published via GitHub Releases (tagged `model_v*`) and stored in S3 (`models/v<semver>/`); signed-in users receive automatic in-app model updates — confirmation dialog, then auto-selected immediately; manual "Check now" button in Settings; in-memory activity log shows check and download events per session

## Getting a model for the Android app

The Gradle build downloads a bundled default model automatically from the latest GitHub Release
tagged `model_v<x.y.z>`. For a local build you have three options:

**Option 1 — GitHub token (recommended):** Add to `android/local.properties` (gitignored):
```
MODEL_DOWNLOAD_TOKEN=ghp_<your_personal_access_token>
```
The token needs `repo` read scope. The build picks the latest `model_v*` release by semantic
version.

**Option 2 — Manual placement:** Copy a compatible `.tflite` (and its `.txt` sidecar) to
`android/app/src/main/assets/models/`. The download step is skipped when the file already exists.

**Option 3 — No model at build time:** If neither a token nor a local file is present, the build
succeeds with a warning. The app installs and shows a "No detection model" screen until a model
is downloaded at runtime after sign-in.

At runtime, signed-in users automatically receive model updates from S3 (`models/v<semver>/` in
the dataset bucket). The app checks on startup and every hour; the user confirms before any
download is applied.

## Project structure (high level)
- `android/` — Android application source.
- `curation-android/` — standalone, internal-only Android app for a trusted curator to review uploaded YOLO packages in S3 and promote them into a training-ready `done/` dataset. Reuses the same Cognito backend as `android/` but talks to S3 directly via a scoped `CuratorRole` (no backend API). See `curation-android/CLAUDE.md`. Requirements: REQ-022, REQ-023 (done); REQ-024 — box editor (draft).
- `infra/aws/` — AWS SAM infrastructure (S3 bucket, Lambda, API Gateway, Cognito, `CuratorRole`) for cloud dataset upload and curation. See `infra/aws/README.md` for deploy instructions.
- `training/` — Ready-to-run training pipelines implemented in Python.
- `experiments/` — Exploratory training experiments. Some experiments may be promoted into `training/` after they prove useful; others remain here for history and comparison.
  - `experiments/ultralytics/` for alternative training/export scripts.
- `dataset_YOLO/` for the initial YOLO dataset layout and format expectations.

## Release artifacts (signed when secrets are available)
The GitHub Actions release workflow signs artifacts when the Android keystore secrets are provided. When the secrets are missing, it still builds unsigned release outputs.

- Android App Bundle (signed if secrets are present): `android/app/build/outputs/bundle/release/app-release.aab`.
- APK output:
  - Signed when secrets are present: `android/app/build/outputs/apk/release/app-release.apk`.
  - Unsigned when secrets are missing: `android/app/build/outputs/apk/release/app-release-unsigned.apk`.

## Building the Android app locally (unsigned)
Local builds are unsigned by default, so you can run the standard Gradle tasks without supplying any extra parameters:

```
cd android
./gradlew :app:assembleRelease
```

The unsigned APK will be available at:
`android/app/build/outputs/apk/release/app-release-unsigned.apk`.

If you prefer a bundle, run:

```
cd android
./gradlew :app:bundleRelease
```

The unsigned bundle will be at:
`android/app/build/outputs/bundle/release/app-release.aab`.

## Building signed artifacts in GitHub Actions
The release workflow signs artifacts only when the keystore secrets are present. Provide the following secrets:

- `ANDROID_KEYSTORE_BASE64` (base64-encoded JKS/keystore file)
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The workflow decodes the keystore and sets the environment variables used by Gradle (`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) before running:

```
./gradlew clean :app:bundleRelease :app:assembleRelease
```

When those secrets are set, the workflow produces:

- Signed bundle: `android/app/build/outputs/bundle/release/app-release.aab`
- Signed APK: `android/app/build/outputs/apk/release/app-release.apk`

## Roadmap (planned)
- **Play Store compliance** — Auto Backup exclusion, Data Safety declaration, privacy policy update.
- **Parking access control** — vehicle-type classifier + access decision overlay (civilian / police / emergency).

See `android/ROADMAP.md` for the full backlog.

## Privacy
The app is designed to minimise data leaving the device:
- Camera frames are processed locally in memory; nothing is uploaded by default.
- The optional "Contribute data" feature (off by default) saves detected frames locally and can upload them to a private AWS S3 bucket run by the developer. A consent dialog is shown before any upload occurs.
- No analytics or tracking is used for core functionality.

See: [Privacy Policy](privacy-policy.md)

## License
This repository is licensed under **AGPL-3.0-only**. See [LICENSE](LICENSE).

Ultralytics YOLO (used for training/export) is AGPL-3.0. Model artifacts produced through that pipeline are treated as AGPL-3.0 by default per Ultralytics licensing.

See: [Third-Party Notices](THIRD_PARTY_NOTICES.md)
