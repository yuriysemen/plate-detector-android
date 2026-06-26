# plate-detector-android
An Android app for on-device license-plate detection, paired with a set of model-training experiments that export TensorFlow Lite (TFLite) models. The repository is intentionally organized to let me compare different training pipelines over time.
YOLO is the first (and currently implemented) experiment, with room for additional approaches later. OCR (reading plate text) is planned.

## Features
- Real-time license plate detection (on-device, TFLite YOLO)
- Bounding box overlay with confidence score
- OCR — reads plate text using ML Kit (always active; text shown in bounding box label)
- Optional beep alert on detection
- Training data collection — opt-in toggle saves detected frames as a YOLO dataset directly on device; frame files named `<date>_<time>_<seq>` with capture timestamp; configurable storage quota (default 500 MB) with 80%/100% banners
- Dataset editor — review collected frames in read-only view mode; tap ✏ to enter edit mode and correct bounding boxes (move, resize, add, delete) with pinch-to-zoom; discard or save changes explicitly
- Cloud upload — packages frames into a ZIP and uploads to a private AWS S3 bucket; users register and sign in with email + password (Cognito User Pool); upload requests are SigV4-signed using short-lived STS credentials from a Cognito Identity Pool; AWS configuration is embedded at build time (no in-app URL or key entry); manual upload button or automatic daily upload at a configurable time (default 02:00); respects Wi-Fi / mobile data preference; on-start catch-up if a scheduled run was missed; notification on successful auto-upload
- Exported `data.yaml` includes device metadata (phone model, Android version, app version, anonymised device ID) for dataset provenance tracking
- Multiple models selectable; custom `.tflite` import; per-model confidence threshold
- Model artifacts published via GitHub Releases (`best.pt`, `best_float16.tflite`)

## Getting a model for the Android app
The Android app expects one or more `.tflite` files in the assets directory. When you start working on the app:

1. Choose a training experiment (for example, `training/ultralytics` or `experiments/ultralytics`) and produce a `.tflite` model.
   - Each experiment README explains how it trains, exports, and validates the model.
2. Copy the exported `.tflite` file into the Android assets folder:
   - Preferred location: `android/app/src/main/assets/models/`
   - Fallback location (if no `models/` folder exists): `android/app/src/main/assets/`
3. Rebuild the app. The UI will list all available `.tflite` files so you can choose which model to run.

> The app supports multiple models. Drop additional `.tflite` files into the assets folder and they will appear in the model picker. This is designed for future iterations of training so that you can compare or ship multiple models at once.

## Project structure (high level)
- `android/` — Android application source.
- `infra/aws/` — AWS SAM infrastructure (S3 bucket, Lambda, API Gateway) for cloud dataset upload. See `infra/aws/README.md` for deploy instructions.
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
- **Model auto-update** — download updated `.tflite` models from GitHub Releases without a full app update.
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
