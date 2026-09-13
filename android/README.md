# Plate Detector (Android)

The main app: real-time on-device license-plate detection (TFLite YOLO) with ML Kit OCR, plus an
opt-in "Contribute data" flow that uploads collected frames to a private AWS S3 bucket for later
curation and retraining. See the [repo-level README](../README.md) for the full feature list,
privacy notes, and license.

## Quick start

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # unsigned release APK (signed if ANDROID_KEYSTORE_* env set)
./gradlew :app:test                   # unit tests
./gradlew :app:connectedAndroidTest   # instrumented tests (requires a connected device/emulator)
```

A model is required to run detection — see [Getting a model for the Android app](../README.md#getting-a-model-for-the-android-app)
in the repo README for the three ways to provide one locally.

The cloud upload / model-update features need `local.properties` configured from the
[`infra/aws`](../infra/aws/README.md) stack outputs — copy `local.properties.example` to
`local.properties` and fill in the `COGNITO_*` / `UPLOAD_SERVICE_URL` values. Without it, the app
still builds and runs detection; only sign-in and upload are unavailable.

## More detail

- [`CLAUDE.md`](CLAUDE.md) — architecture, key files, and build/config notes for anyone (human or
  AI) making changes here.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — deeper architecture reference.
- [`ROADMAP.md`](ROADMAP.md) — full feature backlog and history.
- [`../requirements/`](../requirements/README.md) — the spec each feature was built against.
