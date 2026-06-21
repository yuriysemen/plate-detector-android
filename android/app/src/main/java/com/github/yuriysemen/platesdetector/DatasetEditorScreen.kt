package com.github.yuriysemen.platesdetector

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DatasetEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val editor = remember { DatasetEditor(context) }
    val scope = rememberCoroutineScope()

    var frames by remember { mutableStateOf<List<FrameEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var inSelectionMode by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var openFrame by remember { mutableStateOf<FrameEntry?>(null) }

    fun refresh() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { editor.loadFrames() }
            frames = loaded
            selectedNames = selectedNames.filter { n -> loaded.any { it.name == n } }.toSet()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // Show frame detail screen if a frame is open
    val frameToOpen = openFrame
    if (frameToOpen != null) {
        FrameDetailScreen(
            frame = frameToOpen,
            editor = editor,
            onBack = { openFrame = null; refresh() }
        )
        return
    }

    BackHandler {
        if (inSelectionMode) {
            inSelectionMode = false
            selectedNames = emptySet()
        } else {
            onBack()
        }
    }

    if (showDeleteDialog) {
        val count = selectedNames.size
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete $count ${if (count == 1) "frame" else "frames"}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    val toDelete = frames.filter { it.name in selectedNames }
                    scope.launch {
                        withContext(Dispatchers.IO) { editor.deleteFrames(toDelete) }
                        inSelectionMode = false
                        refresh()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (inSelectionMode) {
                    IconButton(onClick = { inSelectionMode = false; selectedNames = emptySet() }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                    }
                    Text(
                        "${selectedNames.size} selected",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        enabled = selectedNames.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                    }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        "Edit dataset — ${frames.size} frames",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                frames.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No frames collected yet.", color = MaterialTheme.colorScheme.outline)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(frames, key = { it.name }) { frame ->
                        val isSelected = frame.name in selectedNames
                        FrameThumbnailCell(
                            frame = frame,
                            editor = editor,
                            isSelected = isSelected,
                            inSelectionMode = inSelectionMode,
                            onClick = {
                                if (inSelectionMode) {
                                    selectedNames = if (isSelected) selectedNames - frame.name else selectedNames + frame.name
                                } else {
                                    openFrame = frame
                                }
                            },
                            onLongClick = {
                                inSelectionMode = true
                                selectedNames = setOf(frame.name)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameThumbnailCell(
    frame: FrameEntry,
    editor: DatasetEditor,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bitmap by produceState<Bitmap?>(null, frame.name) {
        value = withContext(Dispatchers.IO) { editor.loadBitmap(frame.imageFile) }
    }

    Box(
        modifier = Modifier
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(4.dp))
            .border(
                width = if (isSelected) 3.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        val bmp = bitmap
        if (bmp != null) {
            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val bmpW = bmp.width.toFloat()
                val bmpH = bmp.height.toFloat()
                val s = minOf(size.width / bmpW, size.height / bmpH)
                val dispW = bmpW * s
                val dispH = bmpH * s
                val offX = (size.width - dispW) / 2f
                val offY = (size.height - dispH) / 2f

                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset(offX.toInt(), offY.toInt()),
                    dstSize = IntSize(dispW.toInt(), dispH.toInt())
                )

                val stroke = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round)
                for (box in frame.boxes) {
                    val l = offX + (box.xCenter - box.width / 2f) * dispW
                    val t = offY + (box.yCenter - box.height / 2f) * dispH
                    val w = box.width * dispW
                    val h = box.height * dispH
                    drawRect(color = Color(0xFF00E676), topLeft = Offset(l, t), size = Size(w, h), style = stroke)
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        if (inSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                "#${frame.name.takeLast(8)} · ${frame.boxes.size} box${if (frame.boxes.size != 1) "es" else ""}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
