# plate-detector-android

An end-to-end license-plate detection product: an Android app that runs a TFLite YOLO model
on-device and reads plate text via ML Kit OCR, the AWS infrastructure and training-data
curation tooling that turn its opt-in uploads into a growing labeled dataset, and the training
pipelines that turn that dataset back into new models. YOLO is the first (and currently
implemented) detection experiment; the repo is organized to let additional approaches be compared
over time.

## What's in this repo

| Component | What it demonstrates |
|---|---|
| [`android/`](android/README.md) — the published detection app | On-device ML (TFLite YOLO + ML Kit OCR). Being trimmed to detection-only, no storage/upload/accounts — see [REQ-031](requirements/REQ-031-draft-split-detection-and-training-apps.md) |
| [`training-android/`](training-android/README.md) — the data-collection app | Cognito auth, SigV4-signed cloud upload, background work scheduling — everything `android/`'s collection pipeline used to do, now unpublished and internal-only |
| [`curation-android/`](curation-android/README.md) — the data-curation app | A third, purpose-built Android app for a human-in-the-loop labeling workflow directly against S3 (no backend API) |
| [`infra/aws/`](infra/aws/README.md) — cloud infrastructure | AWS SAM: S3, Lambda, API Gateway, Cognito User/Identity Pools, scoped IAM roles |
| [`training/`](training/ultralytics/README.md) / [`experiments/`](experiments/ultralytics/README.md) — model training | Python YOLO training/export pipelines that consume the curated dataset |
| [`requirements/`](requirements/README.md) | Spec-driven development trail — one `REQ-NNN` doc per feature, from initial detection format through cloud upload, curation workflow, and auth hardening |

## Android app features
- Real-time license plate detection (on-device, TFLite YOLO)
- Bounding box overlay with confidence score
- OCR — reads plate text using ML Kit (always active; text shown in bounding box label)
- Optional beep alert on detection
- Training data collection — opt-in "Contribute data" toggle; **captures only while signed in**. Detected frames are saved on device as a YOLO dataset (with the model's predicted boxes) and uploaded to a private server; nothing is written to disk while signed out. Frame files named `<date>_<time>_<seq>`; configurable storage quota (default 500 MB) with 80%/100% banners; manual single-shot capture button for missed plates; **burst collection mode** captures every frame until a configurable count (default 100). On-device review/editing of collected frames was removed — all box curation happens in the separate `curation-android` app.
- Cloud upload — packages frames into a ZIP and uploads to a private AWS S3 bucket; users register and sign in with email + password (Cognito User Pool); upload requests are SigV4-signed using short-lived STS credentials from a Cognito Identity Pool; AWS configuration is embedded at build time (no in-app URL or key entry); manual upload works on any network; automatic daily upload at a configurable time (default 02:00) respects the Wi-Fi / mobile data preference; on-start catch-up if a scheduled run was missed; notification on successful auto-upload; a persistent "Upload activity" log and per-upload failure reasons are shown in the app. An API 401/403 (authorization/config problem) fails the upload with a clear message but does not sign the user out.
- Privacy — `android:allowBackup="false"`; nothing the app stores (camera frames, upload ZIPs, auth tokens) leaves the device via cloud Auto Backup or device-to-device transfer
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

## Notes on the components above
- `curation-android/` reuses the same Cognito backend as `android/` but talks to S3 directly via
  a scoped `CuratorRole` (no backend API). A `curators`-group account resolves *both* apps to
  `CuratorRole` (shared User Pool client), which is why `CuratorRole` is also granted
  `execute-api:Invoke` — otherwise a curator's own account couldn't use the main app.
- `experiments/` holds exploratory training work; entries may be promoted into `training/` once
  they prove useful, or stay for history and comparison.
- `datasets/dataset_YOLO/` documents the initial YOLO dataset layout and format expectations.

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
