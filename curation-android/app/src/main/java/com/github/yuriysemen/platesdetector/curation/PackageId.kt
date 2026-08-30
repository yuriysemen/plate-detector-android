package com.github.yuriysemen.platesdetector.curation

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
