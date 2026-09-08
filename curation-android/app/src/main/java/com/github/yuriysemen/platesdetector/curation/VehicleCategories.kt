package com.github.yuriysemen.platesdetector.curation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One YOLO class. `id` is the class-id column written into label files. */
data class VehicleClass(val id: Int, val key: String, val label: String)

/**
 * The canonical vehicle-category list (REQ-025). Lives at `config/vehicle-categories.json` in the
 * dataset bucket; a byte-identical copy is bundled at `assets/vehicle-categories.json` as the
 * offline fallback. `id` order is pinned so a reorder can't silently remap existing labels.
 *
 * A package embeds its own snapshot of this (see `CurationManifest.categories`) at Start time —
 * review and Complete always use that snapshot, never a freshly-fetched list, so a package started
 * under version N is reviewed and completed against version N even if the canonical list has since
 * moved on and even if the device is offline at Complete time.
 */
data class VehicleCategories(
    val version: Int,
    val classes: List<VehicleClass>,
) {
    fun labelFor(classId: Int): String =
        classes.firstOrNull { it.id == classId }?.label ?: "Class $classId"

    fun contains(classId: Int): Boolean = classes.any { it.id == classId }

    /** YOLO `names: [...]` in id order, gaps filled so the index matches the class id. */
    fun orderedNames(): List<String> {
        val max = classes.maxOfOrNull { it.id } ?: -1
        return (0..max).map { id -> classes.firstOrNull { it.id == id }?.key ?: "class_$id" }
    }

    /** Embeds the full list (not just [version]) so a package's manifest snapshot round-trips. */
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("version", version)
        put("classes", JSONArray().apply {
            classes.forEach { c ->
                put(JSONObject().apply { put("id", c.id); put("key", c.key); put("label", c.label) })
            }
        })
    }

    companion object {
        private const val ASSET = "vehicle-categories.json"

        fun fromJsonObject(o: JSONObject): VehicleCategories {
            val arr = o.getJSONArray("classes")
            val classes = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                VehicleClass(c.getInt("id"), c.getString("key"), c.getString("label"))
            }.sortedBy { it.id }
            require(classes.isNotEmpty()) { "vehicle-categories.json has no classes" }
            return VehicleCategories(o.optInt("version", 1), classes)
        }

        fun fromJson(text: String): VehicleCategories = fromJsonObject(JSONObject(text))

        fun bundledDefault(context: Context): VehicleCategories =
            fromJson(context.assets.open(ASSET).bufferedReader().use { it.readText() })
    }
}
