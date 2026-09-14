---
id: REQ-036
title: Curation — View a Done Package's Images
status: draft
priority: medium
---

## Summary

The Done tab (`PackageTabs.kt`'s `DoneTab`) today shows only a summary card per completed
package — filename, completion date, and accept/reject counts (`DoneManifest`). There's no way to
actually look at what got curated into a package's `done/` output. This requirement adds a
**read-only viewer** for a Done package's images (and their final labels), reachable by tapping
its card.

**Scope: `done/` only** (the accepted-items archive), not `rejected/` — matches what you asked
for. Viewing rejected items later would be a natural, similarly-shaped extension, not part of
this.

## Why this needs its own design (not just "reuse ReviewScreen")

`ReviewScreen` is built around an editable, per-item decision workflow (Accept/Reject/Change
decision, box add/move/delete) operating on the **local working cache** of an in-progress
package. A Done package has none of that: it's already finalized, its content now lives only in
the `done/<sub>/<device>/<stem>-curated-<ts>.zip` archive (REQ-035), not in `filesDir/packages/`
(deleted on Complete), and there's nothing left to decide. So this needs a separate, genuinely
read-only screen — no Accept/Reject/box-editing affordances at all — that downloads and displays
from the archive instead of the working cache.

## Proposed design

- **Entry point:** tapping a `DoneTab` card opens a new `DoneViewerScreen` for that package.
- **Data source:** `DoneManifest.doneZipKey` (added in REQ-035) — the exact S3 key of that
  package's accepted-items archive. On open, download it to a temp cache dir and unzip it (the
  same `ZipInputStream` + zip-slip-guard approach `PackageCache.unzip()` already uses).
- **Display:** a scrollable list/grid of the unzipped images, grouped or labeled by subset
  (train/val/test), each optionally showing its final YOLO boxes overlaid (read-only — reusing the
  box-drawing logic from `ReviewScreen`'s `Canvas`, minus every interactive `pointerInput` block).
  Tapping a thumbnail opens it larger with the overlay, dismissible back to the grid.
- **Lazy decoding:** decode each image bitmap only as it's actually shown (thumbnail grid item
  becomes visible / full view opened), not all upfront — a package can have hundreds of images,
  and decoding every one into memory at once the way this screen might be tempted to isn't
  necessary just to browse.
- **Cleanup:** delete the temp download+unzip when the viewer closes. Nothing about viewing a Done
  package should leave a lasting local copy — matches the "no local trace" pattern already used
  elsewhere in this app and in `training-android`.
- **A fully-rejected package has no images to view.** If `doneZipKey` is null (nothing was
  accepted), the viewer should say so plainly rather than trying to download a zip that doesn't
  exist.
- **Backward compatibility — packages completed before REQ-035.** Those have no `doneZipKey` at
  all (the field didn't exist yet) and their images live loose under the pre-REQ-035
  `done/<packageId>/<subset>/{images,labels}/` shape instead of one archive. The viewer needs a
  fallback path for this: when `doneZipKey` is null but the package predates REQ-035 (distinguish
  from "nothing accepted" by checking `accepted > 0`), list objects directly under
  `done/<packageId>/` via S3 `ListObjectsV2` and download/display those instead of a zip.

## Open questions (need your input before implementation)

- [ ] **Grid of thumbnails, or a simple scrollable list of full-width images?** A grid gets you
      browsing more images at once; a list is simpler to build and matches `ReviewScreen`'s
      single-image-at-a-time mental model more closely. My instinct is a grid (this is a "browse
      what's in here" use case, not a "review one at a time" one), but happy to go either way.
- [ ] **Show the box overlay by default, or only on demand (e.g. a toggle)?** Showing it always is
      simpler and more directly useful for QA ("does this final label look right"), but adds a
      bit of visual noise if someone just wants to skim the photos themselves.
- [ ] **Any per-image action beyond viewing** — e.g. a way to flag "this shouldn't have been
      accepted" from inside the viewer, which would presumably need to feed back into REQ-034's
      change-decision flow somehow? Flagging this as a real possibility but treating it as
      out of scope unless you want it folded in here — REQ-034 already only supports changing a
      decision on an **In Progress** package, not a completed one, so wiring this up would be a
      third piece of work of its own.

## Acceptance criteria (draft — pending the above)

- [ ] Tapping a `DoneTab` card opens a read-only viewer showing that package's accepted images.
- [ ] Images are grouped/labeled by subset (train/val/test).
- [ ] Final YOLO boxes are visible per image (per the Q2 decision above).
- [ ] A fully-rejected package (`doneZipKey == null`, `accepted == 0`) shows a clear "nothing was
      accepted into this package" message instead of attempting a download.
- [ ] A pre-REQ-035 completed package (`doneZipKey == null`, `accepted > 0`) still works, via the
      old flat-path fallback.
- [ ] Closing the viewer leaves no local trace — the temp download/unzip is deleted.
- [ ] No Accept/Reject/box-editing affordance appears anywhere in this screen.
