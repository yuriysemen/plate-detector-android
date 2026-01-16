package com.github.yuriysemen.platesdetector

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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

    // If first launch and nothing selected, open picker.
    var showPicker by rememberSaveable { mutableStateOf(selectedId == null) }

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
        showPicker = false
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

    if (showPicker || selected == null) {
        ModelPickerScreen(
            models = models,
            onPick = { spec ->
                ModelPrefs.setSelectedId(context, spec.id)
                selectedId = spec.id
                showPicker = false
            },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) }
        )
    } else {
        // Important: spec.conf may change in prefs in picker, so load fresh conf for runtime.
        val runtimeSpec = selected.copy(conf = ModelPrefs.getConf(context, selected.id))

        LiveDetectionUi(
            spec = runtimeSpec,
            onChangeModel = {
                ModelPrefs.clearSelected(context)
                selectedId = null
                showPicker = true
            }
        )
    }
}

// ------------------------
// UI screens
// ------------------------

@Composable
private fun NoModelsScreen(onRetry: () -> Unit, onPickFile: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
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
private fun ModelPickerScreen(
    models: List<ModelSpec>,
    onPick: (ModelSpec) -> Unit,
    onPickFile: () -> Unit
) {
    val context = LocalContext.current

    var selectedId by rememberSaveable { mutableStateOf(models.first().id) }
    var conf by rememberSaveable { mutableStateOf(0.5f) }

    // when selected model changes, load its saved conf (default 0.5)
    LaunchedEffect(selectedId) {
        conf = ModelPrefs.getConf(context, selectedId)
    }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Select model", style = MaterialTheme.typography.titleLarge)

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

            HorizontalDivider()

            Text(
                text = "Confidence threshold: ${"%.2f".format(conf)}",
                style = MaterialTheme.typography.titleMedium
            )

            Slider(
                value = conf,
                onValueChange = { conf = it },
                valueRange = 0.05f..0.95f
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        conf = 0.5f
                        ModelPrefs.setConf(context, selectedId, conf)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Default 0.50") }

                Button(
                    onClick = {
                        ModelPrefs.setConf(context, selectedId, conf)
                        val base = models.first { it.id == selectedId }
                        onPick(base.copy(conf = conf))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Continue") }
            }
        }
    }
}

@Composable
private fun LiveDetectionUi(
    spec: ModelSpec,
    onChangeModel: () -> Unit
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

    var enabled by rememberSaveable { mutableStateOf(true) }

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

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Model: ${spec.title}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (lastFrameW > 0 && lastFrameH > 0)
                            "Detected: ${lastDetections.size} | ${lastMs} ms | Frame: ${lastFrameW}x${lastFrameH} | conf≥${
                                "%.2f".format(
                                    spec.conf
                                )
                            }"
                        else
                            "Detected: — | conf≥${"%.2f".format(spec.conf)}"
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onChangeModel) {
                            Text("Change model")
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (!hasPermission) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Camera permission required.")
                        OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Request permission")
                        }
                    }
                } else {
                    // Key by model id to ensure full rebind for analyzer when model changes
                    key(spec.id) {
                        CameraPreviewWithAnalysis(
                            enabled = enabled,
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

                        for (det in lastDetections) {
                            val left = offX + det.leftPx * scale
                            val top = offY + det.topPx * scale
                            val right = offX + det.rightPx * scale
                            val bottom = offY + det.bottomPx * scale

                            drawRect(
                                color = Color.Green,
                                topLeft = Offset(left, top),
                                size = Size(right - left, bottom - top),
                                style = stroke
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { enabled = !enabled },
                    modifier = Modifier.weight(1f)
                ) { Text(if (enabled) "Pause" else "Resume") }

                OutlinedButton(
                    onClick = { lastDetections = emptyList() },
                    modifier = Modifier.weight(1f)
                ) { Text("Reset") }
            }
        }
    }
}

// ------------------------
// CameraX preview + analysis
// ------------------------

@Composable
private fun CameraPreviewWithAnalysis(
    enabled: Boolean,
    detector: PlateDetector,
    scoreThreshold: Float,
    onResult: (List<Detection>, Int, Int, Long) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Avoid stale captures inside analyzer
    val enabledState by rememberUpdatedState(enabled)
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
                    val shouldProcess = enabledState && !busy && (now - lastRun >= throttleMs)
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
