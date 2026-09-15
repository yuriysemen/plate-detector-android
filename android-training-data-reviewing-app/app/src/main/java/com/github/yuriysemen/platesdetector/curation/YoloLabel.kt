package com.github.yuriysemen.platesdetector.curation

import java.util.Locale

/**
 * A single YOLO bounding box in normalized coordinates. `classId` is `0` (plate) as collected by
 * the main app; the curation app's class picker (REQ-025) reassigns it to a vehicle-type class
 * from `config/vehicle-categories.json`. Mirrors `android-end-user-app/.../DatasetEditor.kt` — reimplemented
 * per REQ-022's "no shared module" decision.
 */
data class YoloBox(
    val classId: Int,
    val xCenter: Float,
    val yCenter: Float,
    val width: Float,
    val height: Float,
)

object YoloLabel {

    fun parse(text: String?): List<YoloBox> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val p = line.split(Regex("\\s+"))
                if (p.size < 5) return@mapNotNull null
                runCatching {
                    YoloBox(
                        p[0].toInt(),
                        p[1].replace(',', '.').toFloat(),
                        p[2].replace(',', '.').toFloat(),
                        p[3].replace(',', '.').toFloat(),
                        p[4].replace(',', '.').toFloat(),
                    )
                }.getOrNull()
            }
            .toList()
    }

    fun format(boxes: List<YoloBox>): String = format(boxes, emptyList())

    /**
     * Like [format] but overrides the class-id column from [classOverrides] where a non-null entry
     * is present (REQ-025 — the curator's chosen vehicle type). Index-aligned with [boxes].
     */
    fun format(boxes: List<YoloBox>, classOverrides: List<Int?>): String =
        boxes.mapIndexed { i, b ->
            val classId = classOverrides.getOrNull(i) ?: b.classId
            "%d %.6f %.6f %.6f %.6f".format(
                Locale.US, classId, b.xCenter, b.yCenter, b.width, b.height,
            )
        }.joinToString("\n")
}
