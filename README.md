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

## Roadmap (planned)
- **Character-level recognition (OCR)**: add a separate model and pipeline to detect the license-plate number itself.
- **Event metadata**: emit notifications that specify *when* a plate is detected and *which* plate text was recognized. The event format and payload are still being designed and will be documented once the OCR pipeline lands.
