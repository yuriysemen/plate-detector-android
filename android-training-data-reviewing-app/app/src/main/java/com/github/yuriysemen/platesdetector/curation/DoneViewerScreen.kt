package com.github.yuriysemen.platesdetector.curation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val BOX_COLOR = Color(0xFF00E5FF)
private val SUBSETS = listOf("train", "val", "test")

/**
 * Read-only viewer for a Done package's accepted images (REQ-036) — no Accept/Reject/box-editing
 * anywhere here, since there's nothing left to decide once a package is completed. [data] is
 * already downloaded/unzipped by [CurationViewModel.openDoneViewer]; this screen only displays it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoneViewerScreen(data: DoneViewerData, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var openImage by remember { mutableStateOf<DoneViewImage?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(data.filename, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (data.images.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing was accepted into this package.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SUBSETS.forEach { subset ->
                    val subsetImages = data.images.filter { it.subset == subset }
                    if (subsetImages.isEmpty()) return@forEach
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "$subset (${subsetImages.size})",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(subsetImages) { image ->
                        ThumbnailCell(image, onClick = { openImage = image })
                    }
                }
            }
        }
    }

    openImage?.let { image ->
        FullImageDialog(image, onDismiss = { openImage = null })
    }
}

@Composable
private fun ThumbnailCell(image: DoneViewImage, onClick: () -> Unit) {
    var bitmap by remember(image.imageFile) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(image.imageFile) {
        bitmap = withContext(Dispatchers.IO) {
            decodeSampledBitmap(image.imageFile, THUMBNAIL_TARGET_PX)?.asImageBitmap()
        }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .background(Color.DarkGray)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun FullImageDialog(image: DoneViewImage, onDismiss: () -> Unit) {
    var bitmap by remember(image.imageFile) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(image.imageFile) {
        bitmap = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(image.imageFile.absolutePath)?.asImageBitmap()
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val rect = fitRect(IntSize(size.width.toInt(), size.height.toInt()), bmp.width, bmp.height)
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                    )
                    image.boxes.forEach { b ->
                        drawRect(
                            color = BOX_COLOR,
                            topLeft = Offset(
                                rect.left + (b.xCenter - b.width / 2f) * rect.width,
                                rect.top + (b.yCenter - b.height / 2f) * rect.height,
                            ),
                            size = Size(b.width * rect.width, b.height * rect.height),
                            style = Stroke(width = 3f),
                        )
                    }
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

private const val THUMBNAIL_TARGET_PX = 200

/** Decodes [file] downsampled to roughly [reqSize] px on the long side, to keep a grid of
 *  potentially hundreds of thumbnails from decoding every image at full resolution. */
private fun decodeSampledBitmap(file: File, reqSize: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var inSampleSize = 1
    while (bounds.outWidth / 2 / inSampleSize >= reqSize && bounds.outHeight / 2 / inSampleSize >= reqSize) {
        inSampleSize *= 2
    }
    val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}
