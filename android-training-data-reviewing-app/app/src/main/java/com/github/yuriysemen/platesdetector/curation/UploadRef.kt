package com.github.yuriysemen.platesdetector.curation

/**
 * A raw uploaded package sitting in S3 `uploads/<user_sub>/<device_id>/<filename>.zip`
 * (key format from `aws-training-infra/aws/lambda/get_upload_url/handler.py`).
 */
data class UploadRef(
    val key: String,
    val userSub: String,
    val deviceId: String,
    val filename: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
) {
    val packageId: String get() = packageIdOf(userSub, deviceId, filename)
}

/** Parses an `uploads/<sub>/<device>/<file>.zip` key; returns null for anything else. */
fun parseUploadKey(key: String, sizeBytes: Long, lastModifiedMs: Long): UploadRef? {
    val parts = key.split('/')
    if (parts.size != 4 || parts[0] != "uploads") return null
    val (_, sub, device, file) = parts
    if (sub.isBlank() || device.isBlank() || !file.endsWith(".zip", ignoreCase = true)) return null
    return UploadRef(key, sub, device, file, sizeBytes, lastModifiedMs)
}
