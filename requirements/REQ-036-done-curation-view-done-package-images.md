---
id: REQ-036
title: Curation — View a Done Package's Images
status: done
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
  elsewhere in this app and in `android-training-data-collection-app`.
- **A fully-rejected package has no images to view.** If `doneZipKey` is null (nothing was
  accepted), the viewer should say so plainly rather than trying to download a zip that doesn't
  exist.
- **Backward compatibility — packages completed before REQ-035.** Those have no `doneZipKey` at
  all (the field didn't exist yet) and their images live loose under the pre-REQ-035
  `done/<packageId>/<subset>/{images,labels}/` shape instead of one archive. The viewer needs a
  fallback path for this: when `doneZipKey` is null but the package predates REQ-035 (distinguish
  from "nothing accepted" by checking `accepted > 0`), list objects directly under
  `done/<packageId>/` via S3 `ListObjectsV2` and download/display those instead of a zip.

## Resolved questions

Implemented using my own recommendations (no explicit answers given before implementation):

- **Q1 (grid vs. list):** grid — a 3-column `LazyVerticalGrid`, grouped by subset with a header
  row per subset (`item(span = { GridItemSpan(maxLineSpan) })`).
- **Q2 (box overlay always vs. toggle):** always shown, in the full-size dialog opened by tapping
  a thumbnail (not on the thumbnail itself, to keep the grid legible at small size).
- **Q3 (any action beyond viewing):** none — purely read-only, as originally scoped. No
  flag/change-decision affordance from this screen.

## Implementation notes

- **Thumbnails are downsampled**, not decoded at full resolution — `BitmapFactory.Options
  .inSampleSize`, targeting ~200px on the long side, computed per image from `inJustDecodeBounds`.
  A package can have hundreds of images; decoding every one at full camera resolution just to show
  a grid would be wasteful and risks memory pressure. The full-size dialog decodes at full
  resolution, since only one image is ever open at a time there.
- **Lazy by construction**: `LazyVerticalGrid` doesn't compose off-screen items, so each
  thumbnail's own `LaunchedEffect(image.imageFile) { ... }` only fires when that cell actually
  becomes visible — no separate "visible items" tracking needed.
- **Geometry reuse**: `ReviewScreen`'s `fitRect()` (letterbox-fit an image inside a canvas) was
  made non-private so `DoneViewerScreen` could reuse it for the full-size dialog's box overlay
  instead of duplicating that math.
- **Zip-slip-guarded unzip reuse**: extracted the entry-copying loop out of `PackageCache.unzip()`
  into a shared top-level `unzipInto(zip, targetDir)` (`PackageCache.kt`), used by both the
  existing working-copy unzip and the new Done-viewer's temp-dir unzip.
- **Temp storage**: downloads/unzips into `filesDir/cache/done_view/<packageId>-<timestamp>/` —
  deliberately separate from `filesDir/packages/` (the working-copy cache), since this isn't a
  working copy and has different lifecycle/cleanup rules (deleted on viewer close, always).

## Acceptance criteria

- [x] Tapping a `DoneTab` card opens a read-only viewer showing that package's accepted images.
- [x] Images are grouped/labeled by subset (train/val/test).
- [x] Final YOLO boxes are visible per image (in the full-size dialog).
- [x] A fully-rejected package (`doneZipKey == null`, `accepted == 0`) shows a clear "nothing was
      accepted into this package" message instead of attempting a download.
- [x] A pre-REQ-035 completed package (`doneZipKey == null`, `accepted > 0`) still works, via the
      old flat-path fallback.
- [x] Closing the viewer leaves no local trace — the temp download/unzip is deleted.
- [x] No Accept/Reject/box-editing affordance appears anywhere in this screen.
