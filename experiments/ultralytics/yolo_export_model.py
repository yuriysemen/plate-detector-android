import os
import certifi
import torch
from ultralytics import YOLO


# Ensure HTTPS requests (model download, dataset fetch, etc.) use certifi's CA bundle.
os.environ["SSL_CERT_FILE"] = certifi.where()
os.environ["REQUESTS_CA_BUNDLE"] = certifi.where()

# Pick the best available device: Apple MPS, CUDA GPU, or CPU fallback.
device = "mps" if torch.backends.mps.is_available() else (0 if torch.cuda.is_available() else "cpu")
print("device:", device)


# Export the trained model to a mobile-friendly TFLite format with NMS enabled.
m = YOLO("./runs/detect/train4/weights/best.pt")  # Update path to build model before exporting
m.export(format="tflite", imgsz=640, half=True, nms=True)

print("Model exported into tflite format: './yolo11n_saved_model' folder.")
