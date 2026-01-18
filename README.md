# plate-detector-android
An Android app for on-device license-plate detection, paired with a set of model-training experiments that export TensorFlow Lite (TFLite) models. The repository is intentionally organized to let me compare different training pipelines over time. YOLO is the first (and currently implemented) experiment, with room for additional approaches later.

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
- **Character-level recognition (OCR)**: add a separate model and pipeline to detect the license-plate number itself.
- **Event metadata**: emit notifications that specify *when* a plate is detected and *which* plate text was recognized. The event format and payload are still being designed and will be documented once the OCR pipeline lands.
