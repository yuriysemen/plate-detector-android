package com.github.yuriysemen.platesdetector.curation

import org.json.JSONObject

/**
 * Written to `done/<package_id>/_manifest.json` on Complete — a small audit record. The curated
 * images themselves live under `done/<package_id>/<subset>/{images,labels}/`.
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
            )
        }
    }
}
