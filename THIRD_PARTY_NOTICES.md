# Third-Party Notices — Car Plate Detector

This project includes and/or is built with third‑party open‑source components. Their respective licenses apply.

> Tip: The definitive list of Android dependencies is in the Gradle build files.
> The definitive list of Python dependencies for training is in `training/` (requirements or your `pip` environment).

## Ultralytics YOLO (YOLOv11)
- Used in: `training/`, `experiments/` and to produce the released model artifacts (`best.pt`, `best_float16.tflite`).
- License: GNU Affero General Public License v3.0 (AGPL‑3.0)
- Project: Ultralytics YOLO
- License information: https://www.ultralytics.com/license

## Android / Kotlin ecosystem (major components)
Depending on your Gradle configuration, the Android app typically uses components such as:
- Kotlin (JetBrains)
- AndroidX / Jetpack libraries (Google)
- Camera framework (e.g., CameraX) (Google)
- (Optional) TensorFlow Lite runtime / support libraries (Google) if used for TFLite inference

Each component is distributed under its own license. Please review the exact artifacts and licenses in your Gradle dependency tree.

## Trademarks
“Android” and “Google Play” are trademarks of Google LLC. Other trademarks belong to their respective owners.
