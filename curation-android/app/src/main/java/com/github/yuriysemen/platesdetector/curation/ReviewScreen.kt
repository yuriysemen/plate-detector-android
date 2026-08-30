package com.github.yuriysemen.platesdetector.curation

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: CurationViewModel, onBack: () -> Unit) {
    val session = vm.session ?: return
    BackHandler(onBack = onBack)

    val item = session.currentItem
    val cached = session.current
    val decided = item.status != ItemStatus.PENDING

    // Label content: accepted items carry their saved content in the manifest; otherwise read
    // the on-disk label file from the local unzip cache.
    val labelText = remember(session.index, item.status) {
        item.labelContent ?: cached.labelFile.takeIf { it.exists() }?.readText()
    }
    val boxes = remember(labelText) { YoloLabel.parse(labelText) }

    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(session.index) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(cached.imageFile.absolutePath)?.asImageBitmap() }
                .getOrNull()
        }
    }

    var showJump by remember { mutableStateOf(false) }
    var showReject by remember { mutableStateOf(false) }
    var reason by remember(session.index) { mutableStateOf("") }

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
                    IconButton(onClick = { showJump = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Jump to item")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                // Scaffold does not inset a custom bottomBar for the system nav bar — do it here.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val hint = when {
                        item.status == ItemStatus.ACCEPTED ->
                            "Accepted — read-only (Release the package to redo)"
                        item.status == ItemStatus.REJECTED ->
                            "Rejected${item.reason?.let { ": $it" } ?: ""} — read-only"
                        boxes.isEmpty() ->
                            "No boxes — this image must be Rejected, not Accepted."
                        else -> null
                    }
                    hint?.let {
                        Text(
                            it,
                            Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!decided && boxes.isEmpty()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { vm.navigate(-1) },
                            enabled = session.index > 0,
                        ) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous") }

                        OutlinedButton(
                            onClick = { showReject = true },
                            enabled = !decided,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Reject") }

                        Button(
                            onClick = {
                                vm.decide(
                                    ItemStatus.ACCEPTED,
                                    labelContent = labelText ?: "",
                                    reason = null,
                                )
                            },
                            enabled = !decided && boxes.isNotEmpty(),
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
        Box(
            Modifier.fillMaxSize().padding(padding).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp == null) {
                Text("Loading image…", color = Color.White)
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val iw = bmp.width.toFloat()
                    val ih = bmp.height.toFloat()
                    val scale = min(size.width / iw, size.height / ih)
                    val dw = iw * scale
                    val dh = ih * scale
                    val ox = (size.width - dw) / 2f
                    val oy = (size.height - dh) / 2f
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                        dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                    )
                    boxes.forEach { b ->
                        val left = ox + (b.xCenter - b.width / 2f) * dw
                        val top = oy + (b.yCenter - b.height / 2f) * dh
                        drawRect(
                            color = Color(0xFF00E5FF),
                            topLeft = Offset(left, top),
                            size = Size(b.width * dw, b.height * dh),
                            style = Stroke(width = 3f),
                        )
                    }
                }
            }
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
                TextButton(onClick = {
                    showReject = false
                    vm.decide(ItemStatus.REJECTED, labelContent = null, reason = reason)
                }) { Text("Reject") }
            },
            dismissButton = { TextButton(onClick = { showReject = false }) { Text("Cancel") } },
        )
    }

    if (showJump) {
        ModalBottomSheet(onDismissRequest = { showJump = false }) {
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                itemsIndexed(session.manifest.items) { i, mi ->
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
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
