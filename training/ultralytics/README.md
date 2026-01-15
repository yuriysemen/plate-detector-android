# Training (Ultralytics Experiment)

This folder hosts a ready-to-run training process implemented in Python for the license-plate detector model using Ultralytics YOLO. It is the first option in the repository, with additional training approaches planned.
Training artifacts (runs/, weights/, exported models) are generated locally and
are intentionally not tracked by git.

## Contents
- `train.py` - minimal training entrypoint for YOLO.
- `requirements.txt` - Python dependencies for training.
- `export_ptlite.py` - helper script to convert a trained `.pt` checkpoint into
  a mobile-friendly `.ptlite` file for the PyTorch Lite runtime.

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
4. Run training in python implementation:
   ```bash
   python train.py --data <path_to_dataset>/data.yaml --model yolo11n.pt --epochs 20 --device cpu
   ```
   Or run training directly from console.
   ```bash
   yolo detect train data=<path_to_dataset>/data.yaml model=yolo11n.pt imgsz=640 epochs=20 batch=16 name=<training-model-name>
   ```
5. Export it in *.tflite format (for TensorFlow Lite runtime):
   ```bash
   yolo export model=runs/detect/<training-model-name>/weights/best.pt format=tflite imgsz=640 nms=True conf=0.25 iou=0.45 max_det=300
   ```
   The export writes artifacts under `runs/detect/<training-model-name>/weights/best_saved_model/` and produces a
   `best_float16.tflite` (or similar) file that is compatible with the Android app.
   Rename the file to reflect the model and copy it into the Android app assets. After rebuilding the app, you can select it.
6. Export it in *.ptlite format (for PyTorch Lite runtime):
   ```bash
   python export_ptlite.py --weights runs/detect/<training-model-name>/weights/best.pt --imgsz 640
   ```
   The script saves a `best.ptlite` file next to the checkpoint unless `--output` is provided.
   Copy the resulting `.ptlite` into the Android app assets (or wherever your app expects model assets)
   and update the app-side model name accordingly. If you keep assets in the repo, this is usually
   under `android/app/src/main/assets/`.

> If you want to use a device other than CPU, pass `--device` explicitly (for example, `cuda` or `mps`).

> Tip: in Google Colab, upload this `training/` folder or clone the repo and run the same commands.

> For best accuracy, consider running 50+ epochs.
