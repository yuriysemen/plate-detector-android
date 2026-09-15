package com.github.yuriysemen.platesdetector.curation

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * `package_id` = sanitized `<user_sub>__<device_id>__<filename-without-zip>`, deterministic from
 * the source ZIP's S3 key (REQ-022). It always traces a curated package back to its source ZIP
 * and device, and is safe to use as an S3 key segment.
 */
fun packageIdOf(userSub: String, deviceId: String, filename: String): String {
    val stem = filename.removeSuffix(".zip").removeSuffix(".ZIP")
    return sanitizePackageId("${userSub}__${deviceId}__${stem}")
}

private val UNSAFE = Regex("[^A-Za-z0-9._-]")

fun sanitizePackageId(raw: String): String = UNSAFE.replace(raw, "_")

// ── REQ-035: completed-package archive naming ───────────────────────────────
// done/ and rejected/ mirror uploads/<sub>/<device>/<filename>.zip's nested path shape, rather
// than the flat packageId used for curation/ working state and the local cache.

private val COMPLETION_TS_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

/** UTC timestamp in the same `<YYYYMMDD>_<HHmmss>` shape used for frame filenames elsewhere in
 *  this codebase, for the `-curated-`/`-rejected-` suffix. */
fun completionTimestamp(epochMs: Long = System.currentTimeMillis()): String =
    COMPLETION_TS_FORMAT.format(epochMs)

private fun filenameStem(filename: String): String =
    filename.removeSuffix(".zip").removeSuffix(".ZIP")

/** `done/<sub>/<device>/<stem>-curated-<ts>` — the shared base path (no extension) for a
 *  completed package's accepted-items archive and its `_manifest.json` sidecar. */
fun doneBaseKey(userSub: String, deviceId: String, filename: String, timestamp: String): String =
    "done/$userSub/$deviceId/${filenameStem(filename)}-curated-$timestamp"

/** `rejected/<sub>/<device>/<stem>-rejected-<ts>` — same idea, for the rejected-items archive
 *  (no manifest sidecar — nothing lists `rejected/`). */
fun rejectedBaseKey(userSub: String, deviceId: String, filename: String, timestamp: String): String =
    "rejected/$userSub/$deviceId/${filenameStem(filename)}-rejected-$timestamp"

private val DONE_MANIFEST_LEAF = Regex("""^(.*)-curated-\d{8}_\d{6}$""")

/**
 * Recovers the original `packageId` from a `done/<sub>/<device>/<stem>-curated-<ts>._manifest.json`
 * key, so [CurationRepository.listNotProcessed] can tell an already-completed upload apart from a
 * fresh one without needing to fetch and parse the manifest itself. Returns null for anything that
 * doesn't match this shape (e.g. a pre-REQ-035 flat `done/<packageId>/_manifest.json` key — that
 * scheme is handled separately, as a fallback, for backward compatibility with packages completed
 * before this shipped).
 */
fun packageIdFromDoneManifestKey(key: String): String? {
    val withoutPrefix = key.removePrefix("done/")
    if (withoutPrefix == key) return null
    val parts = withoutPrefix.split('/')
    if (parts.size != 3) return null
    val (sub, device, leaf) = parts
    val base = leaf.removeSuffix("._manifest.json")
    if (base == leaf) return null
    val stem = DONE_MANIFEST_LEAF.matchEntire(base)?.groupValues?.get(1) ?: return null
    return packageIdOf(sub, device, "$stem.zip")
}
