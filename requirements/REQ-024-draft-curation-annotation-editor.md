---
id: REQ-024
title: Dataset Curation — Annotation Editor (Verify, Edit, Reject)
status: draft
priority: high
depends_on: REQ-022, REQ-023
---

## Summary

Per-image review UI inside a claimed package: view the image with its YOLO boxes overlaid, correct
boxes (move/resize/delete/add), and either mark the image **ready** (boxes are now correct) or
**rejected** (not a valid training example). This implements the bounding-box correction and
false-positive flagging that REQ-001 deferred to "a separate editing app." All reads and writes go
through the Lambda-mediated item routes defined in REQ-022 — the browser never has S3 credentials.

---

## Goals

- Match the editing fidelity of the Android app's Dataset Editor (REQ-011) and Frame Detail
  zoom/pan editor (REQ-012), so curators aren't working with a degraded tool compared to what's
  already proven in the app.
- Make the common case (boxes are already correct) a single click ("Ready") — most uploaded frames
  should need no editing since the on-device model already drew the boxes.
- Never silently lose a correction — every box edit is reflected in the `.txt` label written when
  the item is marked ready.
- Let the Lambda be the final authority on whether a decision is allowed (ownership, claim not
  expired, item still pending) — the editor's UI state is a convenience, not the source of truth.

## Non-goals

- Multi-class editing — the dataset has a single class (`License_Plate`); no class picker needed.
- Pixel-level image editing (crop/rotate/brightness/contrast).
- OCR-assisted box suggestion or auto-correction.

---

## Loading items

- On opening a package, the app calls `GET /curation/packages/{package_id}/items`, returning the
  manifest's item list (`subset`, `basename`, `status`, `reason` if rejected) for building the
  position indicator ("14 / 247") and the jump-to-item list. No image data is fetched yet.
- Navigating to a specific item calls
  `GET /curation/packages/{package_id}/items/{subset}/{basename}`, which the Lambda answers with:
  - A **pre-signed S3 GET URL** for the image, scoped to that exact object and short-lived (e.g.
    5 minutes) — generated server-side per request, not a standing grant.
  - The current label content (YOLO `.txt` text, parsed client-side into boxes).
  - The item's `subset` and current `status`.
- The Lambda verifies the caller's `curator_sub` matches the claim's `claimed_by` and that the
  claim has not expired before returning anything (HTTP 403/410 otherwise).

## Layout

- Image canvas fills most of the screen; boxes parsed from the returned label content are overlaid
  as draggable/resizable rectangles, converted from normalized `<x_center> <y_center> <width>
  <height>` to pixel space using the image's actual dimensions.
- Sidebar/footer: item position within package (e.g. "14 / 247"), subset badge
  (train/val/test), Ready / Reject / Next / Previous controls.
- Zoom and pan (matching REQ-012: pinch/scroll-wheel zoom, drag to pan) for verifying small or
  distant plates.

## Box editing operations

- **Move** — drag an existing box.
- **Resize** — drag a corner/edge handle.
- **Delete** — remove a box that doesn't correspond to a real plate (click box + delete key, or an
  "×" handle on the box).
- **Add** — draw a new rectangle for a plate the on-device model missed.
- Edits are held in local component state until the item is marked Ready; navigating away without
  marking Ready discards edits for that item (no autosave of in-progress edits — only committed on
  Ready/Reject, sent in the decision request body).

## Mark Ready

- Enabled whenever the image has at least one box (see the zero-box rule below).
- On click: the app calls
  `POST /curation/packages/{package_id}/items/{subset}/{basename}/decision` with
  `{decision: "ready", label_content: "<current box list as YOLO .txt text>"}`.
- The Lambda re-validates the zero-box rule server-side (rejects with HTTP 400 if `label_content`
  has zero lines — the frontend disabling the button is a UX nicety, not the enforcement), then
  writes the label content and moves the image+label pair from the package's pending location to
  `processing/<curator_sub>/<package_id>/reviewed/<subset>/...` (per REQ-023), updates
  `manifest.json` status to `reviewed`, refreshes `last_activity_at`, and returns updated progress
  counts. The app advances to the next pending item.
- **Zero-box rule:** if a curator deletes every box, the image cannot be marked Ready — it must be
  marked Rejected instead. This mirrors the on-device invariant that a frame with no detections is
  never saved (REQ-002), keeping exported packages consistent with what the training pipeline
  already expects. The UI disables "Ready" and shows a hint when the box count is 0; the Lambda
  enforces the same rule independently.

## Mark Rejected

- Available at any time, with an optional free-text reason.
- On click: the app calls the same decision route with
  `{decision: "rejected", label_content: "<current box list, if any>", reason: "<text>"}`.
- The Lambda moves the image+label pair (current edited state, if any) to
  `processing/<curator_sub>/<package_id>/rejected/<subset>/...`, updates `manifest.json` status to
  `rejected` with the reason, refreshes `last_activity_at`, and returns updated progress counts.
  The app advances to the next pending item.

## Navigation

- **Next / Previous** step through items in the package in their original order.
- Already-reviewed/rejected items can be revisited via a "jump to item" list (e.g. to confirm what
  was rejected and why), but — see open decision 1 below — are **read-only** once decided in v1
  (the decision route only accepts items whose server-side status is still `pending`; re-submitting
  a decision for an already-decided item returns HTTP 409).

## Heartbeat

While an item is open in the editor, the app calls `POST /curation/packages/{package_id}/heartbeat`
roughly every 5 minutes (REQ-023) to keep the claim alive during long single-image reviews. This is
independent of marking items ready/rejected, which also refreshes activity.

## Claim expiry while editing

Claims expire after 24 hours of inactivity and can then be reclaimed by another curator (REQ-023).
If a decision call fails with HTTP 410 because the claim is no longer the caller's (it expired and
was taken by someone else), show "This package's claim expired and was released to another curator
— your unsaved progress was discarded" and return to the In Progress screen rather than retrying
silently.

---

## Open decisions / assumptions to confirm

1. **Can a curator change a decision** (Ready→Rejected or vice versa) for an item already moved
   out of the pending location, before the package is completed? The decision route as specified
   only accepts `pending` items, so changing a decision would need a new "undo" route that moves a
   file back to pending server-side. Proposed default: **not editable after the decision** in v1 —
   if a mistake is made, the whole package must be Released (REQ-023) and re-claimed from scratch.
   Confirm if this is acceptable, or if an undo route is needed for v1.
2. Exact JS library for the canvas/box editor (custom canvas vs. a library like `konva` or
   `fabric.js`) — implementation detail, not blocking requirements sign-off.
3. Keyboard shortcuts (Android's touch gestures don't port directly to a desktop browser) —
   proposed: arrow keys = next/prev, `R` = ready, `X` = reject, `Delete` = remove selected box.
   Confirm or adjust.

---

## Acceptance criteria

- [ ] Opening an in-progress package's editor loads the first `pending` item via the items API and
      renders its existing boxes correctly converted from YOLO normalized coordinates.
- [ ] The image is fetched via a Lambda-issued pre-signed URL scoped to that single object, not via
      any standing browser S3 permission.
- [ ] Dragging a box updates its position; resizing updates its dimensions; both are reflected in
      the `label_content` sent on Ready.
- [ ] Deleting a box removes it from the `label_content` sent on Ready.
- [ ] Adding a box produces a new correctly-normalized line in the `label_content` sent on Ready.
- [ ] "Ready" is disabled client-side when zero boxes remain on the image, **and** the Lambda
      independently rejects a zero-box Ready request with HTTP 400.
- [ ] "Reject" works regardless of box count and accepts an optional reason.
- [ ] Marking Ready or Reject immediately moves the file pair server-side (REQ-023) and updates
      progress shown on the In Progress screen.
- [ ] A decision request for an item whose server-side status is no longer `pending` is rejected
      with HTTP 409.
- [ ] A decision or item-get request from a curator who is not the claim's owner is rejected with
      HTTP 403.
- [ ] Zoom and pan work via both mouse/trackpad and touch.
- [ ] Editing one item does not affect any other item's files.
- [ ] The editor sends a heartbeat roughly every 5 minutes while open and focused.