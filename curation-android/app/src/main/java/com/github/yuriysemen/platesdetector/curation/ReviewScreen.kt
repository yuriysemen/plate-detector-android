package com.github.yuriysemen.platesdetector.curation

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val UNCLASSIFIED = Color(0xFFFFC107)   // amber
private val CLASSIFIED = Color(0xFF00E5FF)      // cyan
private val SELECTED = Color(0xFFFF4081)        // pink

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: CurationViewModel, onBack: () -> Unit) {
    val session = vm.session ?: return
    BackHandler(onBack = onBack)

    val item = session.currentItem
    val cached = session.current
    val decided = item.status != ItemStatus.PENDING
    val categories = vm.categories

    // Recomputed on every session change (each box edit copies the session).
    val review = remember(session) { vm.currentBoxes() }
    val boxes = review.boxes
    val classes = review.classes

    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(session.index) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(cached.imageFile.absolutePath)?.asImageBitmap() }
                .getOrNull()
        }
    }

    var selected by remember(session.index) { mutableStateOf<Int?>(null) }
    var addMode by remember(session.index) { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var showReject by remember { mutableStateOf(false) }
    var reason by remember(session.index) { mutableStateOf("") }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragNow by remember { mutableStateOf<Offset?>(null) }

    // ── REQ-024: move/resize the selected box + pinch-zoom/pan ─────────────
    var dragHandle by remember(session.index) { mutableStateOf<DragHandle?>(null) }
    var liveBox by remember(session.index) { mutableStateOf<YoloBox?>(null) }
    var zoomScale by remember(session.index) { mutableStateOf(1f) }
    var panOffsetX by remember(session.index) { mutableStateOf(0f) }
    var panOffsetY by remember(session.index) { mutableStateOf(0f) }
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { 14.dp.toPx() }

    val defaultNewClass = categories?.classes?.firstOrNull { it.key != "license_plate" }?.id
        ?: categories?.classes?.firstOrNull()?.id ?: 1

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("${session.index + 1} / ${session.items.size}   ·   ${cached.subset}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!decided) {
                        IconButton(onClick = { addMode = !addMode }) {
                            Icon(
                                if (addMode) Icons.Default.Close else Icons.Default.Add,
                                contentDescription = if (addMode) "Cancel add box" else "Add box",
                                tint = if (addMode) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    IconButton(onClick = { showJump = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Jump to item")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    reviewHint(item, boxes, review.allClassified)?.let { (msg, isError) ->
                        Text(
                            msg, Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { vm.navigate(-1) }, enabled = session.index > 0) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous")
                        }
                        OutlinedButton(
                            onClick = { showReject = true },
                            enabled = !decided,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Reject") }
                        Button(
                            onClick = { vm.accept() },
                            enabled = !decided && review.allClassified,
                            modifier = Modifier.weight(1f),
                        ) { Text("Accept") }
                        IconButton(
                            onClick = { vm.navigate(1) },
                            enabled = session.index < session.items.size - 1,
                        ) { Icon(Icons.Default.ChevronRight, contentDescription = "Next") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(
                Modifier.fillMaxWidth().weight(1f).background(Color.Black)
                    .onSizeChanged { canvasSize = it },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp == null) {
                    Text("Loading image…", color = Color.White)
                } else {
                    // Each block below is always attached and internally no-ops when its mode
                    // isn't active, so add-box / move-resize / pinch-zoom-pan can coexist on one
                    // Canvas — mirrors android/.../FrameDetailScreen.kt's chained pointerInputs.
                    val canvasMod = Modifier.fillMaxSize()
                        .pointerInput(session.index) {
                            if (!addMode || decided) return@pointerInput
                            detectDragGestures(
                                onDragStart = { dragStart = it; dragNow = it },
                                onDrag = { change, _ -> dragNow = change.position },
                                onDragCancel = { dragStart = null; dragNow = null },
                                onDragEnd = {
                                    val s = dragStart; val e = dragNow
                                    if (s != null && e != null) {
                                        val rect = fitRect(canvasSize, bmp.width, bmp.height)
                                        toNormalizedBox(s, e, rect)?.let { (xc, yc, w, h) ->
                                            vm.addBox(YoloBox(defaultNewClass, xc, yc, w, h), defaultNewClass)
                                            selected = boxes.size
                                        }
                                    }
                                    dragStart = null; dragNow = null; addMode = false
                                },
                            )
                        }
                        .pointerInput(session.index) {
                            detectTapGestures(onDoubleTap = {
                                zoomScale = 1f; panOffsetX = 0f; panOffsetY = 0f
                            })
                        }
                        .pointerInput(session, addMode, decided) {
                            if (addMode || decided) return@pointerInput
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val rect = fitRect(canvasSize, bmp.width, bmp.height)
                                    val content = Offset((pos.x - panOffsetX) / zoomScale, (pos.y - panOffsetY) / zoomScale)
                                    val norm = normalizedPoint(content, rect)
                                    val radiusPx = handleRadiusPx / zoomScale
                                    val rx = if (rect.width > 0f) radiusPx / rect.width else 0f
                                    val ry = if (rect.height > 0f) radiusPx / rect.height else 0f
                                    val hit = BoxGeometry.hitTest(norm, boxes, selected, rx, ry)
                                    if (hit != null) {
                                        selected = hit.first; dragHandle = hit.second; liveBox = boxes[hit.first]
                                    } else {
                                        dragHandle = null; liveBox = null
                                    }
                                },
                                onDrag = { _, dragAmount ->
                                    val handle = dragHandle ?: return@detectDragGestures
                                    val idx = selected ?: return@detectDragGestures
                                    val rect = fitRect(canvasSize, bmp.width, bmp.height)
                                    if (rect.width <= 0f || rect.height <= 0f) return@detectDragGestures
                                    val dx = (dragAmount.x / zoomScale) / rect.width
                                    val dy = (dragAmount.y / zoomScale) / rect.height
                                    liveBox = BoxGeometry.applyHandle(liveBox ?: boxes[idx], handle, dx, dy)
                                },
                                onDragEnd = {
                                    val idx = selected; val box = liveBox
                                    if (idx != null && box != null && dragHandle != null) vm.moveBox(idx, box)
                                    dragHandle = null; liveBox = null
                                },
                                onDragCancel = { dragHandle = null; liveBox = null },
                            )
                        }
                        .pointerInput(session.index) {
                            awaitEachGesture {
                                var event = awaitPointerEvent()
                                while (event.changes.any { it.pressed } && event.changes.count { it.pressed } < 2) {
                                    event = awaitPointerEvent()
                                }
                                if (event.changes.count { it.pressed } < 2) return@awaitEachGesture
                                dragStart = null; dragNow = null
                                dragHandle = null; liveBox = null
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
                                    val rect = fitRect(IntSize(size.width, size.height), bmp.width, bmp.height)
                                    val w = size.width.toFloat(); val h = size.height.toFloat()
                                    panOffsetX = panOffsetX.coerceIn(
                                        w * 0.25f - (rect.left + rect.width) * zoomScale,
                                        w * 0.75f - rect.left * zoomScale,
                                    )
                                    panOffsetY = panOffsetY.coerceIn(
                                        h * 0.25f - (rect.top + rect.height) * zoomScale,
                                        h * 0.75f - rect.top * zoomScale,
                                    )
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        }

                    Canvas(canvasMod) {
                        val rect = fitRect(IntSize(size.width.toInt(), size.height.toInt()), bmp.width, bmp.height)
                        withTransform({
                            translate(panOffsetX, panOffsetY)
                            scale(zoomScale, zoomScale, pivot = Offset.Zero)
                        }) {
                            drawImage(
                                image = bmp,
                                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                                dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                            )
                            val shown = if (dragHandle != null && liveBox != null && selected != null) {
                                boxes.mapIndexed { i, b -> if (i == selected) liveBox!! else b }
                            } else boxes
                            shown.forEachIndexed { i, b ->
                                val color = when {
                                    i == selected -> SELECTED
                                    classes.getOrNull(i) == null -> UNCLASSIFIED
                                    else -> CLASSIFIED
                                }
                                drawRect(
                                    color = color,
                                    topLeft = Offset(
                                        rect.left + (b.xCenter - b.width / 2f) * rect.width,
                                        rect.top + (b.yCenter - b.height / 2f) * rect.height,
                                    ),
                                    size = Size(b.width * rect.width, b.height * rect.height),
                                    style = Stroke(width = if (i == selected) 5f else 3f),
                                )
                            }
                            val sel = selected
                            if (!decided && !addMode && sel != null && sel in shown.indices) {
                                val b = shown[sel]
                                val hl = rect.left + (b.xCenter - b.width / 2f) * rect.width
                                val ht = rect.top + (b.yCenter - b.height / 2f) * rect.height
                                val hr2 = rect.left + (b.xCenter + b.width / 2f) * rect.width
                                val hb = rect.top + (b.yCenter + b.height / 2f) * rect.height
                                val hcx = (hl + hr2) / 2f; val hcy = (ht + hb) / 2f
                                val handlePts = listOf(
                                    Offset(hl, ht), Offset(hr2, ht), Offset(hl, hb), Offset(hr2, hb),
                                    Offset(hcx, ht), Offset(hcx, hb), Offset(hl, hcy), Offset(hr2, hcy),
                                )
                                val hr = handleRadiusPx / zoomScale
                                handlePts.forEach { h ->
                                    drawCircle(Color.White, radius = hr, center = h)
                                    drawCircle(SELECTED, radius = (hr - 3f).coerceAtLeast(1f), center = h)
                                }
                            }
                        }
                        val s = dragStart; val e = dragNow
                        if (addMode && s != null && e != null) {
                            drawRect(
                                color = SELECTED,
                                topLeft = Offset(min(s.x, e.x), min(s.y, e.y)),
                                size = Size(abs(e.x - s.x), abs(e.y - s.y)),
                                style = Stroke(width = 3f),
                            )
                        }
                    }
                }
                if (addMode && !decided) {
                    Text(
                        "Drag on the image to add a vehicle box",
                        Modifier.align(Alignment.TopCenter).background(Color(0xCC000000)).padding(6.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (zoomScale > 1f) {
                    Text(
                        "%.1f×".format(zoomScale),
                        Modifier.align(Alignment.BottomStart).background(Color(0x73000000)).padding(6.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            BoxPanel(
                boxes = boxes,
                classes = classes,
                categories = categories,
                editable = !decided,
                selected = selected,
                onSelect = { selected = if (selected == it) null else it },
                onPick = { i, classId -> vm.setBoxClass(i, classId) },
                onDelete = { vm.deleteBox(it); if (selected == it) selected = null },
            )
        }
    }

    if (showReject) {
        AlertDialog(
            onDismissRequest = { showReject = false },
            title = { Text("Reject image") },
            text = {
                Column {
                    Text("Optional reason:")
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showReject = false; vm.reject(reason) }) { Text("Reject") }
            },
            dismissButton = { TextButton(onClick = { showReject = false }) { Text("Cancel") } },
        )
    }

    if (showJump) {
        ModalBottomSheet(onDismissRequest = { showJump = false }) {
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                itemsIndexed(session.manifest.items) { i, mi ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when (mi.status) {
                                ItemStatus.ACCEPTED -> "✓"
                                ItemStatus.REJECTED -> "✗"
                                ItemStatus.PENDING -> "•"
                            },
                            Modifier.width(24.dp),
                        )
                        TextButton(onClick = { showJump = false; vm.jumpTo(i) }) {
                            Text("${i + 1}. ${mi.subset}/${mi.basename}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxPanel(
    boxes: List<YoloBox>,
    classes: List<Int?>,
    categories: VehicleCategories?,
    editable: Boolean,
    selected: Int?,
    onSelect: (Int) -> Unit,
    onPick: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    if (boxes.isEmpty()) {
        Text(
            "No boxes on this image.",
            Modifier.fillMaxWidth().padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        return
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
        itemsIndexed(boxes) { i, _ ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { onSelect(i) }) {
                    Text("Box ${i + 1}${if (i == selected) " •" else ""}")
                }
                Spacer(Modifier.weight(1f))
                ClassDropdown(
                    categories = categories,
                    selectedId = classes.getOrNull(i),
                    enabled = editable,
                    onPick = { onPick(i, it) },
                )
                if (editable) {
                    IconButton(onClick = { onDelete(i) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete box ${i + 1}")
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassDropdown(
    categories: VehicleCategories?,
    selectedId: Int?,
    enabled: Boolean,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text(
                selectedId?.let { categories?.labelFor(it) ?: "Class $it" } ?: "Choose type…",
                color = if (selectedId == null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            categories?.classes?.forEach { c ->
                DropdownMenuItem(text = { Text(c.label) }, onClick = { onPick(c.id); open = false })
            }
        }
    }
}

/** `(message, isError)` for the bottom-bar hint, or null when nothing needs saying. */
private fun reviewHint(item: ManifestItem, boxes: List<YoloBox>, allClassified: Boolean): Pair<String, Boolean>? =
    when {
        item.status == ItemStatus.ACCEPTED -> "Accepted — read-only (Release the package to redo)" to false
        item.status == ItemStatus.REJECTED ->
            ("Rejected${item.reason?.let { ": $it" } ?: ""} — read-only") to false
        boxes.isEmpty() -> "No boxes — this image must be Rejected, not Accepted." to true
        !allClassified -> "Choose a type for every box before accepting." to true
        else -> null
    }

private fun fitRect(canvas: IntSize, imgW: Int, imgH: Int): Rect {
    if (canvas.width == 0 || canvas.height == 0 || imgW == 0 || imgH == 0) return Rect(0f, 0f, 0f, 0f)
    val scale = min(canvas.width / imgW.toFloat(), canvas.height / imgH.toFloat())
    val dw = imgW * scale
    val dh = imgH * scale
    val ox = (canvas.width - dw) / 2f
    val oy = (canvas.height - dh) / 2f
    return Rect(ox, oy, ox + dw, oy + dh)
}

/** A canvas-pixel point → normalized `[0, 1]` image coordinates (REQ-024 hit-testing reuses this). */
private fun normalizedPoint(p: Offset, rect: Rect): Offset {
    if (rect.width <= 0f || rect.height <= 0f) return Offset(0f, 0f)
    val x = ((p.x - rect.left) / rect.width).coerceIn(0f, 1f)
    val y = ((p.y - rect.top) / rect.height).coerceIn(0f, 1f)
    return Offset(x, y)
}

/** Two canvas-pixel corners → a normalized YOLO box, or null if too small. */
private fun toNormalizedBox(a: Offset, b: Offset, rect: Rect): FloatArray? {
    if (rect.width <= 0f || rect.height <= 0f) return null
    val p1 = normalizedPoint(a, rect); val p2 = normalizedPoint(b, rect)
    val x1 = min(p1.x, p2.x); val x2 = max(p1.x, p2.x)
    val y1 = min(p1.y, p2.y); val y2 = max(p1.y, p2.y)
    val w = x2 - x1; val h = y2 - y1
    if (w < 0.01f || h < 0.01f) return null
    return floatArrayOf((x1 + x2) / 2f, (y1 + y2) / 2f, w, h)
}

private operator fun FloatArray.component1() = this[0]
private operator fun FloatArray.component2() = this[1]
private operator fun FloatArray.component3() = this[2]
private operator fun FloatArray.component4() = this[3]
