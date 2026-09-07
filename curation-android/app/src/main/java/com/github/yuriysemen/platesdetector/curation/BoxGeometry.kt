package com.github.yuriysemen.platesdetector.curation

import androidx.compose.ui.geometry.Offset
import kotlin.math.max

/**
 * Which part of a box a drag gesture is manipulating (REQ-024). Mirrors the main app's
 * `DragTarget` in `android/.../FrameDetailScreen.kt` — reimplemented per REQ-022's "no shared
 * module" decision.
 */
enum class DragHandle { MOVE, TL, TR, BL, BR, TM, BM, LM, RM }

/**
 * Pure, Compose-runtime-free geometry for REQ-024's move/resize editing. Everything operates in
 * normalized `[0, 1]` box space — the space [YoloBox] already lives in — so it's unit-testable
 * without an Android/Compose test harness; `ReviewScreen` owns the pixel <-> normalized
 * conversion (via its existing `fitRect`) and the drawing.
 */
object BoxGeometry {
    /** Floor on a box's normalized width/height — matches `toNormalizedBox`'s add-box threshold. */
    const val MIN_SIZE = 0.01f

    /**
     * The box at [selected] (if any) and its drag handle under [point], else the topmost box
     * whose body contains [point] (as [DragHandle.MOVE]), else `null`. [handleRadiusX]/
     * [handleRadiusY] are the hit-test radius in normalized units per axis — pass two, not one,
     * because a non-square image maps 1 normalized unit to a different pixel count per axis.
     */
    fun hitTest(
        point: Offset,
        boxes: List<YoloBox>,
        selected: Int?,
        handleRadiusX: Float,
        handleRadiusY: Float,
    ): Pair<Int, DragHandle>? {
        if (selected != null && selected in boxes.indices) {
            val handle = handleHitTest(point, boxes[selected], handleRadiusX, handleRadiusY)
            if (handle != null) return selected to handle
        }
        for (i in boxes.indices.reversed()) {
            if (contains(boxes[i], point)) return i to DragHandle.MOVE
        }
        return null
    }

    private fun handleHitTest(point: Offset, box: YoloBox, rx: Float, ry: Float): DragHandle? {
        val e = Edges(box)
        val handles = listOf(
            Offset(e.l, e.t) to DragHandle.TL, Offset(e.r, e.t) to DragHandle.TR,
            Offset(e.l, e.b) to DragHandle.BL, Offset(e.r, e.b) to DragHandle.BR,
            Offset(e.cx, e.t) to DragHandle.TM, Offset(e.cx, e.b) to DragHandle.BM,
            Offset(e.l, e.cy) to DragHandle.LM, Offset(e.r, e.cy) to DragHandle.RM,
        )
        return handles.firstOrNull { (h, _) -> inEllipse(point, h, rx, ry) }?.second
    }

    private fun inEllipse(p: Offset, center: Offset, rx: Float, ry: Float): Boolean {
        if (rx <= 0f || ry <= 0f) return false
        val nx = (p.x - center.x) / rx
        val ny = (p.y - center.y) / ry
        return nx * nx + ny * ny <= 1f
    }

    private fun contains(box: YoloBox, p: Offset): Boolean {
        val e = Edges(box)
        return p.x in e.l..e.r && p.y in e.t..e.b
    }

    /**
     * [box] with the edge(s) for [handle] shifted by ([dx], [dy]) (normalized deltas), clamped to
     * `[0, 1]` and never shrunk below [minSize]. `classId` is preserved.
     */
    fun applyHandle(box: YoloBox, handle: DragHandle, dx: Float, dy: Float, minSize: Float = MIN_SIZE): YoloBox {
        val e = Edges(box)
        var l = e.l; var t = e.t; var r = e.r; var b = e.b
        when (handle) {
            DragHandle.MOVE -> {
                val newL = (e.l + dx).coerceIn(0f, 1f - box.width)
                val newT = (e.t + dy).coerceIn(0f, 1f - box.height)
                l = newL; r = newL + box.width
                t = newT; b = newT + box.height
            }
            // `.coerceAtLeast(0f)` / `.coerceAtMost(1f)` on the far bound guards a box that starts
            // out narrower/shorter than `minSize` (e.g. a tiny collected plate box) — without it,
            // a far bound that's already past the near bound makes `coerceIn` throw.
            DragHandle.TL -> {
                l = (e.l + dx).coerceIn(0f, (e.r - minSize).coerceAtLeast(0f))
                t = (e.t + dy).coerceIn(0f, (e.b - minSize).coerceAtLeast(0f))
            }
            DragHandle.TR -> {
                r = (e.r + dx).coerceIn((e.l + minSize).coerceAtMost(1f), 1f)
                t = (e.t + dy).coerceIn(0f, (e.b - minSize).coerceAtLeast(0f))
            }
            DragHandle.BL -> {
                l = (e.l + dx).coerceIn(0f, (e.r - minSize).coerceAtLeast(0f))
                b = (e.b + dy).coerceIn((e.t + minSize).coerceAtMost(1f), 1f)
            }
            DragHandle.BR -> {
                r = (e.r + dx).coerceIn((e.l + minSize).coerceAtMost(1f), 1f)
                b = (e.b + dy).coerceIn((e.t + minSize).coerceAtMost(1f), 1f)
            }
            DragHandle.TM -> t = (e.t + dy).coerceIn(0f, (e.b - minSize).coerceAtLeast(0f))
            DragHandle.BM -> b = (e.b + dy).coerceIn((e.t + minSize).coerceAtMost(1f), 1f)
            DragHandle.LM -> l = (e.l + dx).coerceIn(0f, (e.r - minSize).coerceAtLeast(0f))
            DragHandle.RM -> r = (e.r + dx).coerceIn((e.l + minSize).coerceAtMost(1f), 1f)
        }
        return box.copy(
            xCenter = (l + r) / 2f, yCenter = (t + b) / 2f,
            width = max(r - l, minSize), height = max(b - t, minSize),
        )
    }

    private class Edges(box: YoloBox) {
        val l = box.xCenter - box.width / 2f
        val t = box.yCenter - box.height / 2f
        val r = box.xCenter + box.width / 2f
        val b = box.yCenter + box.height / 2f
        val cx get() = (l + r) / 2f
        val cy get() = (t + b) / 2f
    }
}
