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

## Where the current model came from

The model shipping in the end-user app today (`plates_y11n5`, YOLOv11n, 640px) was **not** trained
on Ukrainian plates specifically. The dataset was a generic, multi-country plate-detection set
pulled from Roboflow Universe, with a slice of the project's own curated uploads mixed in (see
[`datasets/`](../../datasets/dataset_YOLO/README.md) for the exact mix and the Roboflow reference).
The idea was to get a detector accurate enough to bootstrap the collection app with — it finds
rectangular plate shapes in general, not Ukrainian plates in particular — and then retrain on real,
curator-reviewed Ukrainian uploads once enough of them existed.

**How training was run:** `yolo detect train ... model=yolo11n.pt epochs=250 patience=100`, run
name `plates_y11n5`. Ultralytics' early stopping halted it at epoch 150 (100 epochs with no further
validation gain), but it had already auto-saved its best checkpoint back at **epoch 116** (mAP50
0.976) — that's the weights file that got exported, not the epoch-150 snapshot. Letting it run all
the way to 150 instead of stopping around 116 cost extra wall-clock time for nothing; not ideal, but
harmless, since Ultralytics always keeps `best.pt` regardless of how much further training goes. See
[`training-report.html`](training-report.html) for the full per-epoch curves and the two earlier
runs. Training itself ran locally on a MacBook (Apple M4), not in the cloud — that's what
`--device mps` above is for.

**How "done" was decided:** there's no held-out human eval yet. After `export_tflite.py`, the
resulting `.tflite` was copied into `android-end-user-app`'s assets, the app was rebuilt, installed
on a device, and checked by eye against a handful of real frames. That's the extent of verification
today — no automated on-device benchmark yet.

## What's next

A second, much more targeted batch of curated uploads — real Ukrainian plates captured and
corrected through this project's own collection/review pipeline, instead of a generic public
dataset — is what the next training run should use. The infrastructure for that loop (collection
app → S3 → reviewing app → `done/` packages) is already built and working; what's missing is time
spent consolidating those packages into one clean dataset (dedup, split, sanity-check labels), which
is a deliberate manual step today (see [`datasets/`](../../datasets/dataset_YOLO/README.md)). That
consolidation, not compute or tooling, is the actual bottleneck before the model gets retrained on
data that matches what it actually needs to recognize.

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
