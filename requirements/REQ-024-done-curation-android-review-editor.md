---
id: REQ-024
title: Dataset Curation Android App — Review Editor (Accept, Edit, Reject)
status: done
priority: high
depends_on: REQ-022, REQ-023
---

> **Status — done (2026-09-07).** Move (drag) and resize (8-handle drag) of any box, plus
> pinch-zoom/pan and double-tap-to-reset, are implemented on `ReviewScreen`, alongside REQ-025's
> earlier add-box/delete/class-picker/Accept-Reject work. The geometry math
> (`BoxGeometry.hitTest`/`applyHandle`) is pure and unit-tested; the Compose gesture wiring itself
> is not (no instrumented-test harness in this project yet — same gap as REQ-023/025). See
> "Implementation notes" at the end. Acceptance criteria below are behavioural and await a full
> on-device run, same as REQ-023/025.

## Summary

Per-image review screen for a package that's In Progress: view the image with its YOLO boxes
overlaid, correct boxes (move/resize/delete/add), and mark the image **Accepted** (boxes are
correct) or **Rejected** (not a valid training example). This matches the editing fidelity of the
main app's Dataset Editor / Frame Detail screen (REQ-011/REQ-012), reimplemented natively for this
standalone project (REQ-022's "no shared module" decision). Everything here is local — no network
call happens per box edit; only the per-item decision triggers a `manifest.json` write (REQ-023).

---

## Goals

- Match the editing fidelity of the main app's proven editor (pinch/pan/zoom, drag/resize/
  delete/add box) so the curator isn't working with a degraded tool.
- Make the common case (boxes already correct) a single tap ("Accept") — most uploaded frames
  should need no editing since the on-device model already drew the boxes.
- Never silently lose a correction — every box edit is reflected in the label content saved to the
  local label file (and, on Accept, into the manifest) when the item is marked Accepted.
- Enforce the zero-box rule locally, same invariant as REQ-002's export-time rule: an item with
  zero boxes cannot be Accepted, must be Rejected instead.

## Non-goals

- The vehicle-type **class picker** and **add-box** — these live in **REQ-025** (already
  implemented on `ReviewScreen`). This requirement is precise geometry editing (move / resize /
  delete of any box, pinch-zoom/pan) layered on top.
- Pixel-level image editing (crop/rotate/brightness/contrast).
- OCR-assisted box suggestion or auto-correction.
- Server-side re-validation of the zero-box rule — there is no backend in this design (REQ-022); the
  local device is the sole authority, acceptable given the single-trusted-curator threat model.

---

## Loading items

- Opening a package (REQ-023) loads the item list from the local unzip cache and the current
  per-item status from `curation/<package_id>/manifest.json`, building the position indicator
  ("14 / 247") and jump-to-item list.
- Navigating to an item reads the image and label directly from the local unzip cache — no S3 call
  per item, since the working copy already lives on-device (REQ-022).

## Layout

- Image canvas fills most of the screen; boxes parsed from the item's label content are overlaid as
  draggable/resizable rectangles, converted from normalized `<x_center> <y_center> <width>
  <height>` to pixel space using the image's actual dimensions.
- Sidebar/footer: item position within package (e.g. "14 / 247"), subset badge (train/val/test),
  Accept / Reject / Next / Previous controls.
- Zoom and pan (pinch/scroll-wheel zoom, drag to pan) for verifying small or distant plates —
  matching REQ-012.

## Box editing operations

- **Move** — drag an existing box.
- **Resize** — drag a corner/edge handle.
- **Delete** / **Add** already exist (REQ-025); this requirement adds precise geometry editing of
  any box (plate or car).
- Edits update `working_label` in `curation/<package_id>/manifest.json` (REQ-025 already persists
  box add/delete this way, on a debounce) — an item's geometry survives an app kill mid-review.

## Accept

- Enabled only when the image has at least one box (zero-box rule) **and every box has a vehicle
  class** (REQ-025).
- On tap: writes the current box list — geometry + chosen class ids — as YOLO `.txt` content into
  the local unzip cache's label file, updates the item's manifest entry to `{status: "accepted",
  label_content: "<...>"}`, refreshes package progress counts, and advances to the next `pending`
  item.
- If the curator deletes every box, Accept is disabled and a hint is shown — the image must be
  Rejected instead.

## Reject

- Available at any time, regardless of box count, with an optional free-text reason.
- On tap: updates the item's entry in `manifest.json` to `{status: "rejected", reason: "<text>"}`
  (current box edits, if any, are discarded — a rejected item never contributes to `done/`),
  refreshes progress counts, and advances to the next `pending` item.

## Navigation

- **Next / Previous** step through items in the package's original order.
- Already-decided items (`accepted`/`rejected`) are visible via a "jump to item" list but are
  **read-only** in v1 — to change a decision, the whole package must be Released (REQ-023) and
  restarted.
- Navigating away from a still-`pending` item **keeps** its in-progress box edits — geometry and
  class picks live in `working_label`/`box_classes` on `curation/<package_id>/manifest.json`
  (REQ-025's debounced persistence, which move/resize reuses), not in transient screen state.
  Returning to the item later (even after an app kill) shows the same edited boxes; its manifest
  `status` stays `pending` either way. This supersedes this doc's original "edits are discarded on
  navigate" design — REQ-025 changed that before REQ-024 was built.

## No heartbeat

There is no heartbeat call — this design has no server-side claim-expiry concept (REQ-022/REQ-023
have no claim/lock marker at all in the single-curator model), so there is nothing that needs to be
kept alive by periodic pings.

---

## Implementation notes

All in `curation-android/app/src/main/java/.../curation/`:

- **`BoxGeometry.kt`** (new, pure Kotlin, no Compose runtime dependency beyond the `Offset` value
  type — unit-tested via `BoxGeometryTest`): `DragHandle` (`MOVE`/4 corners/4 edges), `hitTest`
  (selected box's handles first, then topmost box body, else `null` — all in normalized `[0,1]`
  box space with a per-axis handle radius, since a non-square image maps 1 normalized unit to a
  different pixel count per axis), `applyHandle` (shift one handle by a normalized delta, clamped
  to `[0,1]` and never below `MIN_SIZE = 0.01f`, matching `toNormalizedBox`'s add-box threshold;
  guards a box that's already narrower than `MIN_SIZE` so resize never throws).
- **`CurationViewModel.moveBox(index, updated)`** — same shape as `setBoxClass`/`addBox`/
  `deleteBox`: replaces one box's geometry, re-serializes via `YoloLabel.format`, writes through
  `manifest.withItemBoxes`, debounced save. No new manifest fields.
- **`ReviewScreen`** — the `Canvas`'s pointer handling is four always-attached `pointerInput`
  blocks that each no-op unless their mode is active (mirrors `android/.../FrameDetailScreen.kt`'s
  chaining): add-box drag (REQ-025, unchanged), double-tap-to-reset-zoom, move/resize drag (hit-
  tests via `BoxGeometry`, live-previews the dragged box in local state, commits once via
  `moveBox` on drag end so the manifest gets one write per gesture, not per frame), and an
  always-active two-finger pinch-zoom/pan (`awaitEachGesture` + `calculateCentroid`/`calculateZoom`/
  `calculatePan`, ported from the same reference screen). Drawing wraps the image + boxes in a
  `withTransform { translate(pan); scale(zoom) }`; the selected box's 8 handles are drawn at a
  screen-constant size (`radius / zoomScale`). Pinch-zoom/pan stays active on a decided (read-only)
  item so a curator can still inspect it; move/resize and add-box don't.

Verified: `BoxGeometryTest` (10 cases) passes; `:app:compileDebugKotlin`, `:app:testDebugUnitTest`,
and `:app:assembleDebug` all succeed. Real touch-gesture feel (drag/pinch/pan on a device) has not
been checked — same gap as REQ-023/025's on-device criteria below.

---

## Acceptance criteria

- [ ] Opening an in-progress package's editor loads the first `pending` item and renders its
      existing boxes correctly converted from YOLO normalized coordinates.
- [ ] Dragging a box updates its position; resizing updates its dimensions; both are reflected in
      the label content saved on Accept.
- [ ] Deleting a box removes it from the label content saved on Accept.
- [ ] Adding a box produces a new correctly-normalized line in the label content saved on Accept.
- [ ] "Accept" is disabled when zero boxes remain on the image.
- [ ] "Reject" works regardless of box count and accepts an optional reason.
- [ ] Marking Accept or Reject immediately updates `curation/<package_id>/manifest.json` and the
      progress shown on the In Progress screen (REQ-023).
- [ ] Zoom and pan work via both touch gestures.
- [ ] Editing one item does not affect any other item's files or manifest entry.
- [ ] Navigating away from a pending item without marking Accept/Reject **keeps** its in-progress
      box edits (geometry + classes) — returning to the item, even after an app kill, shows the
      same edited boxes; its manifest `status` stays `pending`.
- [ ] Already-decided items are viewable via the jump list but their boxes cannot be edited in v1.
