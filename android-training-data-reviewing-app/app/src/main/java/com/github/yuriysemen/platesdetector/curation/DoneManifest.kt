package com.github.yuriysemen.platesdetector.curation

import org.json.JSONObject

/**
 * Written on Complete (REQ-035) to `<doneBaseKey>._manifest.json` — a small audit record kept as
 * an un-zipped sidecar, deliberately, so `CurationRepository.listDone()` can list every completed
 * package's summary without downloading and unzipping its archive. The curated images themselves
 * are in the sibling archive named by [doneZipKey] (rejected items similarly in [rejectedZipKey]).
 * A manifest from before REQ-035 has neither — its images live loose under the pre-REQ-035
 * `done/<package_id>/<subset>/{images,labels}/` shape instead.
 */
data class DoneManifest(
    val packageId: String,
    val filename: String,
    val curatorEmail: String,   // who started the package
    val completedBy: String,    // who tapped Complete
    val startedAt: String,
    val completedAt: String,
    val total: Int,
    val accepted: Int,
    val rejected: Int,
    val perSubsetAccepted: Map<String, Int>,
    val reviewers: Map<String, ReviewerCount>,  // per-curator accept/reject tally
    val categoryListVersion: Int,               // REQ-025 class scheme in force
    val classCounts: Map<String, Int>,          // accepted-box count per class key
    val doneZipKey: String? = null,             // REQ-035: S3 key of the accepted-items archive; null if nothing was accepted
    val rejectedZipKey: String? = null,         // REQ-035: S3 key of the rejected-items archive; null if nothing was rejected
) {
    fun toJson(): String = JSONObject().apply {
        put("package_id", packageId)
        put("filename", filename)
        put("curator_email", curatorEmail)
        put("completed_by", completedBy)
        put("started_at", startedAt)
        put("completed_at", completedAt)
        put("total", total)
        put("accepted", accepted)
        put("rejected", rejected)
        put("accepted_by_subset", JSONObject(perSubsetAccepted.toMap()))
        put("reviewers", JSONObject().apply {
            reviewers.forEach { (email, c) ->
                put(email, JSONObject().apply {
                    put("accepted", c.accepted)
                    put("rejected", c.rejected)
                })
            }
        })
        put("category_list_version", categoryListVersion)
        put("class_counts", JSONObject(classCounts.toMap()))
        if (doneZipKey != null) put("done_zip_key", doneZipKey)
        if (rejectedZipKey != null) put("rejected_zip_key", rejectedZipKey)
    }.toString(2)

    companion object {
        fun fromJson(text: String): DoneManifest {
            val o = JSONObject(text)
            val bySubset = o.optJSONObject("accepted_by_subset") ?: JSONObject()
            val subsetMap = bySubset.keys().asSequence().associateWith { bySubset.optInt(it, 0) }
            val revObj = o.optJSONObject("reviewers") ?: JSONObject()
            val revMap = revObj.keys().asSequence().associateWith { k ->
                val c = revObj.getJSONObject(k)
                ReviewerCount(c.optInt("accepted", 0), c.optInt("rejected", 0))
            }
            val ccObj = o.optJSONObject("class_counts") ?: JSONObject()
            val ccMap = ccObj.keys().asSequence().associateWith { ccObj.optInt(it, 0) }
            return DoneManifest(
                packageId = o.getString("package_id"),
                filename = o.optString("filename", o.getString("package_id")),
                curatorEmail = o.optString("curator_email", ""),
                completedBy = o.optString("completed_by", ""),
                startedAt = o.optString("started_at", ""),
                completedAt = o.optString("completed_at", ""),
                total = o.optInt("total", 0),
                accepted = o.optInt("accepted", 0),
                rejected = o.optInt("rejected", 0),
                perSubsetAccepted = subsetMap,
                reviewers = revMap,
                categoryListVersion = o.optInt("category_list_version", 1),
                classCounts = ccMap,
                doneZipKey = o.optString("done_zip_key", "").ifEmpty { null },
                rejectedZipKey = o.optString("rejected_zip_key", "").ifEmpty { null },
            )
        }
    }
}
