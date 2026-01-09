# Training (YOLO)

This folder hosts the training project for the license-plate detector model.

## Contents
- `train.py` - minimal training entrypoint for YOLO.
- `dataset.yaml` - dataset configuration placeholder.
- `requirements.txt` - Python dependencies for training.

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
3. Prepare data set in YOLO v11 outside of repository code. Usually it is large, so, it is not good to commit it into repo as a code. 
4. Run training in python implementation:
   ```bash
   python train.py --data <path_to_dataset>/data.yaml --model yolo11n.pt --epochs 20 --devices cpu or mps>
   ```
   Or run training directly from console.
   ```bash
   yolo detect train data=<path_to_dataset>/data.yaml model=yolo11n.pt imgsz=640 epochs=20 batch=16 name=<training-model-name>
   ```
5. Export it in *.tflite format:
   ```bash
   yolo export model=runs/detect/<training-model-name>/weights/best.pt format=tflite imgsz=640 nms=True conf=0.25 iou=0.45 max_det=300
   ```
   As a result You will receive model in folder at runs/detect/<training-model-name>/weights/bet_saved_model that
   will contain base.onnx (outside of this folder) and a file that is compatible with android app - 'best_float16.tflite'.
   You will need to rename this file to reflect the model meaning and copy into android app. That after rebuilding the app - You can use it.

> If you have some other device that could be used except CPU to train the model - it will not be recognised automatically. You should properly state `device` parameter to use available devices.

> Tip: in Google Colab, upload this `training/` folder or clone the repo and run the same commands.

> For best accuracy, consider running 50+ epochs.
