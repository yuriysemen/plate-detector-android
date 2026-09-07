package com.github.yuriysemen.platesdetector.curation

import org.json.JSONArray
import org.json.JSONObject

enum class ItemStatus { PENDING, ACCEPTED, REJECTED }

data class ManifestItem(
    val subset: String,        // train | val | test
    val basename: String,      // e.g. 20260101_120000_000001
    val imageName: String,     // basename + extension, e.g. ...000001.jpg
    val status: ItemStatus = ItemStatus.PENDING,
    val labelContent: String? = null,  // final YOLO .txt content (with class ids), saved on ACCEPT
    val reason: String? = null,        // optional, set on REJECT
    val decidedBy: String? = null,     // curator who accepted/rejected this item
    val decidedAt: String? = null,     // ISO-8601 UTC of the decision
    // ── REQ-025: in-review box editing state (durable across sessions) ──
    val workingLabel: String? = null,  // current box geometry while pending; null = use the on-disk file
    val boxClasses: List<Int?> = emptyList(), // curator-chosen class per box; null / missing = unclassified
)

/** Per-curator accept/reject tally, computed from item attribution. */
data class ReviewerCount(val accepted: Int, val rejected: Int)

/**
 * The durable per-package review record. Rewritten in full to
 * `curation/<package_id>/manifest.json` after every decision (small JSON, cheap — REQ-023).
 * Its presence in S3 is what puts a package "In Progress"; its S3 `LastModified` is the
 * "last activity" signal used for stale detection.
 */
data class CurationManifest(
    val packageId: String,
    val sourceKey: String,     // uploads/<sub>/<device>/<file>.zip
    val filename: String,
    val userSub: String,
    val deviceId: String,
    val startedAt: String,     // ISO-8601 UTC
    val curatorEmail: String,  // who *started* the package (per-item decidedBy is who reviewed each)
    val items: List<ManifestItem>,
    val categoryListVersion: Int = 1,  // REQ-025 vehicle-category list version in force
) {
    val total: Int get() = items.size
    val accepted: Int get() = items.count { it.status == ItemStatus.ACCEPTED }
    val rejected: Int get() = items.count { it.status == ItemStatus.REJECTED }
    val pending: Int get() = items.count { it.status == ItemStatus.PENDING }

    /** Index of the first still-pending item, or -1 if every item is decided. */
    fun firstPendingIndex(): Int = items.indexOfFirst { it.status == ItemStatus.PENDING }

    fun withDecision(
        index: Int,
        status: ItemStatus,
        labelContent: String? = null,
        reason: String? = null,
        decidedBy: String? = null,
        decidedAt: String? = null,
    ): CurationManifest {
        val updated = items.toMutableList()
        updated[index] = updated[index].copy(
            status = status,
            labelContent = if (status == ItemStatus.ACCEPTED) labelContent else null,
            reason = if (status == ItemStatus.REJECTED) reason?.takeIf { it.isNotBlank() } else null,
            decidedBy = decidedBy?.takeIf { it.isNotBlank() },
            decidedAt = decidedAt?.takeIf { it.isNotBlank() },
        )
        return copy(items = updated)
    }

    /** Replace the working box geometry + per-box class list for one item (REQ-025 edits). */
    fun withItemBoxes(
        index: Int,
        workingLabel: String?,
        boxClasses: List<Int?>,
    ): CurationManifest {
        val updated = items.toMutableList()
        updated[index] = updated[index].copy(workingLabel = workingLabel, boxClasses = boxClasses)
        return copy(items = updated)
    }

    /** True when the item has ≥1 box and every one has a class chosen. */
    fun allBoxesClassified(index: Int, boxCount: Int): Boolean =
        boxCount > 0 && (0 until boxCount).all { items[index].boxClasses.getOrNull(it) != null }

    fun perSubsetAccepted(): Map<String, Int> =
        items.filter { it.status == ItemStatus.ACCEPTED }
            .groupingBy { it.subset }
            .eachCount()

    /** Accept/reject counts keyed by the curator who made each decision. */
    fun reviewers(): Map<String, ReviewerCount> {
        val acc = mutableMapOf<String, IntArray>()  // [accepted, rejected]
        items.forEach { item ->
            if (item.status == ItemStatus.PENDING) return@forEach
            val who = item.decidedBy ?: return@forEach
            val slot = acc.getOrPut(who) { IntArray(2) }
            if (item.status == ItemStatus.ACCEPTED) slot[0]++ else slot[1]++
        }
        return acc.mapValues { ReviewerCount(it.value[0], it.value[1]) }
    }

    /** Box counts per class key over accepted items (from their final `labelContent`). */
    fun classCounts(categories: VehicleCategories): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        items.filter { it.status == ItemStatus.ACCEPTED }.forEach { item ->
            YoloLabel.parse(item.labelContent).forEach { box ->
                val key = categories.classes.firstOrNull { it.id == box.classId }?.key
                    ?: "class_${box.classId}"
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts
    }

    fun toJson(): String {
        val arr = JSONArray()
        items.forEach { it ->
            arr.put(JSONObject().apply {
                put("subset", it.subset)
                put("basename", it.basename)
                put("image_name", it.imageName)
                put("status", it.status.name.lowercase())
                if (it.labelContent != null) put("label_content", it.labelContent)
                if (it.reason != null) put("reason", it.reason)
                if (it.decidedBy != null) put("decided_by", it.decidedBy)
                if (it.decidedAt != null) put("decided_at", it.decidedAt)
                if (it.workingLabel != null) put("working_label", it.workingLabel)
                if (it.boxClasses.isNotEmpty()) {
                    put("box_classes", JSONArray().apply {
                        it.boxClasses.forEach { c -> put(c ?: JSONObject.NULL) }
                    })
                }
            })
        }
        return JSONObject().apply {
            put("package_id", packageId)
            put("source_key", sourceKey)
            put("filename", filename)
            put("user_sub", userSub)
            put("device_id", deviceId)
            put("started_at", startedAt)
            put("curator_email", curatorEmail)
            put("category_list_version", categoryListVersion)
            put("items", arr)
        }.toString(2)
    }

    companion object {
        fun fromJson(text: String): CurationManifest {
            val o = JSONObject(text)
            val itemsArr = o.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsArr.length()).map { i ->
                val it = itemsArr.getJSONObject(i)
                val boxClassesArr = it.optJSONArray("box_classes")
                val boxClasses = if (boxClassesArr == null) emptyList() else
                    (0 until boxClassesArr.length()).map { j ->
                        if (boxClassesArr.isNull(j)) null else boxClassesArr.getInt(j)
                    }
                ManifestItem(
                    subset = it.getString("subset"),
                    basename = it.getString("basename"),
                    imageName = it.optString("image_name", it.getString("basename") + ".jpg"),
                    status = when (it.optString("status", "pending").lowercase()) {
                        "accepted" -> ItemStatus.ACCEPTED
                        "rejected" -> ItemStatus.REJECTED
                        else -> ItemStatus.PENDING
                    },
                    labelContent = it.optString("label_content", "").ifEmpty { null },
                    reason = it.optString("reason", "").ifEmpty { null },
                    decidedBy = it.optString("decided_by", "").ifEmpty { null },
                    decidedAt = it.optString("decided_at", "").ifEmpty { null },
                    workingLabel = it.optString("working_label", "").ifEmpty { null },
                    boxClasses = boxClasses,
                )
            }
            return CurationManifest(
                packageId = o.getString("package_id"),
                sourceKey = o.getString("source_key"),
                filename = o.getString("filename"),
                userSub = o.optString("user_sub", ""),
                deviceId = o.optString("device_id", ""),
                startedAt = o.optString("started_at", ""),
                curatorEmail = o.optString("curator_email", ""),
                items = items,
                categoryListVersion = o.optInt("category_list_version", 1),
            )
        }
    }
}
