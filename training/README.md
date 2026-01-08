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
2. Update `dataset.yaml` with your dataset paths.
3. Run training:
   ```bash
   python train.py --data dataset.yaml --model yolov8n.pt --epochs 100
   ```

> Tip: in Google Colab, upload this `training/` folder or clone the repo and run the same commands.
