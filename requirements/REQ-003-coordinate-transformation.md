---
id: REQ-003
title: Coordinate Transformation — Detection Pixels to YOLO Normalized
status: draft
priority: high
---

## Summary

Define the exact math that converts `Detection` pixel coordinates to YOLO normalized format, and document how image size changes (resolution setting, device rotation) are handled correctly.

## Coordinate pipeline (current app)

```
Camera sensor (YUV, e.g. 1920×1080 landscape)
  ↓ toBitmapSafe()
Bitmap bmp (1920×1080)
  ↓ rotate(imageProxy.imageInfo.rotationDegrees)  e.g. 90°
Bitmap rotated (1080×1920 — portrait-correct)
  ↓ PlateDetector.detectAll(rotated, ...)
    internally: letterbox rotated → 640×640
                run TFLite inference
                unproject coords back from letterbox space
Detection.leftPx / topPx / rightPx / bottomPx  ← in rotated pixel space
```

**Key fact:** `Detection` coordinates are already in `rotated` bitmap pixel space. The letterbox padding and scale have been removed by `PlateDetector` before returning. No additional unproject step is needed.

## Conversion to YOLO format

Given a `Detection` and the `rotated` bitmap it was detected in:

```kotlin
val imgW = rotated.width.toFloat()
val imgH = rotated.height.toFloat()

val cx = (det.leftPx + det.rightPx) / 2f / imgW
val cy = (det.topPx + det.bottomPx) / 2f / imgH
val bw = (det.rightPx - det.leftPx) / imgW
val bh = (det.bottomPx - det.topPx) / imgH

// YOLO line:
"0 ${"%.6f".format(cx)} ${"%.6f".format(cy)} ${"%.6f".format(bw)} ${"%.6f".format(bh)}"
```

The saved image must be `rotated` (not `bmp`), so that pixels and annotations share the same coordinate system.

## Image size changes and why they are safe

YOLO annotations are normalized (0–1). If the user changes the analysis resolution in Settings:

| Resolution setting | `rotated` size (portrait) | cx/cy/bw/bh values |
|---|---|---|
| Default (HAL, e.g. 1080×1920) | 1080 × 1920 | e.g. cx = 0.512 |
| Low (640×480 → rotated) | 480 × 640 | e.g. cx = 0.512 |
| HD (1280×720 → rotated) | 720 × 1280 | e.g. cx = 0.512 |

The normalized values are the same as long as the object occupies the same *relative* position in the frame. They differ only by the minor effect of differing aspect ratios:

- Default HAL and HD are both 16:9, so a plate at a given real-world position produces nearly identical normalized coordinates.
- Low (640×480 = 4:3) has a slightly different aspect ratio; the horizontal field of view may differ depending on how the camera crops. The difference is small and acceptable for training data diversity.

**The saved image and its annotation are always internally consistent** because both are derived from the same `rotated` bitmap dimensions.

## Device rotation handling

`imageProxy.imageInfo.rotationDegrees` corrects for sensor orientation. After `bmp.rotate(degrees)`, the bitmap is always in the user's viewing orientation (portrait when holding the phone portrait). The `Detection` coordinates are in this corrected space. Nothing extra is needed for rotation.

## Edge cases

| Case | Handling |
|---|---|
| Detection box partially outside image bounds | `PlateDetector` already clamps to `[0, bitmapUpright.width]` / `[0, bitmapUpright.height]`. YOLO values will be ≥ 0 and ≤ 1. |
| Degenerate box (width or height = 0) | Skip saving: `bw <= 0 || bh <= 0`. |
| `rotated` is null (conversion failed) | Frame is not saved. Only save when both `rotated` and detections are non-null/non-empty. |

## Acceptance criteria

- [ ] A saved `.txt` for a detection that spans the full width of the image has `bw ≈ 1.0`.
- [ ] Saving the same real-world plate at two different analysis resolutions produces YOLO coordinates within ±0.02 of each other.
- [ ] No saved annotation has any value outside [0.0, 1.0].
- [ ] No saved annotation has `bw = 0` or `bh = 0`.
