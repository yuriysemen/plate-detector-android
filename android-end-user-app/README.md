# Plate Detector — End-User App (Android)

The **published** app of the project: point the camera at a vehicle and see license plates detected
and read in real time, entirely on the device.

Published on Google Play as *Car Plate Detector*
(`com.github.yuriysemen.platesdetector`).

## Why this module exists

This is the product the rest of the repository exists to improve. It is deliberately small:
**detection only**. It has no accounts, stores no camera frames, uploads nothing, and declares no
network permission at all. That is a design decision, not a limitation — the app used to also
collect training data, and that pipeline was split out into a separate internal app so the public
app could make a simple, verifiable privacy promise ("collects nothing") with trivial Play Store
data-safety obligations. See
[REQ-031](../requirements/REQ-031-done-split-detection-and-training-apps.md) for the reasoning and
[REQ-039](../requirements/REQ-039-done-end-user-app-scope-and-deployment.md) for the current scope.

## What it does

- Real-time plate detection on the live camera feed (on-device TFLite YOLO), throttled to about
  8 frames per second.
- Bounding-box overlay with confidence score.
- OCR: reads the plate text with ML Kit and shows it in the box label.
- Optional beep alert on detection.
- Multiple models selectable in Settings, with a per-model confidence threshold, scan interval, and
  analysis resolution.
- Camera controls: pinch-to-zoom, tap-to-focus, torch, zoom shortcuts, and exposure compensation.

## What it deliberately does not do

- No sign-in or accounts.
- No storage of camera frames or detections (`android:allowBackup="false"`, and nothing sensitive is
  written in the first place).
- No upload and no runtime network access — `INTERNET` is not declared in the manifest.
- No training-data collection or review. That lives in
  [`android-training-data-collection-app`](../android-training-data-collection-app/README.md) and
  [`android-training-data-reviewing-app`](../android-training-data-reviewing-app/README.md).

## How it works

Single-Activity Jetpack Compose app. Per frame: CameraX `ImageAnalysis` → bitmap conversion and
rotation → `PlateDetector` (TFLite; letterbox preprocessing, decodes the `[1, N, 6]` YOLO output,
maps boxes back to image space) → optional `PlateOCR` (ML Kit) on each cropped plate → overlay.
Detection pauses when the app leaves the foreground. Candidate model files are validated before
they are offered, so an unrelated `.tflite` can never be mistaken for a detector.

Full detail in [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Tech

Kotlin · Jetpack Compose · CameraX · TensorFlow Lite · ML Kit Text Recognition · Gradle ·
GitHub Actions

## Quick start

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # unsigned release APK (signed if ANDROID_KEYSTORE_* env set)
./gradlew :app:bundleRelease          # unsigned release bundle
./gradlew :app:test                   # unit tests
./gradlew :app:connectedAndroidTest   # instrumented tests (needs a device/emulator)
```

Run these from this folder. A model is required to run detection — see below.

## Getting a model

The model is **build-time only**; there is no runtime download path. The Gradle build downloads a
default model from the latest GitHub Release tagged `model_v<x.y.z>`. For a local build you have
three options:

**1. GitHub token (recommended).** Add to `local.properties` (gitignored):

```
MODEL_DOWNLOAD_TOKEN=ghp_<your_personal_access_token>
```

The token needs read access to the repository's releases. The build picks the latest `model_v*`
release by semantic version. It is also read from the Gradle property or the environment variables
`MODEL_DOWNLOAD_TOKEN` / `GITHUB_TOKEN` (set automatically in GitHub Actions).

The repository the release is fetched from defaults to `yuriysemen/plate-detector-android`. Override
it with `MODEL_REPO=<owner>/<repo>` in `local.properties` (or as a Gradle property) if you fork or
rename the repo. In GitHub Actions it follows `GITHUB_REPOSITORY` automatically.

**2. Manual placement.** Copy a compatible `.tflite` and its `.txt` sidecar into
`app/src/main/assets/models/`. The download step is skipped when the file already exists.

**3. No model.** If neither is available the build succeeds with a warning. The app installs and
shows a "No detection model" screen with a Retry button. This can only be fixed by rebuilding or
placing a file and reinstalling.

Models come from the [`training/`](../training/ultralytics/README.md) pipeline.

## Release builds

Local builds are unsigned by default:

```bash
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release-unsigned.apk
./gradlew :app:bundleRelease     # app/build/outputs/bundle/release/app-release.aab
```

Signed builds run in GitHub Actions
([`android-release.yml`](../.github/workflows/android-release.yml)) on `v*` tags. The workflow
signs the APK and bundle when these repository secrets are set, and produces unsigned artifacts
otherwise:

- `ANDROID_KEYSTORE_BASE64` — base64-encoded keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

To sign locally, set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and
`ANDROID_KEY_PASSWORD` in the environment. Never commit the keystore.

## Privacy

Camera frames are processed in memory and discarded. Nothing is uploaded, no analytics or tracking
is included, and the app has no network access. See the [Privacy Policy](../privacy-policy.md).

## More detail

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — architecture reference.
- [`CLAUDE.md`](CLAUDE.md) — key files and build/config notes for anyone (human or AI) changing this app.
- [`ROADMAP.md`](ROADMAP.md) — feature backlog and history.
- [`../requirements/`](../requirements/README.md) — the specs each feature was built against.
