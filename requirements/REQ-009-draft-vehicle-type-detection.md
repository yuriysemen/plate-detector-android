---
id: REQ-009
title: Vehicle Type Detection — Police, Medical, Fire vs Civilian
status: draft
priority: high
---

## Summary

Extend the detection pipeline to classify the approaching vehicle as a special service vehicle (police, ambulance, fire truck) or a civilian vehicle. Special service vehicles are auto-allowed at the parking barrier without plate verification. Civilian vehicles proceed to plate check (REQ-010).

This requirement is specific to the Ukrainian deployment context: Ukrainian police, ambulance, and fire truck vehicles carry distinctive visual markings that make them classifiable from a single camera frame.

---

## Ukrainian special vehicle visual characteristics

| Type | Ukrainian name | Distinctive visual features |
|---|---|---|
| Police | Поліція | White or dark vehicle, blue-yellow stripe markings, "ПОЛІЦІЯ" text, roof light bar |
| Ambulance | Швидка допомога | White vehicle, red cross symbol, "ШВИДКА ДОПОМОГА" text, roof light bar |
| Fire truck | Пожежна охорона | Red/orange large vehicle, "ПОЖЕЖНА ОХОРОНА" text, large body size |
| Military | Військові | Camouflage or military green, army insignia — optional, see REQ-010 Settings |
| Civilian | — | Everything else |

---

## Model architecture decision

**Recommended: a separate vehicle type classifier running in parallel with the existing plate detector.**

The existing plate detection model (`plate_numbers.tflite`) is left unchanged. A second lightweight classification model classifies the vehicle type from the full camera frame.

```
Camera frame (rotated bitmap)
  ├── PlateDetector (existing YOLO TFLite)       → plate bounding boxes
  │     └── PlateOCR (if OCR enabled)            → plate text
  └── VehicleTypeClassifier (new TFLite)         → VehicleClassification(type, confidence)
                                                 ↓
                                        AccessDecisionEngine (REQ-010)
```

**Why not a single multi-class YOLO model:**
- The existing plate model is trained and stable; retraining it risks regressing plate detection quality.
- Vehicle type classification from a parking camera (single vehicle per frame, frontal view) is a simpler problem than multi-class YOLO detection — a MobileNet-sized classifier is sufficient and 3–5× faster.
- Each model can be updated independently without touching the other.

**Why full-frame input for the classifier:**
At a parking barrier, the camera is positioned to capture one approaching vehicle. The full frame contains the most visual context (vehicle color, shape, light bar, text). Cropping around the plate bounding box would discard exactly the features that distinguish vehicle types.

---

## Vehicle type classifier specification

| Property | Value |
|---|---|
| Input | Full `rotated` bitmap, resized to 224×224 |
| Output | `[N_classes]` float32 probability vector (softmax) |
| Architecture | MobileNetV2 fine-tuned on a Ukrainian vehicle dataset, int8 quantized |
| Inference time target | < 60ms on a mid-range device (Snapdragon 665 equivalent) |
| Model file | `vehicle_type_classifier.tflite` (separate from the plate model) |

### Output classes

| Index | Label | Description |
|---|---|---|
| 0 | civilian | Standard passenger car, SUV, van, motorcycle |
| 1 | police | Marked police vehicle (Поліція) |
| 2 | ambulance | Ambulance (Швидка допомога) |
| 3 | fire_truck | Fire truck (Пожежна охорона) |
| 4 | military | Army/military vehicle — only meaningful when the "auto-allow military" setting is enabled |

The classifier outputs a **single vehicle type per frame**. Multi-vehicle frames are not a concern for the parking barrier use case.

---

## Confidence threshold

- A vehicle type is declared **confirmed** only if the classifier confidence ≥ a configurable threshold.
- **Default threshold: 0.85** (high — erring on the side of not auto-allowing ambiguous vehicles).
- If confidence is below threshold for the winning class, the result is **UNCERTAIN** and handed to REQ-010 for handling.
- The threshold is configurable in Settings (see REQ-010).

---

## New data types

```kotlin
enum class VehicleType {
    UNKNOWN,      // classifier not loaded or not run
    CIVILIAN,
    POLICE,
    AMBULANCE,
    FIRE_TRUCK,
    MILITARY
}

data class VehicleClassification(
    val type: VehicleType,
    val confidence: Float      // 0.0–1.0, top class probability
)
```

`UNKNOWN` is returned when the model is missing or an inference error occurs. The app continues gracefully in this case (see Graceful degradation below).

---

## Integration with CameraPreviewWithAnalysis

The classifier runs on the existing background analysis thread, immediately after plate detection:

```
Per-frame callback:
  1. YUV → Bitmap → rotated             (existing)
  2. Throttle check (targetFps)         (existing)
  3. PlateDetector.detectAll(rotated)   (existing)
  4. VehicleTypeClassifier.classify(rotated)   ← NEW
  5. PlateOCR on plate crops (if OCR enabled)  (existing)
  6. post { onResult(detections, vehicleClassification, ...) }  ← updated signature
```

Step 4 adds ~40–60ms per frame. At 8 fps (default) the total frame budget is 125ms, which remains comfortable.

---

## Training data requirements

The classifier needs labeled images for each vehicle class — real Ukrainian street/parking-lot conditions:

| Class | Minimum images | Key variation to cover |
|---|---|---|
| Civilian | 2,000 | Various makes, colors, angles, lighting |
| Police | 500 | Different model years, front/side/rear, day/night |
| Ambulance | 300 | With and without active lights |
| Fire truck | 300 | Small and large variants, front/side |
| Military | 300 | Only if the "auto-allow military" feature is enabled |

Data sources:
- **Curation app typed boxes (REQ-025)** — the primary source. Curators label each detection in
  uploaded packages with a vehicle-type class (`config/vehicle-categories.json`); curated packages
  in `done/` then carry `nc > 1` YOLO labels with per-box vehicle type. Note the class keys differ
  slightly from this doc (`medical` vs `ambulance`, `other` vs `military`) — reconcile the list
  when this classifier is actually built.
- Ukrainian public dashcam repositories (e.g., YouTube dashcam channels)
- Open-license street photography

> **Architecture note:** this doc recommends a *separate full-frame MobileNet classifier* (car
> colour, light bar, livery text — features a plate crop lacks). REQ-025 instead folds the type
> into the *detection* labels (plate-box class, or a curator-drawn car box). These are not
> mutually exclusive — REQ-025 builds the labelled data; the model choice here is still open.

Training pipeline: standard image classification fine-tuning in Python (not YOLO), exported via `tf.lite.TFLiteConverter`. See `training/` for the eventual pipeline location.

---

## Graceful degradation

If `vehicle_type_classifier.tflite` is missing from assets:
- `VehicleClassification(type = UNKNOWN, confidence = 0f)` is returned every frame.
- The access decision engine (REQ-010) treats `UNKNOWN` according to the "On uncertain vehicle type" setting (default: treat as civilian → plate check).
- No crash. No alert to the user unless the access control screen is active.

This allows the app to ship the plate detection feature before the vehicle type model is trained and bundled.

---

## Acceptance criteria

- [ ] A Ukrainian police car image (frontal view, daylight) returns `POLICE` with confidence ≥ 0.85.
- [ ] A standard civilian car returns `CIVILIAN` with confidence ≥ 0.70.
- [ ] Classifier inference completes in < 80ms on a Snapdragon 660-class device.
- [ ] Classification result is available in the same `onResult` callback as plate detections — no extra callback or delay.
- [ ] When `vehicle_type_classifier.tflite` is absent, `VehicleType.UNKNOWN` is returned and the app does not crash.
- [ ] Changing the confidence threshold in Settings takes effect on the next processed frame without restarting the camera.
