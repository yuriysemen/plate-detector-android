package com.github.yuriysemen.platesdetector.curation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Current time as an ISO-8601 UTC string (`2026-08-30T14:03:21Z`). */
fun nowIso(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())

object Format {
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun dateTime(epochMs: Long): String =
        if (epochMs <= 0) "—" else dateFmt.format(Date(epochMs))

    /** Trims an ISO-8601 timestamp to `yyyy-MM-dd HH:mm` for display. */
    fun iso(s: String): String =
        s.replace('T', ' ').removeSuffix("Z").take(16).ifBlank { "—" }

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    fun shortSub(sub: String): String = if (sub.length > 8) sub.take(8) + "…" else sub
}
