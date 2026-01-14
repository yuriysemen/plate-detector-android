# Ultralytics YOLO Experiments

This folder contains three Python scripts and a Jupyter notebook that train a YOLOv11 model on the local dataset, export a TFLite model, and optionally validate predictions for Android usage.

## Prerequisites

* Python 3.9+ (recommended)
* A working PyTorch + Ultralytics environment
* The dataset referenced by `../dataset_1/data.yaml`

Install dependencies (file name matches the repo):

```bash
pip install -r requirements.txt
```

## Option A: Run the Python scripts

Use the scripts when you want a quick, reproducible run from the terminal.

```bash
python yolo_train_model.py
python yolo_export_model.py
python yolo_test_model.py
```

What it does:

1. `yolo_train_model.py` selects the best available device (MPS, CUDA, or CPU) and trains `yolo11n.pt` for one epoch on `../dataset_1/data.yaml`.
2. `yolo_export_model.py` exports a TFLite model with NMS enabled.
3. `yolo_test_model.py` runs a quick validation visualization on the first validation image.

## Option B: Run the Jupyter notebook

Use the notebook if you want to iterate interactively.

```bash
jupyter notebook yolo.ipynb
```

Suggested workflow:

1. Run the install cell (if you haven't installed dependencies yet).
2. Execute cells top-to-bottom to train and export the model.

## Output

The export step creates a TFLite model in the current working directory. Adjust `epochs`, `imgsz`, or `batch` in either file to suit your experiment.

## Validation visualization

Both the script and notebook include a small sanity check that loads the first validation image, runs inference, and draws:

* **Expected** bounding boxes from YOLO labels (green dashed).
* **Predicted** boxes from the model (red dashed).

This is a quick way to verify the model’s output on real validation data before exporting to TFLite.
