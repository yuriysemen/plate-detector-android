import os
from pathlib import Path

import certifi
import torch
import yaml
from PIL import Image
import matplotlib.pyplot as plt
import matplotlib.patches as patches

from ultralytics import YOLO


# Ensure HTTPS requests (model download, dataset fetch, etc.) use certifi's CA bundle.
os.environ["SSL_CERT_FILE"] = certifi.where()
os.environ["REQUESTS_CA_BUNDLE"] = certifi.where()


def load_first_validation_sample(data_yaml_path: Path) -> tuple[Path, Path]:
    """Return the first validation image path and its label file path."""
    data = yaml.safe_load(data_yaml_path.read_text())
    val_key = "val" if "val" in data else "valid"

    # Find where the package is installed
    val_images_dir = (data_yaml_path.parent / data[val_key]).resolve()

    exists = val_images_dir.exists() & val_images_dir.is_dir()

    if not exists:
        val_images_dir = (data_yaml_path.parent / data[val_key][1:]).resolve()
        exists = val_images_dir.exists() & val_images_dir.is_dir()

    if not exists:
        raise FileNotFoundError(f"No validation images found in {val_images_dir}")

    image_candidates = sorted(
        path
        for path in val_images_dir.iterdir()
        if path.suffix.lower() in {".jpg", ".jpeg", ".png"}
    )
    if not image_candidates:
        raise FileNotFoundError(f"No validation images found in {val_images_dir}")
    image_path = image_candidates[0]
    label_path = val_images_dir.parent / "labels" / f"{image_path.stem}.txt"
    return image_path, label_path


def parse_yolo_labels(label_path: Path, image_width: int, image_height: int) -> list[tuple[float, float, float, float]]:
    """Parse YOLO labels and return pixel-space rectangles (x_min, y_min, width, height)."""
    if not label_path.exists():
        return []
    boxes = []
    for line in label_path.read_text().strip().splitlines():
        if not line.strip():
            continue
        _, x_center, y_center, width, height = map(float, line.split())
        box_width = width * image_width
        box_height = height * image_height
        x_min = (x_center * image_width) - (box_width / 2)
        y_min = (y_center * image_height) - (box_height / 2)
        boxes.append((x_min, y_min, box_width, box_height))
    return boxes

device = "mps" if torch.backends.mps.is_available() else (0 if torch.cuda.is_available() else "cpu")
print("device:", device)

# Visual sanity check on the first validation image (ground truth vs. predictions).
data_yaml_path = (Path(__file__).resolve().parent / "../dataset_YOLO/data.yaml").resolve()
image_path, label_path = load_first_validation_sample(data_yaml_path)
image = Image.open(image_path).convert("RGB")
image_width, image_height = image.size

weights_path = Path("runs/detect/train/weights/best.pt")
eval_model = YOLO(str(weights_path)) if weights_path.exists() else YOLO("yolo11n.pt")
results = eval_model.predict(source=str(image_path), imgsz=640, device=device, conf=0.25)
predicted_boxes = results[0].boxes.xyxy.cpu().tolist() if results[0].boxes else []
expected_boxes = parse_yolo_labels(label_path, image_width, image_height)

fig, ax = plt.subplots(figsize=(8, 8))
ax.imshow(image)

for x_min, y_min, width, height in expected_boxes:
    ax.add_patch(
        patches.Rectangle(
            (x_min, y_min),
            width,
            height,
            linewidth=2,
            edgecolor="lime",
            facecolor="none",
            linestyle="--",
            label="expected" if "expected" not in ax.get_legend_handles_labels()[1] else None,
        )
    )

for x_min, y_min, x_max, y_max in predicted_boxes:
    ax.add_patch(
        patches.Rectangle(
            (x_min, y_min),
            x_max - x_min,
            y_max - y_min,
            linewidth=2,
            edgecolor="red",
            facecolor="none",
            linestyle="--",
            label="predicted" if "predicted" not in ax.get_legend_handles_labels()[1] else None,
        )
    )

ax.set_title("Validation sample: expected (green) vs predicted (red)")
ax.axis("off")
ax.legend(loc="upper right")
plt.show()
