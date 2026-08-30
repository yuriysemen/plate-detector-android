package com.github.yuriysemen.platesdetector.curation

import java.util.Locale

/**
 * A single YOLO bounding box in normalized coordinates. Single-class dataset (`License_Plate`),
 * so `classId` is effectively always 0. Mirrors `android/.../DatasetEditor.kt` — reimplemented
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

    fun format(boxes: List<YoloBox>): String =
        boxes.joinToString("\n") { b ->
            "%d %.6f %.6f %.6f %.6f".format(
                Locale.US, b.classId, b.xCenter, b.yCenter, b.width, b.height,
            )
        }
}
