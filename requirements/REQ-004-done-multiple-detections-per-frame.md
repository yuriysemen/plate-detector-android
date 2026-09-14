---
id: REQ-004
title: Multiple Detections Per Frame — Investigation and Handling
status: done
priority: high
---

> **Partially split by [REQ-031](REQ-031-done-split-detection-and-training-apps.md):** the detection capability itself ("Current capability" below — `PlateDetector.detectAll()` returning every detection above threshold, no early exit) is unchanged and still exactly how `android/` works today; multiple plates per frame are still detected and drawn in the live overlay. Only "Saving multi-detection frames" (writing multiple YOLO lines to a `.txt`) is `training-android/`-only now.

## Summary

Investigate whether the current model and pipeline can detect multiple license plates in the same frame, and define how multi-detection frames are stored.

## Current capability

`PlateDetector.detectAll()` returns `List<Detection>` — it already iterates over all `maxDetections` slots in the model output tensor `[1, N, 6]` and appends every result above the confidence threshold. There is no early exit after the first detection.

**Conclusion: the pipeline already supports multiple plates per frame. No model change is needed.**

## YOLO format: multiple objects per file

YOLO natively supports multiple objects per image. Each detection is written as a separate line in the same `.txt` file:

```
0 0.512 0.334 0.218 0.071   ← plate 1
0 0.741 0.601 0.163 0.058   ← plate 2
0 0.210 0.450 0.190 0.065   ← plate 3
```

All detections from a single frame go into one image file and one label file. This is the standard YOLO multi-instance format and is supported by all Ultralytics training commands.

## Investigation: does the current model find multiple plates?

The current model (`plate_numbers.tflite`) was exported with `max_det=300`, meaning the TFLite model can output up to 300 detection candidates. After confidence filtering, any number of them above the threshold are returned.

**What limits multi-plate detection in practice:**

1. **NMS (Non-Maximum Suppression)** — The model was exported with `nms=True`, meaning NMS runs inside the TFLite graph at export time (baked in). This suppresses overlapping boxes. For plates that don't overlap, all are returned. For very close plates, one may be suppressed.

2. **Confidence threshold** — A second plate that's partially occluded or at an angle may score below the threshold and be missed. This is tunable via the existing slider.

3. **Training data distribution** — If the training set contained mostly single-plate images, the model may underperform on multi-plate scenes. Collecting multi-plate frames is valuable specifically to address this.

## Saving multi-detection frames

Write all detections to the same `.txt` file in the order returned by `detectAll()` (sorted by descending confidence, which is the current default).

```kotlin
val lines = detections.joinToString("\n") { det ->
    val imgW = rotated.width.toFloat()
    val imgH = rotated.height.toFloat()
    val cx = (det.leftPx + det.rightPx) / 2f / imgW
    val cy = (det.topPx + det.bottomPx) / 2f / imgH
    val bw = (det.rightPx - det.leftPx) / imgW
    val bh = (det.bottomPx - det.topPx) / imgH
    "0 ${"%.6f".format(cx)} ${"%.6f".format(cy)} ${"%.6f".format(bw)} ${"%.6f".format(bh)}"
}
```

## Value for training

Multi-plate frames are particularly valuable because:
- They teach the model that multiple instances can coexist (parking lots, roads with several cars).
- They stress-test NMS tuning.
- They are typically underrepresented in manually curated datasets.

## Out of scope

- **Rejection / hard negatives:** marking a detection as wrong inside this app is not supported. A separate editing app will handle reviewing collected frames and flagging false positives. This app only collects raw detections as-is.
- **Background frames:** frames with zero detections are not saved (decided in REQ-001). Hard negatives must be added manually in the editing app if needed.

## Recommendation: tag multi-detection frames

In `manifest.json`, track how many frames had ≥ 2 detections separately so dataset quality can be assessed:

```json
{
  "total_frames": 247,
  "total_detections": 389,
  "multi_detection_frames": 31
}
```

## Acceptance criteria

- [x] A frame with 3 detected plates produces a single `.jpg` and a `.txt` with exactly 3 lines.
- [x] The saved label file for a multi-detection frame is accepted by `yolo val` without error.
- [x] `manifest.json` correctly counts frames where detections ≥ 2.
- [x] Single-detection and zero-detection frames are handled identically to the multi-detection case (no special branching).
