# plate-detector-android
Train a YOLO-based license plate detector in Google Colab and deploy it as a TFLite model in an Android app for real-time on-device detection.

## Getting a model for the Android app
The Android app expects one or more `.tflite` files in the assets directory. When you start working on the app:

1. Train/export a model using the training pipeline under `training/ultralytics`.
   - Follow the steps in `training/ultralytics/README.md` to run YOLO training and export a TensorFlow Lite model.
2. Copy the exported `.tflite` file into the Android assets folder:
   - Preferred location: `android/app/src/main/assets/models/`
   - Fallback location (if no `models/` folder exists): `android/app/src/main/assets/`
3. Rebuild the app. The UI will list all available `.tflite` files so you can choose which model to run.

> The app supports multiple models. Drop additional `.tflite` files into the assets folder and they will appear in the model picker. This is designed for future iterations of training so that you can compare or ship multiple models at once.

## Project structure (high level)
- `android/` — Android application source.
- `training/` — Model training project (first iteration lives under `training/ultralytics`).
- `experiments/` — Planned for future training iterations and experiments (not added yet).
