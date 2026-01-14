# Dataset (Detection) — License Plate Detection

This repository does **not** include the dataset files (images/labels) because datasets from external sources (e.g., Roboflow, Kaggle, etc.) may have licensing and redistribution restrictions.

This document explains the expected dataset layout and how to obtain/build a compatible dataset for *detection* experiments (starting with YOLO). Future OCR experiments may introduce additional datasets and formats. Store the dataset under the `dataset_YOLO/` folder at the repository root.

## Expected image size and split sizes

- **Image resolution**: **640 × 640** (RGB)
- **Splits**:
  - **train**: **5000+** images
  - **valid**: **1000+** images
  - **test**: **1000+** images
- **Classes**: 1 (`License_Plate`)

> If your exported dataset has a different resolution, you can still use it; however, this repo assumes training/export at `imgsz=640`. Adjust `imgsz` in training/export if needed.

---

## 1) Dataset structure

Create the following directory structure (YOLO format). This is the baseline format used by detection experiments in this repository.

```
dataset/
  train/
    images/
      *.jpg | *.png
    labels/
      *.txt
  valid/
    images/
      *.jpg | *.png
    labels/
      *.txt
  test/
    images/
      *.jpg | *.png
    labels/
      *.txt
  data.yaml
```

### YOLO label format (`labels/*.txt`)

Each label file contains zero or more lines (one line per object):

```
<class_id> <x_center> <y_center> <width> <height>
```

All coordinates are **normalized** to `[0..1]` relative to image width/height.

Example (single object, class 0):
```
0 0.545 0.812 0.162 0.094
```

---

## 2) Train split

Path: `dataset/train/images` and `dataset/train/labels`

**Expected counts**
- images: **5000+**
- labels: **5000+** (one `.txt` per image, may be empty if no objects; usually for this dataset every image has a plate)

---

## 3) Valid split

Path: `dataset/valid/images` and `dataset/valid/labels`

**Expected counts**
- images: **1000**
- labels: **1000**

---

## 4) Test split

Path: `dataset/test/images` and `dataset/test/labels`

**Expected counts**
- images: **1000+**
- labels: **1000+**

---

## 5) data.yaml

Place `data.yaml` in `dataset/data.yaml`.

Example:

```yaml
train: ./train/images
val: ./valid/images
test: ./test/images

nc: 1
names: ['License_Plate']
```

Notes:
- Paths are relative to the location of `data.yaml`.
- `nc` must match the length of `names`.

---

## 6) How to obtain/build the dataset

### Option A — Export from Roboflow (recommended for quick setup)

1. Choose a dataset in **Roboflow Universe** suitable for license plate detection.
2. Export format: **YOLO** (YOLOv5/YOLOv8/YOLOv11 format is fine for Ultralytics).
3. Download the ZIP and unzip it locally.
4. Copy/move the extracted folders into this repository’s `dataset/` structure:
   - `train/images`, `train/labels`
   - `valid/images`, `valid/labels`
   - `test/images`, `test/labels`
   - `data.yaml`

Roboflow example (dataset reference used during experiments in this repo):
```text
Roboflow Universe: "license-plate-recognition-rxg4e" (version 11)
Export format: YOLO
License: CC BY 4.0 (verify on the dataset page before use)
```

> If Roboflow requires login/API key, use your own credentials. Do not commit keys to the repo.

### Option B — Convert from other annotation formats (VOC/COCO → YOLO)

If your dataset is in:
- Pascal VOC (XML)
- COCO (JSON)

You can convert annotations into YOLO `.txt` labels using any standard converter (there are many tools available). The end goal must match the structure and label format described above.

---

## 7) Validation checklist (before training)

Run these commands from the repo root to ensure the dataset is correct:

```bash
# Count images
find dataset/train/images -type f | wc -l
find dataset/valid/images -type f | wc -l
find dataset/test/images  -type f | wc -l

# Count labels
find dataset/train/labels -type f | wc -l
find dataset/valid/labels -type f | wc -l
find dataset/test/labels  -type f | wc -l
```

Expected:
- train images/labels: 5000 / 5000
- valid images/labels: 1000 / 1000
- test  images/labels: 1000 / 1000

Optional (quick sanity check for a few label lines):
```bash
head -n 5 dataset/train/labels/*.txt | head -n 20
```

---

## 8) Important: do not commit dataset files

Add (or keep) these entries in `.gitignore`:

```
dataset/
**/dataset/
*.zip
```

This repo is intended as a portfolio and engineering reference (training pipeline + Android inference).
You should download/build the dataset locally using the steps above.
