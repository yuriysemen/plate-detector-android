import torch
import os, certifi

os.environ["SSL_CERT_FILE"] = certifi.where()
os.environ["REQUESTS_CA_BUNDLE"] = certifi.where()

device = "mps" if torch.backends.mps.is_available() else (0 if torch.cuda.is_available() else "cpu")
print("device:", device)

from ultralytics import YOLO

model = YOLO("yolo11n.pt")
model.train(
    data="../dataset_1/data.yaml",
    imgsz=640,
    epochs=1,
    batch=16,
    device=device,
)


m = YOLO("yolo11n.pt")
m.export(format="tflite", imgsz=640, half=True, nms=True)
