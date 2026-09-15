# Plate Detector (Android)

The published app: real-time on-device license-plate detection (TFLite YOLO) with ML Kit OCR.
**Detection-only** — it stores nothing, uploads nothing, requires no account, and makes no
network calls at runtime ([REQ-031](../requirements/REQ-031-done-split-detection-and-training-apps.md)).
The account/capture/upload pipeline this app used to have now lives in the internal, unpublished
[`../android-training-data-collection-app/`](../android-training-data-collection-app/README.md) app instead. See the
[repo-level README](../README.md) for the full feature list, privacy notes, and license.

## Quick start

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # unsigned release APK (signed if ANDROID_KEYSTORE_* env set)
./gradlew :app:test                   # unit tests
./gradlew :app:connectedAndroidTest   # instrumented tests (requires a connected device/emulator)
```

A model is required to run detection — see [Getting a model for the Android app](../README.md#getting-a-model-for-the-android-app)
in the repo README for the three ways to provide one locally. All of them are build-time only;
there's no runtime download path here.

## More detail

- [`CLAUDE.md`](CLAUDE.md) — architecture, key files, and build/config notes for anyone (human or
  AI) making changes here.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — deeper architecture reference.
- [`ROADMAP.md`](ROADMAP.md) — feature backlog and history for this app specifically; see
  [`../android-training-data-collection-app/ROADMAP.md`](../android-training-data-collection-app/ROADMAP.md) for the capture/upload/auth
  history that used to be here.
- [`../requirements/`](../requirements/README.md) — the spec each feature was built against.
