package com.github.yuriysemen.platesdetector

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

// Mutable box in display-space pixels (relative to image canvas origin)
private data class DisplayBox(
    val classId: Int,
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float
) {
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    val w get() = right - left
    val h get() = bottom - top
}

private enum class DragTarget { NONE, MOVE, TL, TR, BL, BR, TM, BM, LM, RM }

@Composable
fun FrameDetailScreen(
    frame: FrameEntry,
    editor: DatasetEditor,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var boxes by remember { mutableStateOf<List<DisplayBox>>(emptyList()) }
    var selectedIndex by remember { mutableStateOf(-1) }
    var hasUnsavedChanges by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showDeleteFrameDialog by remember { mutableStateOf(false) }
    var showDeleteLastBoxDialog by remember { mutableStateOf(false) }
    var drawingNewBox by remember { mutableStateOf(false) }
    var newBoxStart by remember { mutableStateOf(Offset.Zero) }
    var newBoxEnd by remember { mutableStateOf(Offset.Zero) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    var layoutOffX by remember { mutableStateOf(0f) }
    var layoutOffY by remember { mutableStateOf(0f) }
    var layoutDispW by remember { mutableStateOf(1f) }
    var layoutDispH by remember { mutableStateOf(1f) }
    var zoomScale by remember { mutableStateOf(1f) }
    var panOffsetX by remember { mutableStateOf(0f) }
    var panOffsetY by remember { mutableStateOf(0f) }

    // mode state
    var isEditMode by remember { mutableStateOf(false) }
    var savedBoxes by remember { mutableStateOf<List<DisplayBox>>(emptyList()) }

    var dragTarget by remember { mutableStateOf(DragTarget.NONE) }

    LaunchedEffect(frame.name) {
        zoomScale = 1f
        panOffsetX = 0f
        panOffsetY = 0f
        val bmp = withContext(Dispatchers.IO) { editor.loadBitmap(frame.imageFile) } ?: return@LaunchedEffect
        bitmap = bmp
        imageBitmap = bmp.asImageBitmap()
    }

    fun enterEditMode() {
        savedBoxes = boxes
        isEditMode = true
    }

    fun exitEditMode(discardChanges: Boolean) {
        if (discardChanges) {
            boxes = savedBoxes
            hasUnsavedChanges = false
            drawingNewBox = false
        }
        selectedIndex = -1
        isEditMode = false
    }

    fun navigateBack() {
        if (isEditMode) {
            if (hasUnsavedChanges) showDiscardDialog = true else exitEditMode(false)
        } else {
            onBack()
        }
    }

    BackHandler { navigateBack() }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard unsaved changes?") },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; exitEditMode(true) }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            }
        )
    }

    if (showDeleteFrameDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteFrameDialog = false },
            title = { Text("Delete this frame?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteFrameDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) { editor.deleteFrames(listOf(frame)) }
                        onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteFrameDialog = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteLastBoxDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteLastBoxDialog = false },
            title = { Text("No boxes remain") },
            text = { Text("Delete the frame entirely?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteLastBoxDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) { editor.deleteFrames(listOf(frame)) }
                        onBack()
                    }
                }) { Text("Delete frame") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteLastBoxDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) { editor.saveBoxes(frame, emptyList()) }
                        onBack()
                    }
                }) { Text("Keep empty") }
            }
        )
    }

    val density = LocalDensity.current
    val handleRadius = with(density) { 14.dp.toPx() }
    val minBoxSize = with(density) { 10.dp.toPx() }
    val minBoxH = minBoxSize * 0.75f

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (!isEditMode) {
                    // View mode
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        frame.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { enterEditMode() }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit boxes")
                    }
                } else {
                    // Edit mode
                    TextButton(onClick = { navigateBack() }) {
                        Text("Cancel")
                    }
                    Text(
                        frame.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    // Delete selected box
                    if (selectedIndex in boxes.indices) {
                        IconButton(onClick = {
                            val updated = boxes.toMutableList().also { it.removeAt(selectedIndex) }
                            selectedIndex = -1
                            if (updated.isEmpty()) {
                                showDeleteLastBoxDialog = true
                            } else {
                                boxes = updated
                                hasUnsavedChanges = true
                            }
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Delete box", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    // Save
                    IconButton(
                        onClick = {
                            isSaving = true
                            scope.launch {
                                val bmp = bitmap ?: return@launch
                                val yoloBoxes = boxes.map { d ->
                                    val l = ((d.left - layoutOffX) / layoutDispW).coerceIn(0f, 1f)
                                    val t = ((d.top - layoutOffY) / layoutDispH).coerceIn(0f, 1f)
                                    val r = ((d.right - layoutOffX) / layoutDispW).coerceIn(0f, 1f)
                                    val b2 = ((d.bottom - layoutOffY) / layoutDispH).coerceIn(0f, 1f)
                                    YoloBox(d.classId, (l + r) / 2f, (t + b2) / 2f, r - l, b2 - t)
                                }
                                withContext(Dispatchers.IO) { editor.saveBoxes(frame, yoloBoxes) }
                                isSaving = false
                                hasUnsavedChanges = false
                                onBack()
                            }
                        },
                        enabled = hasUnsavedChanges && !isSaving
                    ) {
                        if (isSaving) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(
                            Icons.Default.Check,
                            contentDescription = "Save",
                            tint = if (hasUnsavedChanges) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                    // Overflow — delete frame
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Delete frame", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { showOverflowMenu = false; showDeleteFrameDialog = true }
                            )
                        }
                    }
                }
            }

            // ── Image + interactive canvas ────────────────────────────────────
            val bmp = bitmap
            val imgBitmap = imageBitmap
            if (bmp == null || imgBitmap == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    val canvasW = constraints.maxWidth.toFloat()
                    val canvasH = constraints.maxHeight.toFloat()
                    val scale = minOf(canvasW / bmp.width, canvasH / bmp.height)
                    val dispW = bmp.width * scale
                    val dispH = bmp.height * scale
                    val offX = (canvasW - dispW) / 2f
                    val offY = (canvasH - dispH) / 2f

                    SideEffect {
                        if (initialized && dispW > 0f && dispH > 0f &&
                            (layoutOffX != offX || layoutOffY != offY || layoutDispW != dispW || layoutDispH != dispH)
                        ) {
                            val oldOffX = layoutOffX; val oldOffY = layoutOffY
                            val oldDispW = layoutDispW; val oldDispH = layoutDispH
                            boxes = boxes.map { d ->
                                val l = ((d.left - oldOffX) / oldDispW).coerceIn(0f, 1f)
                                val t = ((d.top - oldOffY) / oldDispH).coerceIn(0f, 1f)
                                val r = ((d.right - oldOffX) / oldDispW).coerceIn(0f, 1f)
                                val b2 = ((d.bottom - oldOffY) / oldDispH).coerceIn(0f, 1f)
                                DisplayBox(d.classId, offX + l * dispW, offY + t * dispH, offX + r * dispW, offY + b2 * dispH)
                            }
                        }
                        layoutOffX = offX
                        layoutOffY = offY
                        layoutDispW = dispW
                        layoutDispH = dispH
                    }

                    fun yoloToCanvas(b: YoloBox): DisplayBox = DisplayBox(
                        classId = b.classId,
                        left = offX + (b.xCenter - b.width / 2f) * dispW,
                        top = offY + (b.yCenter - b.height / 2f) * dispH,
                        right = offX + (b.xCenter + b.width / 2f) * dispW,
                        bottom = offY + (b.yCenter + b.height / 2f) * dispH
                    )

                    LaunchedEffect(bmp, offX, offY, dispW, dispH) {
                        if (!initialized && dispW > 0f && dispH > 0f) {
                            boxes = frame.boxes.map { b -> yoloToCanvas(b) }
                            initialized = true
                        }
                    }

                    var zoomLabelAlpha by remember { mutableStateOf(0f) }
                    val animatedLabelAlpha by animateFloatAsState(targetValue = zoomLabelAlpha, label = "zoomLabel")
                    LaunchedEffect(zoomScale) {
                        if (zoomScale > 1f) {
                            zoomLabelAlpha = 1f
                            delay(1500)
                            zoomLabelAlpha = 0f
                        } else {
                            zoomLabelAlpha = 0f
                        }
                    }

                    fun hitTest(pos: Offset): Pair<Int, DragTarget> {
                        val cp = Offset((pos.x - panOffsetX) / zoomScale, (pos.y - panOffsetY) / zoomScale)
                        val hitR = handleRadius / zoomScale * 1.5f
                        val sel = selectedIndex
                        if (sel in boxes.indices) {
                            val b = boxes[sel]
                            val handles = listOf(
                                Offset(b.left, b.top) to DragTarget.TL,
                                Offset(b.right, b.top) to DragTarget.TR,
                                Offset(b.left, b.bottom) to DragTarget.BL,
                                Offset(b.right, b.bottom) to DragTarget.BR,
                                Offset(b.cx, b.top) to DragTarget.TM,
                                Offset(b.cx, b.bottom) to DragTarget.BM,
                                Offset(b.left, b.cy) to DragTarget.LM,
                                Offset(b.right, b.cy) to DragTarget.RM,
                            )
                            for ((hPos, target) in handles) {
                                if (dist(cp, hPos) <= hitR) return sel to target
                            }
                            if (cp.x in b.left..b.right && cp.y in b.top..b.bottom) return sel to DragTarget.MOVE
                        }
                        for (i in boxes.indices.reversed()) {
                            val b = boxes[i]
                            if (cp.x in b.left..b.right && cp.y in b.top..b.bottom) return i to DragTarget.MOVE
                        }
                        return -1 to DragTarget.NONE
                    }

                    val boxColors = listOf(Color(0xFF00E676), Color(0xFF40C4FF), Color(0xFFFF6E40), Color(0xFFEA80FC))

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            // Draw new box (edit mode only)
                            .pointerInput(drawingNewBox, isEditMode) {
                                if (!isEditMode || !drawingNewBox) return@pointerInput
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        newBoxStart = Offset((offset.x - panOffsetX) / zoomScale, (offset.y - panOffsetY) / zoomScale)
                                        newBoxEnd = newBoxStart
                                    },
                                    onDrag = { change, _ ->
                                        newBoxEnd = Offset((change.position.x - panOffsetX) / zoomScale, (change.position.y - panOffsetY) / zoomScale)
                                    },
                                    onDragEnd = {
                                        var l = minOf(newBoxStart.x, newBoxEnd.x).coerceIn(offX, offX + dispW)
                                        var t = minOf(newBoxStart.y, newBoxEnd.y).coerceIn(offY, offY + dispH)
                                        var r = maxOf(newBoxStart.x, newBoxEnd.x).coerceIn(offX, offX + dispW)
                                        var b2 = maxOf(newBoxStart.y, newBoxEnd.y).coerceIn(offY, offY + dispH)
                                        if (r - l < minBoxSize) {
                                            val cx = (l + r) / 2f
                                            l = cx - minBoxSize / 2f; r = cx + minBoxSize / 2f
                                            if (l < offX) { l = offX; r = offX + minBoxSize }
                                            if (r > offX + dispW) { r = offX + dispW; l = r - minBoxSize }
                                        }
                                        if (b2 - t < minBoxH) {
                                            val cy = (t + b2) / 2f
                                            t = cy - minBoxH / 2f; b2 = cy + minBoxH / 2f
                                            if (t < offY) { t = offY; b2 = offY + minBoxH }
                                            if (b2 > offY + dispH) { b2 = offY + dispH; t = b2 - minBoxH }
                                        }
                                        boxes = boxes + DisplayBox(0, l, t, r, b2)
                                        selectedIndex = boxes.size - 1
                                        hasUnsavedChanges = true
                                        drawingNewBox = false
                                    },
                                    onDragCancel = { drawingNewBox = false }
                                )
                            }
                            // Tap to select / double-tap to reset zoom (edit mode tap selects; view mode double-tap still resets zoom)
                            .pointerInput(boxes, selectedIndex, isEditMode) {
                                if (!drawingNewBox) {
                                    detectTapGestures(
                                        onTap = { offset ->
                                            if (isEditMode) {
                                                val (idx, _) = hitTest(offset)
                                                selectedIndex = idx
                                            }
                                        },
                                        onDoubleTap = {
                                            zoomScale = 1f
                                            panOffsetX = 0f
                                            panOffsetY = 0f
                                        }
                                    )
                                }
                            }
                            // Drag boxes (edit mode only)
                            .pointerInput(selectedIndex, drawingNewBox, isEditMode) {
                                if (!isEditMode || drawingNewBox) return@pointerInput
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val (idx, target) = hitTest(offset)
                                        if (idx >= 0) {
                                            selectedIndex = idx
                                            dragTarget = target
                                        } else {
                                            dragTarget = DragTarget.NONE
                                        }
                                    },
                                    onDrag = { _, dragAmount ->
                                        val idx = selectedIndex
                                        if (idx !in boxes.indices || dragTarget == DragTarget.NONE) return@detectDragGestures
                                        val dx = dragAmount.x / zoomScale
                                        val dy = dragAmount.y / zoomScale
                                        val b = boxes[idx]
                                        val updated = when (dragTarget) {
                                            DragTarget.MOVE -> b.copy(
                                                left = (b.left + dx).coerceIn(offX, offX + dispW - b.w),
                                                top = (b.top + dy).coerceIn(offY, offY + dispH - b.h),
                                                right = (b.right + dx).coerceIn(offX + b.w, offX + dispW),
                                                bottom = (b.bottom + dy).coerceIn(offY + b.h, offY + dispH)
                                            )
                                            DragTarget.TL -> b.copy(
                                                left = (b.left + dx).coerceIn(offX, b.right - minBoxSize),
                                                top = (b.top + dy).coerceIn(offY, b.bottom - minBoxH)
                                            )
                                            DragTarget.TR -> b.copy(
                                                right = (b.right + dx).coerceIn(b.left + minBoxSize, offX + dispW),
                                                top = (b.top + dy).coerceIn(offY, b.bottom - minBoxH)
                                            )
                                            DragTarget.BL -> b.copy(
                                                left = (b.left + dx).coerceIn(offX, b.right - minBoxSize),
                                                bottom = (b.bottom + dy).coerceIn(b.top + minBoxH, offY + dispH)
                                            )
                                            DragTarget.BR -> b.copy(
                                                right = (b.right + dx).coerceIn(b.left + minBoxSize, offX + dispW),
                                                bottom = (b.bottom + dy).coerceIn(b.top + minBoxH, offY + dispH)
                                            )
                                            DragTarget.TM -> b.copy(top = (b.top + dy).coerceIn(offY, b.bottom - minBoxH))
                                            DragTarget.BM -> b.copy(bottom = (b.bottom + dy).coerceIn(b.top + minBoxH, offY + dispH))
                                            DragTarget.LM -> b.copy(left = (b.left + dx).coerceIn(offX, b.right - minBoxSize))
                                            DragTarget.RM -> b.copy(right = (b.right + dx).coerceIn(b.left + minBoxSize, offX + dispW))
                                            DragTarget.NONE -> b
                                        }
                                        boxes = boxes.toMutableList().also { it[idx] = updated }
                                        hasUnsavedChanges = true
                                    },
                                    onDragEnd = { dragTarget = DragTarget.NONE }
                                )
                            }
                            // Pinch-to-zoom (always active)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    var event = awaitPointerEvent()
                                    while (event.changes.any { it.pressed } && event.changes.count { it.pressed } < 2) {
                                        event = awaitPointerEvent()
                                    }
                                    if (event.changes.count { it.pressed } < 2) return@awaitEachGesture

                                    if (drawingNewBox) drawingNewBox = false
                                    event.changes.forEach { it.consume() }

                                    do {
                                        event = awaitPointerEvent()
                                        if (event.changes.count { it.pressed } < 2) break

                                        val centroid = event.calculateCentroid(useCurrent = false)
                                        val zoomFactor = event.calculateZoom()
                                        val panDelta = event.calculatePan()

                                        val newZoom = (zoomScale * zoomFactor).coerceIn(1f, 8f)
                                        val actualFactor = newZoom / zoomScale

                                        panOffsetX = centroid.x - (centroid.x - panOffsetX) * actualFactor + panDelta.x
                                        panOffsetY = centroid.y - (centroid.y - panOffsetY) * actualFactor + panDelta.y
                                        zoomScale = newZoom

                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        panOffsetX = panOffsetX.coerceIn(
                                            w * 0.25f - (layoutOffX + layoutDispW) * zoomScale,
                                            w * 0.75f - layoutOffX * zoomScale
                                        )
                                        panOffsetY = panOffsetY.coerceIn(
                                            h * 0.25f - (layoutOffY + layoutDispH) * zoomScale,
                                            h * 0.75f - layoutOffY * zoomScale
                                        )

                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                    ) {
                        withTransform({
                            translate(panOffsetX, panOffsetY)
                            scale(zoomScale, zoomScale, Offset.Zero)
                        }) {
                            drawImage(
                                image = imgBitmap,
                                dstOffset = androidx.compose.ui.unit.IntOffset(offX.toInt(), offY.toInt()),
                                dstSize = androidx.compose.ui.unit.IntSize(dispW.toInt(), dispH.toInt())
                            )

                            // New-box preview (edit mode only)
                            if (drawingNewBox) {
                                val l = minOf(newBoxStart.x, newBoxEnd.x)
                                val t = minOf(newBoxStart.y, newBoxEnd.y)
                                val r = maxOf(newBoxStart.x, newBoxEnd.x)
                                val b2 = maxOf(newBoxStart.y, newBoxEnd.y)
                                drawRect(Color.White, Offset(l, t), Size(r - l, b2 - t), style = Stroke(2.dp.toPx()))
                            }

                            // Draw boxes
                            for ((i, box) in boxes.withIndex()) {
                                val isSelected = isEditMode && i == selectedIndex
                                val color = boxColors[i % boxColors.size]
                                val strokeW = if (isSelected) 3.dp.toPx() else 2.dp.toPx()
                                drawRect(color, Offset(box.left, box.top), Size(box.w, box.h), style = Stroke(strokeW))

                                if (isSelected) {
                                    val screenHandleR = handleRadius / zoomScale
                                    val handles = listOf(
                                        Offset(box.left, box.top), Offset(box.right, box.top),
                                        Offset(box.left, box.bottom), Offset(box.right, box.bottom),
                                        Offset(box.cx, box.top), Offset(box.cx, box.bottom),
                                        Offset(box.left, box.cy), Offset(box.right, box.cy)
                                    )
                                    for (h in handles) {
                                        drawCircle(Color.White, radius = screenHandleR, center = h)
                                        drawCircle(color, radius = screenHandleR - 3.dp.toPx() / zoomScale, center = h)
                                    }
                                }
                            }
                        }
                    }

                    if (animatedLabelAlpha > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 12.dp, bottom = 12.dp),
                            contentAlignment = Alignment.BottomStart
                        ) {
                            Text(
                                text = "%.1f×".format(zoomScale),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .alpha(animatedLabelAlpha)
                                    .background(Color.Black.copy(alpha = 0.45f), shape = RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // ── FAB row (edit mode only) ──────────────────────────────────────
            if (isEditMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (!drawingNewBox) {
                        FloatingActionButton(
                            onClick = {
                                selectedIndex = -1
                                drawingNewBox = true
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add box")
                        }
                    } else {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { drawingNewBox = false }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}


private fun dist(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}
