import os
from pathlib import Path

import certifi
import torch

# Ensure HTTPS requests (model download, dataset fetch, etc.) use certifi's CA bundle.
os.environ["SSL_CERT_FILE"] = certifi.where()
os.environ["REQUESTS_CA_BUNDLE"] = certifi.where()

# Pick the best available device: Apple MPS, CUDA GPU, or CPU fallback.
device = "mps" if torch.backends.mps.is_available() else (0 if torch.cuda.is_available() else "cpu")
print("device:", device)

from ultralytics import YOLO

# Load a pretrained YOLOv11 nano checkpoint and train on the local dataset.
model = YOLO("yolo11n.pt")
model.train(
    data="../../datasets/dataset_YOLO/data.yaml",
    imgsz=640,
    epochs=50,
    batch=16,
    device=device,
)

# In case if model training is not completed, got to resume training.
# model = YOLO("runs/detect/train6/weights/last.pt")
# model.train(resume=True)
