---
id: REQ-042
title: Collection App — Restrict Captured Frames to 640×480 and 1280×720
status: done
priority: medium
depends_on: REQ-040
---

## Summary

The collection app now produces frames of exactly two sizes: **640×480** (4:3) and **1280×720**
(16:9), in either orientation. The "Default — camera driver decides" analysis-resolution option is
removed, HD is pinned to 16:9, and every uploaded package records which sizes it contains.

## Motivation

Only administrators organize new datasets (see the root README's *Who does what*). To do that they
need to know what a package contains. Before this change the saved frame size was:

- **Device-dependent** under `DEFAULT` (whatever the camera HAL returned).
- **Not what the picker promised** under `HD`. The code passed only a bound size to CameraX
  (`ResolutionStrategy`); CameraX's default aspect-ratio strategy prefers 4:3, so a "1280×720"
  request would resolve to a 4:3 size such as 1280×960 on many devices. (Derived from CameraX
  defaults, not observed on a device — verify with the `frame_sizes` field below.)
- **Unrecorded.** Frames are saved as the raw rotated analysis bitmap with no size in the package.

The dataset spec also said "640×640", which no captured frame ever was.

## Design

- **`AnalysisResolution`** is now `LOW` (640×480) and `HD` (1280×720) only, each carrying its
  width/height. `isAllowedFrameSize(w, h)` accepts either orientation of either size.
- **Migration:** a stored `"DEFAULT"` preference no longer parses; `ModelPrefs` falls back to `LOW`.
  New installs also default to `LOW`.
- **Aspect ratio is pinned** together with the bound size: `LOW` uses
  `RATIO_4_3_FALLBACK_AUTO_STRATEGY`, `HD` uses `RATIO_16_9_FALLBACK_AUTO_STRATEGY`. `Preview` gets
  the same aspect strategy, because the detection overlay is mapped onto the preview with
  FIT_CENTER assuming both share the analysis frame's aspect.
- **Fallback rule unchanged** (`CLOSEST_HIGHER_THEN_LOWER`). A device that cannot deliver the exact
  size is not blocked from capturing: the frame is kept, a warning is logged, and its true size is
  recorded rather than silently coerced.
- **`data.yaml`** gains `device.frame_sizes`, a map of `"WxH"` → frame count, measured from the JPEG
  headers at export time (so it reflects the files in the ZIP, not in-memory counters):

  ```yaml
  device:
    frame_sizes:
      "480x640": 12
      "640x480": 88
  ```

  Packages produced before this change have no `frame_sizes` key.

## Out of scope

- No resizing or rejection of off-spec frames. An admin can filter on `frame_sizes` when assembling a
  dataset.
- `android-end-user-app/` is untouched — it keeps its own `DEFAULT`/`LOW`/`HD` picker; nothing it
  captures leaves the device.
- The reviewing app doesn't read frame size (labels are normalized).

## Acceptance criteria

- [x] Settings offers exactly Low (640×480) and HD (1280×720).
- [x] A stored `DEFAULT` preference resolves to `LOW`.
- [x] Camera bind pins aspect ratio (4:3 / 16:9) on both `ImageAnalysis` and `Preview`.
- [x] `data.yaml` lists `frame_sizes` when the package has images.
- [x] Unit tests: `Req042Test`.
- [ ] On-device: confirm HD frames are 1280×720 (or 720×1280) and the overlay still lines up with the
      preview at both settings. **Not yet run on a device.**
