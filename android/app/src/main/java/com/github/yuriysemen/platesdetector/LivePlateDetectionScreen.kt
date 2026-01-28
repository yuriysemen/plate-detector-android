package com.github.yuriysemen.platesdetector

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.min
import androidx.core.content.edit
import java.nio.ByteBuffer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu

// ------------------------
// Model listing & prefs
// ------------------------

private data class ModelSpec(
    val id: String,                // file name without extension
    val title: String,             // same as id
    val source: ModelSource,       // asset or external file
    val coordFormat: CoordFormat,  // fixed
    val conf: Float                // threshold, editable in picker
)

private object ModelPrefs {
    private const val PREFS = "model_prefs"
    private const val KEY_MODEL_ID = "selected_model_id"
    private const val KEY_EXTERNAL_URIS = "external_model_uris"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getSelectedId(context: Context): String? =
        prefs(context).getString(KEY_MODEL_ID, null)

    fun setSelectedId(context: Context, id: String) {
        prefs(context).edit { putString(KEY_MODEL_ID, id) }
    }

    fun clearSelected(context: Context) {
        prefs(context).edit { remove(KEY_MODEL_ID) }
    }

    private fun externalId(uriString: String) = "external:$uriString"

    fun externalIdForUri(uriString: String) = externalId(uriString)

    fun getExternalUris(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EXTERNAL_URIS, emptySet())?.toSet().orEmpty()

    fun addExternalUri(context: Context, uriString: String) {
        val updated = getExternalUris(context).toMutableSet()
        updated.add(uriString)
        prefs(context).edit { putStringSet(KEY_EXTERNAL_URIS, updated) }
    }

    fun removeExternalUri(context: Context, uriString: String) {
        val updated = getExternalUris(context).toMutableSet()
        updated.remove(uriString)
        prefs(context).edit { putStringSet(KEY_EXTERNAL_URIS, updated) }
    }

    // conf per model, default 0.5
    private fun confKey(id: String) = "conf_$id"

    fun getConf(context: Context, id: String): Float =
        prefs(context).getFloat(confKey(id), 0.5f)

    fun setConf(context: Context, id: String, conf: Float) {
        prefs(context).edit { putFloat(confKey(id), conf) }
    }
}

private fun availableModels(context: Context): List<ModelSpec> {
    fun listTflite(dir: String?): List<Pair<String, String>> {
        // returns list of (fileName, assetPath)
        return try {
            val files = context.assets.list(dir ?: "").orEmpty()
            files
                .filter { it.endsWith(".tflite", ignoreCase = true) }
                .sorted()
                .map { file ->
                    val path = if (dir.isNullOrEmpty()) file else "$dir/$file"
                    file to path
                }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    val inModelsDir = listTflite("models")
    val inRoot = if (inModelsDir.isEmpty()) listTflite(null) else emptyList()

    val all = (inModelsDir + inRoot)
        // avoid duplicates if any
        .distinctBy { it.second }

    val assetModels = all.map { (fileName, assetPath) ->
        val id = fileName.substringBeforeLast(".")
        ModelSpec(
            id = id,
            title = id,
            source = ModelSource.Asset(assetPath),
            coordFormat = CoordFormat.XYXY_SCORE_CLASS,
            conf = ModelPrefs.getConf(context, id)
        )
    }

    val externalModels = ModelPrefs.getExternalUris(context)
        .sorted()
        .mapNotNull { uriString ->
            runCatching { Uri.parse(uriString) }.getOrNull()
        }
        .map { uri ->
            val uriString = uri.toString()
            if (!isValidTfliteModel(context, uri)) {
                Log.w("ModelPicker", "Invalid model file, removing from prefs: $uriString")
                ModelPrefs.removeExternalUri(context, uriString)
                return@map null
            }
            val displayName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: uriString
            val id = ModelPrefs.externalIdForUri(uriString)
            ModelSpec(
                id = id,
                title = displayName,
                source = ModelSource.ContentUri(uri),
                coordFormat = CoordFormat.XYXY_SCORE_CLASS,
                conf = ModelPrefs.getConf(context, id)
            )
        }
        .filterNotNull()

    return assetModels + externalModels
}

private val cocoClassNames = listOf(
    "person",
    "bicycle",
    "car",
    "motorcycle",
    "airplane",
    "bus",
    "train",
    "truck",
    "boat",
    "traffic light",
    "fire hydrant",
    "stop sign",
    "parking meter",
    "bench",
    "bird",
    "cat",
    "dog",
    "horse",
    "sheep",
    "cow",
    "elephant",
    "bear",
    "zebra",
    "giraffe",
    "backpack",
    "umbrella",
    "handbag",
    "tie",
    "suitcase",
    "frisbee",
    "skis",
    "snowboard",
    "sports ball",
    "kite",
    "baseball bat",
    "baseball glove",
    "skateboard",
    "surfboard",
    "tennis racket",
    "bottle",
    "wine glass",
    "cup",
    "fork",
    "knife",
    "spoon",
    "bowl",
    "banana",
    "apple",
    "sandwich",
    "orange",
    "broccoli",
    "carrot",
    "hot dog",
    "pizza",
    "donut",
    "cake",
    "chair",
    "couch",
    "potted plant",
    "bed",
    "dining table",
    "toilet",
    "tv",
    "laptop",
    "mouse",
    "remote",
    "keyboard",
    "cell phone",
    "microwave",
    "oven",
    "toaster",
    "sink",
    "refrigerator",
    "book",
    "clock",
    "vase",
    "scissors",
    "teddy bear",
    "hair drier",
    "toothbrush"
)

private fun classNameFor(classId: Int): String =
    cocoClassNames.getOrNull(classId) ?: "class $classId"

private fun classColorFor(classId: Int): Color {
    val hue = (classId * 37) % 360
    return Color.hsv(hue.toFloat(), 0.85f, 0.95f)
}

private fun isValidTfliteModel(context: Context, uri: Uri): Boolean {
    return runCatching {
        val buffer = loadUriBytes(context, uri)
        val interpreter = Interpreter(buffer)
        try {
            // Constructor validates the flatbuffer; no-op here to avoid API mismatches.
        } finally {
            interpreter.close()
        }
        true
    }.getOrElse { false }
}

private fun loadUriBytes(context: Context, uri: Uri): ByteBuffer {
    return runCatching {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Cannot open $uri")
        java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
            val channel = fis.channel
            channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, pfd.statSize)
        }
    }.getOrElse {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot read $uri")
        java.nio.ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        }
    }.getOrNull()
}

// ------------------------
// Public entry composable
// ------------------------

@Composable
fun LivePlateDetectionScreen() {
    val context = LocalContext.current

    // allow "retry" (even though assets won't change at runtime, it prevents stale UI)
    var reloadKey by rememberSaveable { mutableIntStateOf(0) }

    val models = remember(reloadKey) { availableModels(context) }

    var selectedId by rememberSaveable {
        mutableStateOf(ModelPrefs.getSelectedId(context))
    }

    // If first launch and nothing selected, open settings.
    var showSettings by rememberSaveable { mutableStateOf(selectedId == null) }

    fun handlePickedUri(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (!isValidTfliteModel(context, uri)) {
            Log.w("ModelPicker", "Selected file is not a valid TFLite model: $uri")
            return
        }
        val uriString = uri.toString()
        ModelPrefs.addExternalUri(context, uriString)
        val id = ModelPrefs.externalIdForUri(uriString)
        ModelPrefs.setSelectedId(context, id)
        selectedId = id
        showSettings = false
        reloadKey++
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            handlePickedUri(uri)
        }
    }

    // If no models: show error and DO NOT init any detector.
    if (models.isEmpty()) {
        NoModelsScreen(
            onRetry = { reloadKey++ },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) }
        )
        return
    }

    val selected = models.firstOrNull { it.id == selectedId }

    if (showSettings || selected == null) {
        SettingsScreen(
            models = models,
            onPick = { spec ->
                ModelPrefs.setSelectedId(context, spec.id)
                selectedId = spec.id
                showSettings = false
            },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) }
        )
    } else {
        // Important: spec.conf may change in prefs in picker, so load fresh conf for runtime.
        val runtimeSpec = selected.copy(conf = ModelPrefs.getConf(context, selected.id))

        LiveDetectionUi(
            spec = runtimeSpec,
            onOpenSettings = { showSettings = true }
        )
    }
}

// ------------------------
// UI screens
// ------------------------

@Composable
private fun NoModelsScreen(onRetry: () -> Unit, onPickFile: () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = Color.Black,
        contentColor = Color.White
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("No TFLite models found", style = MaterialTheme.typography.titleLarge)
            Text(
                "The app did not find any *.tflite models in:\n" +
                        "1) app/src/main/assets/models/\n" +
                        "2) app/src/main/assets/\n\n" +
                        "Add at least one model file (e.g. yolo11n_640.tflite) and rebuild the app,\n" +
                        "or pick a model file from your device.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(8.dp))

            OutlinedButton(onClick = onRetry) {
                Text("Retry")
            }

            Button(onClick = onPickFile) {
                Text("Pick model file")
            }

            Text(
                "Note: If you load models via assets, consider adding noCompress for .tflite in Gradle.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    models: List<ModelSpec>,
    onPick: (ModelSpec) -> Unit,
    onPickFile: () -> Unit
) {
    var selectedId by rememberSaveable { mutableStateOf(models.first().id) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = Color.Black,
        contentColor = Color.White
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            Text("Change model", style = MaterialTheme.typography.titleMedium)

            OutlinedButton(onClick = onPickFile) {
                Text("Add model from file")
            }

            models.forEach { m ->
                OutlinedCard(
                    onClick = { selectedId = m.id },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(
                            selected = (selectedId == m.id),
                            onClick = { selectedId = m.id }
                        )
                        Column {
                            Text(m.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                sourceLabel(m),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(onClick = {
                val base = models.first { it.id == selectedId }
                onPick(base)
            }) {
                Text("Use selected model")
            }
        }
    }
}

@Composable
private fun LiveDetectionUi(
    spec: ModelSpec,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Create detector only here (never when models are absent)
    val detector = remember(spec.id) {
        PlateDetector(
            context = context,
            modelSource = spec.source,
            coordFormat = spec.coordFormat,
            debugLogs = true
        )
    }
    DisposableEffect(spec.id) {
        onDispose { detector.close() }
    }

    var lastDetections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var lastFrameW by remember { mutableStateOf(0) }
    var lastFrameH by remember { mutableStateOf(0) }
    var lastMs by remember { mutableStateOf(0L) }
    var hadDetections by remember { mutableStateOf(false) }
    var lastBeepAt by remember { mutableStateOf(0L) }

    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }
    DisposableEffect(Unit) {
        onDispose { toneGenerator.release() }
    }
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            isAntiAlias = true
            style = Paint.Style.FILL
        }
    }
    val labelBackgroundPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(160, 0, 0, 0)
            style = Paint.Style.FILL
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = Color.Black,
        contentColor = Color.White
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (!hasPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(WindowInsets.safeDrawing.asPaddingValues())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Camera permission required.")
                    OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Request permission")
                    }
                }
            } else {
                // Key by model id to ensure full rebind for analyzer when model changes
                key(spec.id) {
                    CameraPreviewWithAnalysis(
                        detector = detector,
                        scoreThreshold = spec.conf,
                        onResult = { dets, w, h, ms ->
                            val now = SystemClock.elapsedRealtime()
                            val shouldBeep = dets.isNotEmpty() &&
                                (!hadDetections || now - lastBeepAt >= 1_000L)
                            if (shouldBeep) {
                                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                                lastBeepAt = now
                            }
                            hadDetections = dets.isNotEmpty()
                            lastDetections = dets
                            lastFrameW = w
                            lastFrameH = h
                            lastMs = ms
                        }
                    )
                }

                // Overlay
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (lastFrameW <= 0 || lastFrameH <= 0) return@Canvas

                    val viewW = size.width
                    val viewH = size.height

                    // Fit-center mapping (matches PreviewView.ScaleType.FIT_CENTER)
                    val scale = min(viewW / lastFrameW, viewH / lastFrameH)
                    val dispW = lastFrameW * scale
                    val dispH = lastFrameH * scale
                    val offX = (viewW - dispW) / 2f
                    val offY = (viewH - dispH) / 2f

                    val stroke = Stroke(width = 3.dp.toPx())
                    val labelPadding = 4.dp.toPx()
                    val labelTextSize = 14.dp.toPx()

                    for (det in lastDetections) {
                        val classColor = classColorFor(det.classId)
                        val left = offX + det.leftPx * scale
                        val top = offY + det.topPx * scale
                        val right = offX + det.rightPx * scale
                        val bottom = offY + det.bottomPx * scale

                        drawRect(
                            color = classColor,
                            topLeft = Offset(left, top),
                            size = Size(right - left, bottom - top),
                            style = stroke
                        )

                        val label = "${classNameFor(det.classId)} ${(det.score * 100).toInt()}%"
                        drawIntoCanvas { canvas ->
                            labelPaint.textSize = labelTextSize
                            val textWidth = labelPaint.measureText(label)
                            val fontMetrics = labelPaint.fontMetrics
                            val textHeight = fontMetrics.descent - fontMetrics.ascent
                            val textLeft = left.coerceAtLeast(0f)
                            val textBoxTop = (top - textHeight - labelPadding * 2)
                                .coerceAtLeast(0f)
                            val textBoxBottom = (textBoxTop + textHeight + labelPadding * 2)
                                .coerceAtMost(viewH)
                            val textBoxRight = (textLeft + textWidth + labelPadding * 2)
                                .coerceAtMost(viewW)
                            val textBaseline = (textBoxTop + labelPadding - fontMetrics.ascent)
                                .coerceAtMost(viewH)

                            canvas.nativeCanvas.drawRect(
                                textLeft,
                                textBoxTop,
                                textBoxRight,
                                textBoxBottom,
                                labelBackgroundPaint
                            )
                            canvas.nativeCanvas.drawText(
                                label,
                                textLeft + labelPadding,
                                textBaseline,
                                labelPaint
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(WindowInsets.safeDrawing.asPaddingValues())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open settings",
                        tint = Color.White
                    )
                }

                Text(
                    text = if (lastFrameW > 0 && lastFrameH > 0)
                        "Detected: ${lastDetections.size} | ${lastMs} ms"
                    else
                        "Detected: —",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
        }
    }
}

// ------------------------
// CameraX preview + analysis
// ------------------------

@Composable
private fun CameraPreviewWithAnalysis(
    detector: PlateDetector,
    scoreThreshold: Float,
    onResult: (List<Detection>, Int, Int, Long) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val detectorState by rememberUpdatedState(detector)
    val thresholdState by rememberUpdatedState(scoreThreshold)
    val onResultState by rememberUpdatedState(onResult)

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        val listener = Runnable {
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val throttleMs = 120L
            var lastRun = 0L
            var busy = false

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                val now = SystemClock.elapsedRealtime()
                var bmp: Bitmap? = null
                var rotated: Bitmap? = null

                try {
                    val shouldProcess = !busy && (now - lastRun >= throttleMs)
                    if (!shouldProcess) return@setAnalyzer

                    busy = true
                    lastRun = now

                    val t0 = System.nanoTime()

                    bmp = imageProxy.toBitmapSafe()
                    rotated = bmp.rotate(imageProxy.imageInfo.rotationDegrees)

                    val dets = detectorState.detectAll(
                        rotated,
                        scoreThreshold = thresholdState
                    )

                    val ms = (System.nanoTime() - t0) / 1_000_000

                    mainExecutor.execute {
                        onResultState(dets, rotated.width, rotated.height, ms)
                    }
                } catch (t: Throwable) {
                    Log.e("CameraAnalysis", "Analyzer failed", t)
                } finally {
                    busy = false
                    imageProxy.close()

                    // reduce memory pressure, avoid double recycle
                    runCatching {
                        if (rotated != null && rotated !== bmp) rotated.recycle()
                    }
                    runCatching { bmp?.recycle() }
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        }

        cameraProviderFuture.addListener(listener, mainExecutor)

        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

// ------------------------
// Image utilities
// ------------------------

private fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
}

/**
 * CameraX ImageProxy -> Bitmap conversion (YUV_420_888).
 * Works without extra dependencies. Good for demo/testing.
 */
private fun ImageProxy.toBitmapSafe(jpegQuality: Int = 90): Bitmap {
    val nv21 = yuv420888ToNv21(this)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), jpegQuality, out)
    val imageBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
    val width = image.width
    val height = image.height

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride

    val uRowStride = uPlane.rowStride
    val uPixelStride = uPlane.pixelStride

    val vRowStride = vPlane.rowStride
    val vPixelStride = vPlane.pixelStride

    val nv21 = ByteArray(width * height + (width * height / 2))
    var pos = 0

    // Copy Y
    for (row in 0 until height) {
        val yRowStart = row * yRowStride
        for (col in 0 until width) {
            nv21[pos++] = yBuffer.get(yRowStart + col * yPixelStride)
        }
    }

    // Copy VU (NV21) from chroma planes (subsampled 2x2)
    val chromaHeight = height / 2
    val chromaWidth = width / 2
    for (row in 0 until chromaHeight) {
        val uRowStart = row * uRowStride
        val vRowStart = row * vRowStride
        for (col in 0 until chromaWidth) {
            val uIndex = uRowStart + col * uPixelStride
            val vIndex = vRowStart + col * vPixelStride
            nv21[pos++] = vBuffer.get(vIndex) // V
            nv21[pos++] = uBuffer.get(uIndex) // U
        }
    }

    return nv21
}

private fun sourceLabel(model: ModelSpec): String {
    return when (val source = model.source) {
        is ModelSource.Asset -> "asset: ${source.path}"
        is ModelSource.ContentUri -> "file: ${model.title}"
    }
}
