# Training (YOLO)

This folder hosts the training project for the license-plate detector model.

## Contents
- `train.py` - minimal training entrypoint for YOLO.
- `dataset.yaml` - dataset configuration placeholder.
- `requirements.txt` - Python dependencies for training.

## Quick start (local or Colab)
1. Install deps:
   ```bash
   pip install -r requirements.txt
   ```
2. Prepear data set in YOLO v11 outside of repository code. Usually it is large, so, it is not good to commit it into repo as a code. 
3a. Run training in python implementation:
   ```bash
   python train.py --data <path_to_dataset>/data.yaml --model yolo11n.pt --epochs 20 --devices cpu or mps>
   ```
3b. Run training directly from console.
   ```bash
   yolo detect train data=<path_to_dataset>/data.yaml model=yolo11n.pt imgsz=640 epochs=20 batch=16 name=plate-detector
   ```

> If you have GPU on device is running - it will not be recognised automatically. You should properly state `device` parameter. Mostly, it is necessary to set to "mps".

> Tip: in Google Colab, upload this `training/` folder or clone the repo and run the same commands.

> For best accuracy, consider running 50+ epochs.
