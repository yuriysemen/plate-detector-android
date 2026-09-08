---
id: REQ-012
title: Zoom and Pan in Frame Detail Editor
status: superseded
superseded_by: REQ-026
priority: high
---

## Summary

Add pinch-to-zoom and pan support to the **Frame Detail screen** (REQ-011) so that small license plates can be magnified before placing or adjusting bounding boxes. Without zoom, boxes drawn on a distant or small plate occupy only a few pixels on screen, making precise handle placement nearly impossible.

---

## Problem statement

The Frame Detail screen displays the full-res JPEG fit-to-screen. On a typical phone, a license plate detected 10–20 m away may span 40–80 px on screen. The drag handles (14 dp circles ≈ 40–56 px at normal DPI) overlap or exceed the plate area entirely, making it impossible to position a box precisely. Zooming into the plate region before editing resolves this.

---

## Scope

This requirement covers only the **Frame Detail screen**. The Dataset Editor grid (thumbnail view) is not affected.

---

## Gesture model

### Pinch-to-zoom (two fingers)

- A standard pinch gesture (two pointers) zooms in or out, pivoting around the midpoint of the two touch points.
- The same two-finger gesture simultaneously pans the view (standard "transform" gesture semantics, identical to most image-viewer apps).
- Pinch detection is layered on top of the existing single-finger gestures; they do not conflict because the existing interactions use only one pointer at a time.

### Zoom bounds

| Bound        | Value                                          |
|--------------|------------------------------------------------|
| Minimum zoom | 1× (fit-center — same as the current baseline) |
| Maximum zoom | 8× the fit-center scale                        |

The minimum bound prevents zooming out past the fit-center baseline (the image cannot become smaller than it currently is).

### Pan bounds

When zoomed, the image can be panned freely. Panning must be clamped so the user cannot pan the image fully off-screen: at least one quarter of the image must remain visible at all times.

### Double-tap to reset

Double-tapping anywhere on the canvas resets zoom to 1× (fit-center) and pan to (0, 0). This is a fast escape if the user gets lost. A brief `"Zoom reset"` snackbar or no feedback — implementation choice.

### Single-finger interactions unchanged

Box selection (tap), box move (drag body), handle drag (drag corner/edge), and new-box draw (drag in "Add box" mode) all continue to use single-finger gestures. Their semantics are unchanged; only their coordinate math must account for the current zoom and pan transform.

---

## Coordinate transform

All existing gesture coordinates (tap position, drag delta, handle hit-test positions) are in screen space. When zoom/pan is applied, a screen-space point maps to image-canvas space via:

```
canvasX = (screenX - panOffsetX) / zoomScale
canvasY = (screenY - panOffsetY) / zoomScale
```

where `zoomScale` and `panOffset` are the current zoom/pan state. Drag deltas scale as:

```
canvasDeltaX = screenDeltaX / zoomScale
canvasDeltaY = screenDeltaY / zoomScale
```

Box positions continue to be stored in unzoomed canvas-pixel space (same as REQ-011). Rendering transforms canvas coordinates to screen coordinates:

```
screenX = canvasX * zoomScale + panOffsetX
screenY = canvasY * zoomScale + panOffsetY
```

The `panOffset` must be updated whenever `zoomScale` changes (e.g., during a pinch) to keep the zoom pivot at the pinch midpoint.

### Handle radius

Handle circles remain **14 dp in screen space** regardless of zoom level. In canvas space the effective hit radius therefore shrinks as zoom increases (`14.dp.toPx() / zoomScale`), but the visible circle on screen stays the same size — the plate region expands around it, making handles easier to target.

### New-box drawing

When drawing a new box (FAB "Add box" mode), the start and end drag coordinates are captured in screen space and inverse-transformed to canvas space using the same formula above before creating the `DisplayBox`.

---

## Zoom state lifecycle

- Zoom and pan state reset to 1×/(0,0) each time a new frame is opened (not persisted across navigation).
- Zoom state does not affect what is saved — saving always re-normalises box coordinates relative to the unzoomed image dimensions.
- Rotation of the device (config change) resets zoom to 1× — Activity recreation causes all `remember` state (including `zoomScale`) to revert to initial values automatically. No explicit reset in `SideEffect` is needed or present; the `SideEffect` only re-projects box canvas coordinates.

---

## Dataset editor scroll position

When the user navigates back from Frame Detail to the Dataset Editor grid (via save, discard, or back press), the grid must restore the scroll position it had when the frame was opened. The user should return to exactly the row they tapped from, not to the top of the list.

Implementation note: the `LazyGridState` must be hoisted to a scope that survives the frame-detail navigation (i.e., declared before any early `return` in `DatasetEditorScreen`) so the scroll offset is retained in memory across the navigation.

---

## Zoom level indicator

A small semi-transparent label (e.g. `"2.5×"`) is displayed in the bottom-left corner of the image canvas when zoom > 1×. The label fades out after 1.5 s of no zoom-change activity. It disappears entirely at 1× zoom.

---

## Interaction with existing modes

| Mode                     | Single-finger gesture                          | Two-finger gesture                                     |
|--------------------------|------------------------------------------------|--------------------------------------------------------|
| Normal (no box selected) | Select box (tap) / start drawing (in add mode) | Zoom + pan                                             |
| Box selected             | Move box (drag body) / resize (drag handle)    | Zoom + pan                                             |
| Add-box draw mode        | Draw new box (drag)                            | Ignored (cancel draw and zoom is acceptable; see note) |

> **Note on add-box + pinch conflict:** if the user accidentally starts a new-box drag and then adds a second finger, the recommended behaviour is to cancel the in-progress box draw and hand control to zoom/pan. This avoids creating an incorrectly sized box.

---

## Performance constraints

| Operation                             | Target   |
|---------------------------------------|----------|
| Zoom/pan frame rate (no box drag)     | ≥ 60 fps |
| Zoom/pan frame rate (box drag active) | ≥ 30 fps |
| First render after double-tap reset   | < 100 ms |

The image bitmap is already fully decoded in memory (loaded by the existing `LaunchedEffect`). Zoom/pan is a pure transform on the draw call — no re-decode is required, so hitting these targets should be straightforward.

---

## Acceptance criteria

### Zoom and pan

- [x] Pinch gesture zooms the image in and out, pivoting at the pinch midpoint.
- [x] Zoom range is clamped to [1×, 8×] fit-center scale.
- [x] Panning is possible while zoomed; image cannot be panned fully off-screen.
- [x] Double-tap resets zoom to 1× and pan to origin.
- [x] A zoom level label (e.g. `"2.5×"`) is visible during zoom activity and fades out after 1.5 s of inactivity.

### Box editing while zoomed

- [x] Tapping a box while zoomed selects it.
- [x] Dragging a box body while zoomed moves the box correctly (drag delta is scaled by 1/zoomScale).
- [x] Dragging a corner or edge handle while zoomed resizes the box correctly.
- [x] Handle circles remain 14 dp in screen size regardless of zoom level.
- [x] Hit-test radius for handles scales inversely with zoom so the tap target is always ≈ 14 dp in screen space.
- [x] Drawing a new box (Add box mode) while zoomed creates a box at the correct normalized image coordinates.
- [x] Box constraints (cannot drag outside image bounds) remain correct while zoomed.

### Coordinate correctness

- [x] Save after editing while zoomed writes the same YOLO coordinates as editing at 1× zoom for the same visual box position (within ±1 px of the unzoomed equivalent).

### Canvas boundary

- [x] Zoomed content (image, boxes, handles) is clipped to the canvas area and does not overflow into the top bar or FAB row controls at any zoom level.

### Box creation

- [x] A draw gesture shorter than the minimum size in either dimension snaps the new box to the minimum size centred on the drag midpoint rather than being silently discarded.
- [x] Minimum box height is 25% smaller than minimum box width (`minBoxH = minBoxW × 0.75`), allowing narrow landscape boxes matching the typical license plate aspect ratio.

### Lifecycle

- [x] Zoom resets to 1× when opening a different frame.
- [x] Zoom state does not affect saved coordinates.

### Navigation

- [x] Returning from Frame Detail to the Dataset Editor grid restores the scroll position to the row the frame was opened from.

---

## Implementation notes

- **Rendering**: `withTransform { translate(panOffsetX, panOffsetY); scale(zoomScale) }` wraps all Canvas draw calls. The Canvas carries `Modifier.clipToBounds()` so zoomed content cannot overflow into the top bar or FAB row.
- **Handle size**: drawn at `handleRadius / zoomScale` in canvas space so they appear as a constant 14 dp on screen at any zoom level.
- **Gesture**: a custom `awaitEachGesture` loop (not `detectTransformGestures`) polls until ≥ 2 fingers appear within the same touch sequence, then reads `PointerEvent.calculateZoom/Pan/Centroid` from Compose foundations.
- **Box creation**: if a draw gesture is shorter than `minBoxSize × minBoxH`, the box is snapped to the minimum dimensions centred on the drag midpoint rather than being discarded. `minBoxH = minBoxSize × 0.75` (height minimum is 25% smaller than width minimum, matching the wide aspect ratio of license plates).
- **Zoom reset bug fix**: zoom was incorrectly resetting when a newly added box became selected (which resized the FAB row, triggering the `SideEffect` geometry check). Fixed by removing the zoom reset from `SideEffect`; rotation-induced reset is handled implicitly by Activity recreation.
- **DatasetEditorScreen**: `LazyGridState` is declared before the early `return` that shows `FrameDetailScreen`, so the grid scroll position survives the navigation. Thumbnail boxes are drawn using the same four-colour cycle as the detail editor.
