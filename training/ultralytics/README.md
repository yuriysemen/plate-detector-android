# Training (Ultralytics YOLO)

## Why this module exists

This closes the loop of the project: it takes the curated dataset (see
[`datasets/`](../../datasets/dataset_YOLO/README.md)) and produces the detection model that ships
in [`android-end-user-app`](../../android-end-user-app/README.md). It covers training and export to
TFLite for the Android app. For quick, throwaway trials use
[`experiments/`](../../experiments/ultralytics/README.md) instead.

This folder hosts a ready-to-run training process implemented in Python for the license-plate detector model using Ultralytics YOLO. It is the first option in the repository, with additional training approaches planned.
Training artifacts (runs/, weights/, exported models) are generated locally and
are intentionally not tracked by git.

## Contents
- `train.py` - minimal training entrypoint for YOLO.
- `requirements.txt` - Python dependencies for training.
- `export_tflite.py` - helper script to convert a trained `.pt` checkpoint into
  a `.tflite` file for the TensorFlow Lite runtime (what the Android app uses).
- [`training-report.html`](training-report.html) - a self-contained report of every training run
  to date (metrics, per-epoch curves, sample detections). Open it directly in a browser.

## Quick start (local or Colab)
1. Prepare python virtual environment:
   ```bash
   python3 -m venv .venv
   source .venv/bin/activate
   ```
2. Install deps:
   ```bash
   pip install -r requirements.txt
   ```
3. Prepare a YOLOv11 dataset outside of the repository (datasets are typically large and should not be committed).
   Organizing a new dataset is an administrator task.
   Today this means manually downloading the curator-reviewed `done/` packages from S3 (see
   [`android-training-data-reviewing-app`](../../android-training-data-reviewing-app/README.md)) and merging them into one
   dataset yourself. There's no automated consolidation step yet: it is manual on purpose, to keep the time to
   the next version of the training material short, and the packages are laid out so a web or desktop tool could
   do it later.
4. Run training in python implementation:
   ```bash
   python train.py --data <path_to_dataset>/data.yaml --model yolo11n.pt --epochs 20 --device cpu
   ```
   Or run training directly from console.
   ```bash
   yolo detect train data=<path_to_dataset>/data.yaml model=yolo11n.pt imgsz=640 epochs=20 batch=16 name=<training-model-name>
   ```
5. Export it in *.tflite format (for TensorFlow Lite runtime, what the Android app uses):
   ```bash
   python export_tflite.py --weights runs/detect/<training-model-name>/weights/best.pt --imgsz 640
   ```
   The script saves a `best.tflite` file next to the checkpoint unless `--output` is provided
   (`--conf`, `--iou`, and `--max-det` are also available and default to `0.25`, `0.45`, and `300`).
   It's a thin wrapper around `yolo export ... format=tflite`, picking the float16 variant and
   copying it out of the `best_saved_model/` directory the export leaves behind.
   Rename the file to reflect the model and copy it into the Android app assets. After rebuilding the app, you can select it.
   If you keep assets in the repo, this is usually under `android-end-user-app/app/src/main/assets/models/`.

> If you want to use a device other than CPU, pass `--device` explicitly (for example, `cuda` or `mps`).

> Tip: in Google Colab, upload this `training/` folder or clone the repo and run the same commands.

> For best accuracy, consider running 50+ epochs.
