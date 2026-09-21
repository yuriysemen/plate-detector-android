package com.github.yuriysemen.platesdetector.training

import android.net.Uri
import java.io.File

/**
 * The only frame sizes the collected dataset may contain (REQ-042): 4:3 [LOW] and 16:9 [HD].
 * There is deliberately no "let the camera decide" option — that made the dataset's resolution
 * device-dependent and undocumented.
 */
enum class AnalysisResolution(
    val label: String,
    val detail: String,
    val width: Int,
    val height: Int
) {
    LOW("Low (640×480)", "Fastest, minimal memory usage", 640, 480),
    HD("HD (1280×720)", "More detail for distant or small plates", 1280, 720);

    val isWide: Boolean get() = width * 9 == height * 16

    companion object {
        /** True when a captured frame (either orientation) is one of the permitted dataset sizes. */
        fun isAllowedFrameSize(w: Int, h: Int): Boolean =
            entries.any { (it.width == w && it.height == h) || (it.width == h && it.height == w) }
    }
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
