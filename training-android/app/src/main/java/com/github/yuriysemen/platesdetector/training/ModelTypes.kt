package com.github.yuriysemen.platesdetector.training

import android.net.Uri
import java.io.File

enum class AnalysisResolution(val label: String, val detail: String) {
    DEFAULT("Default", "Camera driver decides"),
    LOW("Low (640×480)", "Fastest, minimal memory usage"),
    HD("HD (1280×720)", "More detail for distant or small plates")
}

enum class CoordFormat {
    /** [x1, y1, x2, y2, score, class] */
    XYXY_SCORE_CLASS,

    /** [y1, x1, y2, x2, score, class] */
    YXYX_SCORE_CLASS
}

sealed class ModelSource {
    data class Asset(val path: String) : ModelSource()
    data class ContentUri(val uri: Uri) : ModelSource()
    data class FilePath(val file: File) : ModelSource()
}
