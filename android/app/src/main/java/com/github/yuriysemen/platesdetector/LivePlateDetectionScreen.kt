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
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.content.edit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.runtime.rememberCoroutineScope
import android.widget.Toast
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

// ------------------------
// Model listing & prefs
// ------------------------

private object ModelPrefs {
    private const val PREFS = "model_prefs"
    private const val KEY_MODEL_ID = "selected_model_id"
    private const val KEY_EXTERNAL_URIS = "external_model_uris"
    private const val KEY_SHOW_LABELS = "show_class_labels"

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

    fun clearConf(context: Context, id: String) {
        prefs(context).edit { remove(confKey(id)) }
    }

    fun getShowLabels(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_LABELS, false)

    fun setShowLabels(context: Context, show: Boolean) {
        prefs(context).edit { putBoolean(KEY_SHOW_LABELS, show) }
    }

    private const val KEY_ANALYSIS_RESOLUTION = "analysis_resolution"

    fun getAnalysisResolution(context: Context): AnalysisResolution =
        runCatching {
            AnalysisResolution.valueOf(
                prefs(context).getString(KEY_ANALYSIS_RESOLUTION, null) ?: ""
            )
        }.getOrDefault(AnalysisResolution.DEFAULT)

    fun setAnalysisResolution(context: Context, res: AnalysisResolution) {
        prefs(context).edit { putString(KEY_ANALYSIS_RESOLUTION, res.name) }
    }

    private const val KEY_SCAN_INTERVAL_MS = "scan_interval_ms"
    const val DEFAULT_SCAN_INTERVAL_MS = 1000

    fun getScanIntervalMs(context: Context): Int =
        prefs(context).getInt(KEY_SCAN_INTERVAL_MS, DEFAULT_SCAN_INTERVAL_MS)

    fun setScanIntervalMs(context: Context, ms: Int) {
        prefs(context).edit { putInt(KEY_SCAN_INTERVAL_MS, ms) }
    }

    private const val KEY_COLLECT_TRAINING = "collect_training_data"

    fun getCollectTrainingData(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COLLECT_TRAINING, false)

    fun setCollectTrainingData(context: Context, enable: Boolean) {
        prefs(context).edit { putBoolean(KEY_COLLECT_TRAINING, enable) }
    }

    private const val KEY_COLLECT_FIRST_TIME_SHOWN = "collect_first_time_shown"

    fun getCollectFirstTimeShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COLLECT_FIRST_TIME_SHOWN, false)

    fun setCollectFirstTimeShown(context: Context, shown: Boolean) {
        prefs(context).edit { putBoolean(KEY_COLLECT_FIRST_TIME_SHOWN, shown) }
    }

    private const val KEY_STORAGE_QUOTA_MB = "training_data_quota_mb"
    const val DEFAULT_QUOTA_MB = 500

    fun getStorageQuotaMb(context: Context): Int =
        prefs(context).getInt(KEY_STORAGE_QUOTA_MB, DEFAULT_QUOTA_MB).coerceAtLeast(100)

    fun setStorageQuotaMb(context: Context, mb: Int) {
        prefs(context).edit { putInt(KEY_STORAGE_QUOTA_MB, mb.coerceAtLeast(100)) }
    }
}

// ------------------------
// Upload prefs
// ------------------------

internal object UploadPrefs {
    private const val PREFS = "model_prefs"
    private const val KEY_UPLOAD_URL    = "upload_service_url"
    private const val KEY_MOBILE_DATA   = "upload_on_mobile_data"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getUploadUrl(context: Context): String =
        prefs(context).getString(KEY_UPLOAD_URL, "") ?: ""

    fun setUploadUrl(context: Context, url: String) {
        prefs(context).edit { putString(KEY_UPLOAD_URL, url) }
    }

    fun getUploadOnMobileData(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MOBILE_DATA, false)

    fun setUploadOnMobileData(context: Context, v: Boolean) {
        prefs(context).edit { putBoolean(KEY_MOBILE_DATA, v) }
    }

    private const val KEY_AUTO_UPLOAD_TIME       = "auto_upload_time"
    private const val KEY_AUTO_UPLOAD_LAST_DATE  = "auto_upload_last_date"
    const val DEFAULT_AUTO_UPLOAD_TIME = "02:00"

    fun getAutoUploadTime(context: Context): String =
        prefs(context).getString(KEY_AUTO_UPLOAD_TIME, DEFAULT_AUTO_UPLOAD_TIME)
            ?: DEFAULT_AUTO_UPLOAD_TIME

    fun setAutoUploadTime(context: Context, time: String) {
        prefs(context).edit { putString(KEY_AUTO_UPLOAD_TIME, time) }
    }

    fun getAutoUploadLastDate(context: Context): String? =
        prefs(context).getString(KEY_AUTO_UPLOAD_LAST_DATE, null)

    fun setAutoUploadLastDate(context: Context, date: String) {
        prefs(context).edit { putString(KEY_AUTO_UPLOAD_LAST_DATE, date) }
    }

    private const val KEY_USER_POOL_ID     = "cognito_user_pool_id"
    private const val KEY_APP_CLIENT_ID    = "cognito_app_client_id"
    private const val KEY_IDENTITY_POOL_ID = "cognito_identity_pool_id"
    private const val KEY_COGNITO_USER_ID  = "cognito_user_id"
    private const val KEY_COGNITO_USER_EMAIL = "cognito_user_email"

    fun getUserPoolId(context: Context): String =
        prefs(context).getString(KEY_USER_POOL_ID, "") ?: ""
    fun setUserPoolId(context: Context, v: String) =
        prefs(context).edit { putString(KEY_USER_POOL_ID, v) }

    fun getAppClientId(context: Context): String =
        prefs(context).getString(KEY_APP_CLIENT_ID, "") ?: ""
    fun setAppClientId(context: Context, v: String) =
        prefs(context).edit { putString(KEY_APP_CLIENT_ID, v) }

    fun getIdentityPoolId(context: Context): String =
        prefs(context).getString(KEY_IDENTITY_POOL_ID, "") ?: ""
    fun setIdentityPoolId(context: Context, v: String) =
        prefs(context).edit { putString(KEY_IDENTITY_POOL_ID, v) }

    fun getCognitoUserId(context: Context): String =
        prefs(context).getString(KEY_COGNITO_USER_ID, "") ?: ""
    fun setCognitoUserId(context: Context, v: String) =
        prefs(context).edit { putString(KEY_COGNITO_USER_ID, v) }
    fun clearCognitoUserId(context: Context) =
        prefs(context).edit { remove(KEY_COGNITO_USER_ID) }

    fun getCognitoUserEmail(context: Context): String =
        prefs(context).getString(KEY_COGNITO_USER_EMAIL, "") ?: ""
    fun setCognitoUserEmail(context: Context, v: String) =
        prefs(context).edit { putString(KEY_COGNITO_USER_EMAIL, v) }
    fun clearCognitoUserEmail(context: Context) =
        prefs(context).edit { remove(KEY_COGNITO_USER_EMAIL) }
}

private fun availableModels(context: Context): List<ModelSpec> {
    val downloaded = listDownloadedModels(context)
    val defaults   = listAssetModels(context)
    return downloaded + defaults
}

private fun listDownloadedModels(context: Context): List<ModelSpec> {
    val version = DownloadedModelPrefs.getVersion(context)
    if (version.isEmpty()) return emptyList()
    val dir = File(context.filesDir, "models/downloaded")
    return dir.listFiles { f -> f.extension.equals("tflite", ignoreCase = true) }
        ?.sortedBy { it.name.lowercase() }
        ?.map { file ->
            val base = file.nameWithoutExtension
            val id = "downloaded:$base"
            ModelSpec(
                id = id,
                title = base,
                source = ModelSource.FilePath(file),
                coordFormat = CoordFormat.XYXY_SCORE_CLASS,
                conf = ModelPrefs.getConf(context, id),
                description = readDescription(File(dir, "$base.txt")),
                origin = ModelOrigin.DOWNLOADED,
                version = version
            )
        }
        .orEmpty()
}

private fun listAssetModels(context: Context): List<ModelSpec> {
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
        .distinctBy { it.second }

    return all.map { (fileName, assetPath) ->
        val base = fileName.substringBeforeLast(".")
        val descPath = assetPath.substringBeforeLast(".") + ".txt"
        val id = "asset:$base"
        ModelSpec(
            id = id,
            title = base,
            source = ModelSource.Asset(assetPath),
            coordFormat = CoordFormat.XYXY_SCORE_CLASS,
            conf = ModelPrefs.getConf(context, id),
            description = readAssetDescription(context, descPath),
            origin = ModelOrigin.DEFAULT
        )
    }
}


private fun readDescription(file: File): String? {
    if (!file.exists()) return null
    return runCatching { file.readText().trim() }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
}

private fun readAssetDescription(context: Context, path: String): String? {
    return runCatching {
        context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

private fun deleteModel(context: Context, model: ModelSpec): Boolean {
    return when (val source = model.source) {
        is ModelSource.FilePath -> {
            val file = source.file
            val description = File(file.parentFile, "${file.nameWithoutExtension}.txt")
            val deleted = runCatching { file.delete() }.getOrDefault(false)
            runCatching { if (description.exists()) description.delete() }
            if (model.origin == ModelOrigin.DOWNLOADED) DownloadedModelPrefs.clearActive(context)
            deleted
        }
        is ModelSource.ContentUri -> false
        is ModelSource.Asset -> false
    }
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


// ------------------------
// Model update types + logic
// ------------------------

data class PendingModelUpdate(
    val version: String,
    val downloadUrl: String,
    val description: String,
    val s3Key: String
)

private suspend fun applyModelUpdate(
    context: Context,
    update: PendingModelUpdate,
    onComplete: (modelId: String) -> Unit
) = withContext(Dispatchers.IO) {
    val filename = update.s3Key.substringAfterLast("/")
    val dir = File(context.filesDir, "models/downloaded").apply { mkdirs() }
    val dest = File(dir, filename)
    val tmp  = File(dir, "$filename.download")

    ModelUpdateLog.log("Downloading model v${update.version}…")
    try {
        val conn = java.net.URL(update.downloadUrl).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout    = 120_000
            if (conn.responseCode !in 200..299) {
                throw Exception("HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o) } }
        } finally {
            conn.disconnect()
        }

        // Remove old downloaded models
        dir.listFiles()?.forEach { f ->
            if (f != tmp) runCatching { f.delete() }
        }
        if (!tmp.renameTo(dest)) throw Exception("rename failed")

        val base = filename.substringBeforeLast(".")
        if (update.description.isNotEmpty()) {
            runCatching { File(dir, "$base.txt").writeText(update.description) }
        }

        DownloadedModelPrefs.setActive(context, update.version, update.s3Key)
        DownloadedModelPrefs.clearPending(context)
        ModelUpdateLog.log("Model v${update.version} installed", ModelUpdateLog.Level.SUCCESS)

        withContext(Dispatchers.Main) { onComplete("downloaded:$base") }
    } catch (e: Exception) {
        runCatching { tmp.delete() }
        ModelUpdateLog.log("Model download failed: ${e.message}", ModelUpdateLog.Level.ERROR)
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, "Model download failed", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

// ------------------------
// Public entry composable
// ------------------------

@Composable
fun LivePlateDetectionScreen(openContribute: Boolean = false) {
    val context = LocalContext.current

    // allow "retry" to refresh downloaded/custom models
    var reloadKey by rememberSaveable { mutableIntStateOf(0) }

    val models = remember(reloadKey) { availableModels(context) }

    var selectedId by rememberSaveable {
        mutableStateOf(ModelPrefs.getSelectedId(context))
    }
    var showClassNames by rememberSaveable {
        mutableStateOf(ModelPrefs.getShowLabels(context))
    }
    var analysisResolution by rememberSaveable {
        mutableStateOf(ModelPrefs.getAnalysisResolution(context))
    }
    var scanIntervalMs by rememberSaveable {
        mutableIntStateOf(ModelPrefs.getScanIntervalMs(context))
    }
    var collectTrainingData by rememberSaveable {
        mutableStateOf(ModelPrefs.getCollectTrainingData(context))
    }
    var collectFirstTimeShown by rememberSaveable {
        mutableStateOf(ModelPrefs.getCollectFirstTimeShown(context))
    }
    var storageQuotaMb by rememberSaveable {
        mutableIntStateOf(ModelPrefs.getStorageQuotaMb(context))
    }
    var uploadServiceUrl by rememberSaveable {
        mutableStateOf(UploadPrefs.getUploadUrl(context))
    }
    var uploadOnMobileData by rememberSaveable {
        mutableStateOf(UploadPrefs.getUploadOnMobileData(context))
    }
    var autoUploadTime by rememberSaveable {
        mutableStateOf(UploadPrefs.getAutoUploadTime(context))
    }
    val autoUploadLastDate = remember {
        UploadPrefs.getAutoUploadLastDate(context)
    }
    val authManager = remember { CognitoAuthManager(context) }
    var isSignedIn by rememberSaveable { mutableStateOf(authManager.isSignedIn()) }
    var signedInEmail by rememberSaveable { mutableStateOf(authManager.currentUserEmail()) }
    var pendingUpdate by remember { mutableStateOf<PendingModelUpdate?>(null) }
    val latestModelVersion    = remember(reloadKey) { DownloadedModelPrefs.getLatestVersion(context) }
    val compatibleModelVersion = remember(reloadKey) {
        DownloadedModelPrefs.getPendingVersion(context).takeIf { it.isNotEmpty() }
            ?: DownloadedModelPrefs.getVersion(context)
    }
    val lastModelCheckTime = remember(reloadKey) { DownloadedModelPrefs.getLastCheckTime(context) }
    var showAuth by rememberSaveable { mutableStateOf(false) }
    val deviceId = remember {
        DatasetExporter.computeDeviceId(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        )
    }

    // If first launch and nothing selected, open settings.
    var showSettings by rememberSaveable { mutableStateOf(selectedId == null) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var isModelEnabled by rememberSaveable { mutableStateOf(selectedId != null) }
    var stopDetectionRequested by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        AppConfig.seedPrefsIfNeeded(context)
        if (uploadServiceUrl.isBlank()) uploadServiceUrl = UploadPrefs.getUploadUrl(context)
        if (openContribute) showExport = true
        AutoUploadWorker.schedule(context)
        withContext(Dispatchers.IO) {
            AutoUploadWorker.runCatchUpIfNeeded(context)
        }
    }

    LaunchedEffect(models) {
        if (models.isEmpty()) {
            if (selectedId != null) {
                selectedId = null
                ModelPrefs.clearSelected(context)
            }
            return@LaunchedEffect
        }
        val current = selectedId
        val valid = models.firstOrNull { it.id == current }?.id ?: models.first().id
        if (valid != current) {
            selectedId = valid
            ModelPrefs.setSelectedId(context, valid)
        }
    }

    LaunchedEffect(isSignedIn, reloadKey) {
        if (!isSignedIn) return@LaunchedEffect
        val version = DownloadedModelPrefs.getPendingVersion(context)
        if (version.isNotEmpty()) {
            pendingUpdate = PendingModelUpdate(
                version     = version,
                downloadUrl = DownloadedModelPrefs.getPendingDownloadUrl(context),
                description = DownloadedModelPrefs.getPendingDescription(context),
                s3Key       = DownloadedModelPrefs.getPendingS3Key(context)
            )
        }
    }

    val scope = rememberCoroutineScope()

    pendingUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = {
                pendingUpdate = null
                DownloadedModelPrefs.clearPending(context)
            },
            title = { Text("Detection model update") },
            text  = { Text("Model v${update.version} is available. Download and apply now?") },
            confirmButton = {
                TextButton(onClick = {
                    val captured = pendingUpdate
                    pendingUpdate = null
                    if (captured != null) {
                        scope.launch {
                            applyModelUpdate(context, captured) { modelId ->
                                ModelPrefs.setSelectedId(context, modelId)
                                selectedId = modelId
                                isModelEnabled = true
                                reloadKey++
                            }
                        }
                    }
                }) { Text("Update") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingUpdate = null
                    DownloadedModelPrefs.clearPending(context)
                }) { Text("Later") }
            }
        )
    }

    // If no models: show error and DO NOT init any detector.
    if (models.isEmpty()) {
        NoModelsScreen(onRetry = { reloadKey++ })
        return
    }

    val selected = models.firstOrNull { it.id == selectedId }

    if (showSettings || selected == null || !isModelEnabled || showAuth) {
        when {
            showAuth -> AuthScreen(
                authManager = authManager,
                onSignedIn = { userId ->
                    isSignedIn = true
                    signedInEmail = authManager.currentUserEmail()
                    showAuth = false
                    ModelUpdateLog.log("Signed in — checking for model updates")
                    ModelCheckWorker.schedule(context)
                    ModelCheckWorker.runOnce(context)
                },
                onCancel = { showAuth = false }
            )
            showEditor -> DatasetEditorScreen(onBack = { showEditor = false })
            showExport -> ContributeScreen(
                onBack = { showExport = false },
                onEditDataset = { showEditor = true },
                storageQuotaMb = storageQuotaMb,
                onStorageQuotaMbChange = { mb ->
                    ModelPrefs.setStorageQuotaMb(context, mb)
                    storageQuotaMb = mb
                },
                uploadServiceUrl = uploadServiceUrl,
                onUploadServiceUrlChange = { url ->
                    UploadPrefs.setUploadUrl(context, url)
                    uploadServiceUrl = url
                    AutoUploadWorker.schedule(context)
                },
                uploadOnMobileData = uploadOnMobileData,
                onUploadOnMobileDataChange = { v ->
                    UploadPrefs.setUploadOnMobileData(context, v)
                    uploadOnMobileData = v
                    AutoUploadWorker.schedule(context)
                },
                autoUploadTime = autoUploadTime,
                onAutoUploadTimeChange = { t ->
                    UploadPrefs.setAutoUploadTime(context, t)
                    autoUploadTime = t
                    AutoUploadWorker.schedule(context)
                },
                autoUploadLastDate = autoUploadLastDate,
                deviceId = deviceId,
                isSignedIn = isSignedIn,
                signedInEmail = signedInEmail,
                onSignIn = { showAuth = true },
                onSignOut = {
                    authManager.signOut()
                    isSignedIn = false
                    signedInEmail = ""
                    AutoUploadWorker.cancel(context)
                    ModelCheckWorker.cancel(context)
                    ModelUpdateLog.log("Signed out — downloaded model removed")
                    reloadKey++
                }
            )
            else -> SettingsScreen(
                models = models,
                selectedModelId = selectedId ?: models.first().id,
                onPick = { spec ->
                    val isNewModel = selectedId != spec.id
                    ModelPrefs.setSelectedId(context, spec.id)
                    selectedId = spec.id
                    if (isNewModel) {
                        ModelPrefs.setShowLabels(context, false)
                        showClassNames = false
                    }
                    isModelEnabled = true
                    showSettings = false
                    showExport = false
                    showEditor = false
                    stopDetectionRequested = false
                },
                onDelete = { spec ->
                    if (!spec.isDeletable) return@SettingsScreen
                    deleteModel(context, spec)
                    ModelPrefs.clearConf(context, spec.id)
                    reloadKey++
                },
                confidenceForModel = { modelId ->
                    ModelPrefs.getConf(context, modelId)
                },
                onConfidenceChange = { modelId, conf ->
                    ModelPrefs.setConf(context, modelId, conf)
                },
                analysisResolution = analysisResolution,
                onAnalysisResolutionChange = { res ->
                    ModelPrefs.setAnalysisResolution(context, res)
                    analysisResolution = res
                },
                scanIntervalMs = scanIntervalMs,
                onScanIntervalMsChange = { ms ->
                    ModelPrefs.setScanIntervalMs(context, ms)
                    scanIntervalMs = ms
                },
                collectTrainingData = collectTrainingData,
                onCollectTrainingDataChange = { enable ->
                    ModelPrefs.setCollectTrainingData(context, enable)
                    collectTrainingData = enable
                    if (!enable) AutoUploadWorker.cancel(context)
                },
                collectFirstTimeShown = collectFirstTimeShown,
                onCollectFirstTimeShownAck = {
                    ModelPrefs.setCollectFirstTimeShown(context, true)
                    collectFirstTimeShown = true
                },
                onNavigateToContribute = { showExport = true },
                latestModelVersion = latestModelVersion,
                compatibleModelVersion = compatibleModelVersion,
                lastModelCheckTime = lastModelCheckTime,
                onCheckNow = if (isSignedIn) ({
                    ModelCheckWorker.performCheck(context)
                    reloadKey++
                }) else null
            )
        }
    } else {
        // Important: spec.conf may change in prefs in picker, so load fresh conf for runtime.
        val runtimeSpec = selected.copy(conf = ModelPrefs.getConf(context, selected.id))

        LiveDetectionUi(
            spec = runtimeSpec,
            stopDetectionRequested = stopDetectionRequested,
            showClassNames = showClassNames,
            onShowClassNamesChange = { show ->
                ModelPrefs.setShowLabels(context, show)
                showClassNames = show
            },
            collectTrainingData = collectTrainingData,
            storageQuotaMb = storageQuotaMb,
            analysisResolution = analysisResolution,
            scanIntervalMs = scanIntervalMs,
            onRequestOpenSettings = { stopDetectionRequested = true },
            onOpenEditor = {
                isModelEnabled = false
                showSettings = true
                showExport = true
                showEditor = true
                stopDetectionRequested = false
            },
            onDetectionStopped = {
                isModelEnabled = false
                showSettings = true
                showExport = false
                showEditor = false
                stopDetectionRequested = false
            }
        )
    }
}

// ------------------------
// UI screens
// ------------------------

@Composable
private fun NoModelsScreen(onRetry: () -> Unit) {
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
            Text("No detection model found", style = MaterialTheme.typography.titleLarge)
            Text(
                "No model was bundled with this build.\n\n" +
                        "Sign in to download a model automatically, " +
                        "or rebuild the app with a valid MODEL_DOWNLOAD_TOKEN.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(8.dp))

            OutlinedButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}


@Composable
private fun LiveDetectionUi(
    spec: ModelSpec,
    stopDetectionRequested: Boolean,
    showClassNames: Boolean,
    onShowClassNamesChange: (Boolean) -> Unit,
    collectTrainingData: Boolean,
    storageQuotaMb: Int,
    analysisResolution: AnalysisResolution,
    scanIntervalMs: Int,
    onRequestOpenSettings: () -> Unit,
    onOpenEditor: () -> Unit,
    onDetectionStopped: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val trainingSaver = remember { TrainingDataSaver(context) }
    val datasetEditor = remember { DatasetEditor(context) }
    @Suppress("DEPRECATION")
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }.getOrDefault("")
    }

    val scope = rememberCoroutineScope()
    var latestFrame by remember { mutableStateOf<Bitmap?>(null) }
    var captureOnCooldown by remember { mutableStateOf(false) }

    var storageUsageBytes by remember { mutableLongStateOf(0L) }
    val quotaBytes = storageQuotaMb.toLong() * 1024L * 1024L
    val usagePct = if (quotaBytes > 0) (storageUsageBytes * 100L / quotaBytes).toInt().coerceIn(0, 100) else 0
    val quotaReached = storageUsageBytes >= quotaBytes

    LaunchedEffect(collectTrainingData, storageQuotaMb) {
        while (true) {
            storageUsageBytes = withContext(Dispatchers.IO) { datasetEditor.trainingUsageBytes() }
            if (!collectTrainingData) break
            delay(5_000)
        }
    }

    var isInForeground by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> isInForeground = true
                Lifecycle.Event.ON_STOP -> isInForeground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val detectionEnabled = !stopDetectionRequested && isInForeground
    DisposableEffect(detectionEnabled) {
        view.keepScreenOn = detectionEnabled
        onDispose { view.keepScreenOn = false }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — notifications will silently no-op if denied */ }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    // Create OCR instance
    val plateOCR = remember {
        PlateOCR(debugLogs = true)
    }
    DisposableEffect(Unit) {
        onDispose { plateOCR.close() }
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var meteringPointFactory by remember { mutableStateOf<MeteringPointFactory?>(null) }
    var focusTapOffset by remember { mutableStateOf(Offset.Zero) }
    var focusTapKey by remember { mutableIntStateOf(0) }
    val focusRingAlpha = remember { Animatable(0f) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var torchEnabled by remember { mutableStateOf(false) }
    var exposureIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(spec.id) {
        zoomRatio = 1f
        exposureIndex = 0
    }

    LaunchedEffect(camera, torchEnabled) {
        camera?.cameraControl?.enableTorch(torchEnabled)
    }

    LaunchedEffect(camera, exposureIndex) {
        camera?.cameraControl?.setExposureCompensationIndex(exposureIndex)
    }

    LaunchedEffect(detectionEnabled) {
        if (!detectionEnabled) torchEnabled = false
    }

    LaunchedEffect(focusTapKey) {
        if (focusTapKey == 0) return@LaunchedEffect
        focusRingAlpha.snapTo(1f)
        delay(800.milliseconds)
        focusRingAlpha.animateTo(0f, animationSpec = tween(400))
    }

    var lastDetections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var lastFrameW by remember { mutableIntStateOf(0) }
    var lastFrameH by remember { mutableIntStateOf(0) }
    var lastMs by remember { mutableLongStateOf(0L) }
    var hadDetections by remember { mutableStateOf(false) }
    var lastBeepAt by remember { mutableLongStateOf(0L) }
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(stopDetectionRequested, isProcessing) {
        if (stopDetectionRequested && !isProcessing) {
            onDetectionStopped()
        }
    }

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
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                // Key by model id + resolution to rebind when either changes
                key(spec.id, analysisResolution) {
                    CameraPreviewWithAnalysis(
                        detector = detector,
                        plateOCR = plateOCR,
                        collectTrainingData = collectTrainingData && !quotaReached,
                        trainingSaver = trainingSaver,
                        appVersion = appVersion,
                        modelId = spec.id,
                        scoreThreshold = spec.conf,
                        analysisResolution = analysisResolution,
                        scanIntervalMs = scanIntervalMs,
                        isDetectionEnabled = detectionEnabled,
                        onProcessingChanged = { isProcessing = it },
                        onCameraReady = { cam, factory ->
                            camera = cam
                            meteringPointFactory = factory
                        },
                        onResult = { dets, w, h, ms ->
                            val now = SystemClock.elapsedRealtime()
                            val shouldBeep = dets.isNotEmpty() &&
                                (!hadDetections || now - lastBeepAt >= 1_000L)
                            if (shouldBeep) {
                                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                                lastBeepAt = now
                            }
                            if (!showClassNames && dets.any { it.classId > 0 }) {
                                onShowClassNamesChange(true)
                            }
                            hadDetections = dets.isNotEmpty()
                            lastDetections = dets
                            lastFrameW = w
                            lastFrameH = h
                            lastMs = ms
                        },
                        onLatestFrame = if (collectTrainingData) { bmp ->
                            val old = latestFrame
                            latestFrame = bmp
                            old?.recycle()
                        } else null
                    )
                }

                // Overlay + gestures
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(camera) {
                            detectTransformGestures { _, _, zoomChange, _ ->
                                val cam = camera ?: return@detectTransformGestures
                                val zoomState = cam.cameraInfo.zoomState.value
                                    ?: return@detectTransformGestures
                                val newRatio = (zoomState.zoomRatio * zoomChange)
                                    .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                                cam.cameraControl.setZoomRatio(newRatio)
                                zoomRatio = newRatio
                            }
                        }
                        .pointerInput(camera) {
                            detectTapGestures { offset ->
                                val cam = camera ?: return@detectTapGestures
                                val factory = meteringPointFactory ?: return@detectTapGestures
                                val point = factory.createPoint(offset.x, offset.y)
                                val action = FocusMeteringAction.Builder(point)
                                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                    .build()
                                cam.cameraControl.startFocusAndMetering(action)
                                focusTapOffset = offset
                                focusTapKey++
                            }
                        }
                ) {
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

                        val confidenceLabel = "${(det.score * 100).toInt()}%"
                        val classLabel = if (showClassNames) {
                            "${classNameFor(det.classId)} $confidenceLabel"
                        } else {
                            confidenceLabel
                        }

                        // Build label with OCR text if available
                        val label = if (det.ocrText != null && det.ocrText.isNotEmpty()) {
                            "$classLabel | ${det.ocrText}"
                        } else {
                            classLabel
                        }

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

                    // Focus ring — shown after tap-to-focus, fades out
                    val ringAlpha = focusRingAlpha.value
                    if (ringAlpha > 0f) {
                        val ringRadius = 44.dp.toPx()
                        val ringStroke = Stroke(width = 2.dp.toPx())
                        val ringColor = Color.White.copy(alpha = ringAlpha)
                        drawCircle(
                            color = ringColor,
                            radius = ringRadius,
                            center = focusTapOffset,
                            style = ringStroke
                        )
                        // Corner brackets (Samsung-style)
                        val bracket = 12.dp.toPx()
                        val bStroke = Stroke(width = 2.5f.dp.toPx())
                        val cx = focusTapOffset.x
                        val cy = focusTapOffset.y
                        val r = ringRadius
                        listOf(
                            Offset(cx - r, cy - r) to Pair(Offset(cx - r + bracket, cy - r), Offset(cx - r, cy - r + bracket)),
                            Offset(cx + r, cy - r) to Pair(Offset(cx + r - bracket, cy - r), Offset(cx + r, cy - r + bracket)),
                            Offset(cx - r, cy + r) to Pair(Offset(cx - r + bracket, cy + r), Offset(cx - r, cy + r - bracket)),
                            Offset(cx + r, cy + r) to Pair(Offset(cx + r - bracket, cy + r), Offset(cx + r, cy + r - bracket))
                        ).forEach { (corner, lines) ->
                            drawLine(ringColor, corner, lines.first, strokeWidth = bStroke.width)
                            drawLine(ringColor, corner, lines.second, strokeWidth = bStroke.width)
                        }
                    }
                }
            }

            // Bottom controls: EV slider + zoom shortcut buttons
            val cam = camera
            val maxZoom = cam?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
            val visibleZoomLevels = listOf(1f, 2f, 3f).filter { it <= maxZoom + 0.1f }
            val exposureState = cam?.cameraInfo?.exposureState
            if (cam != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(WindowInsets.navigationBars.asPaddingValues())
                        .padding(bottom = 24.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // EV compensation slider
                    if (exposureState?.isExposureCompensationSupported == true) {
                        val evRange = exposureState.exposureCompensationRange
                        val evStep = exposureState.exposureCompensationStep.toFloat()
                        val evValue = exposureIndex * evStep
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "EV ${if (evValue >= 0f) "+" else ""}${"%.1f".format(evValue)}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(56.dp)
                            )
                            Slider(
                                value = exposureIndex.toFloat(),
                                onValueChange = { exposureIndex = it.roundToInt() },
                                valueRange = evRange.lower.toFloat()..evRange.upper.toFloat(),
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }

                    // Zoom shortcut buttons
                    if (visibleZoomLevels.size > 1) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            visibleZoomLevels.forEach { level ->
                                val isSelected = abs(zoomRatio - level) < 0.15f
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) Color.White else Color.Black.copy(alpha = 0.5f)
                                        )
                                        .clickable {
                                            val target = level.coerceIn(
                                                cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f,
                                                maxZoom
                                            )
                                            cam.cameraControl.setZoomRatio(target)
                                            zoomRatio = target
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${level.toInt()}×",
                                        color = if (isSelected) Color.Black else Color.White,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(WindowInsets.safeDrawing.asPaddingValues())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onRequestOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open settings",
                            tint = Color.White
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (lastFrameW > 0 && lastFrameH > 0)
                                "${"%.1f".format(zoomRatio)}× | ${lastDetections.size} det | $lastMs ms"
                            else
                                "Detected: —",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        val modelLabel = buildString {
                            append(spec.title)
                            if (spec.version != null) append(" v${spec.version}")
                            append(" · ${sourceLabel(spec)}")
                        }
                        Text(
                            text = modelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (collectTrainingData) {
                            val captureEnabled = !captureOnCooldown && latestFrame != null
                            IconButton(
                                onClick = {
                                    if (captureOnCooldown) return@IconButton
                                    val frame = latestFrame ?: return@IconButton
                                    if (quotaReached) {
                                        Toast.makeText(context, "Storage quota full", Toast.LENGTH_SHORT).show()
                                        return@IconButton
                                    }
                                    captureOnCooldown = true
                                    scope.launch(Dispatchers.IO) {
                                        trainingSaver.saveFrameManual(frame, appVersion, spec.id)
                                    }
                                    Toast.makeText(context, "Frame saved", Toast.LENGTH_SHORT).show()
                                    scope.launch {
                                        delay(1_000)
                                        captureOnCooldown = false
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Capture frame manually",
                                    tint = if (captureEnabled) Color.White else Color.White.copy(alpha = 0.35f)
                                )
                            }
                        }

                        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                        if (hasFlash) {
                            IconButton(onClick = { torchEnabled = !torchEnabled }) {
                                Icon(
                                    imageVector = if (torchEnabled) Icons.Default.FlashlightOff else Icons.Default.FlashlightOn,
                                    contentDescription = if (torchEnabled) "Turn off torch" else "Turn on torch",
                                    tint = if (torchEnabled) Color.Yellow else Color.White
                                )
                            }
                        }
                    }
                }

                // Storage banners (below top bar, only while collecting)
                if (collectTrainingData && quotaReached) {
                    StorageBanner(
                        text = "Storage limit reached ($storageQuotaMb MB). Export or edit your dataset to continue collecting.",
                        isError = true,
                        actionLabel = "Edit",
                        onAction = onOpenEditor
                    )
                } else if (collectTrainingData && usagePct >= 80) {
                    StorageBanner(
                        text = "Training storage at $usagePct% — consider exporting or editing your dataset.",
                        isError = false,
                        actionLabel = null,
                        onAction = null
                    )
                }
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
    plateOCR: PlateOCR,
    collectTrainingData: Boolean,
    trainingSaver: TrainingDataSaver,
    appVersion: String,
    modelId: String,
    scoreThreshold: Float,
    analysisResolution: AnalysisResolution,
    scanIntervalMs: Int,
    isDetectionEnabled: Boolean,
    onProcessingChanged: (Boolean) -> Unit,
    onCameraReady: (Camera, MeteringPointFactory) -> Unit,
    onResult: (List<Detection>, Int, Int, Long) -> Unit,
    onLatestFrame: ((Bitmap) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val detectorState by rememberUpdatedState(detector)
    val plateOCRState by rememberUpdatedState(plateOCR)
    val collectTrainingDataState by rememberUpdatedState(collectTrainingData)
    val appVersionState by rememberUpdatedState(appVersion)
    val modelIdState by rememberUpdatedState(modelId)
    val thresholdState by rememberUpdatedState(scoreThreshold)
    val detectionEnabledState by rememberUpdatedState(isDetectionEnabled)
    val onResultState by rememberUpdatedState(onResult)
    val onProcessingChangedState by rememberUpdatedState(onProcessingChanged)
    val scanIntervalMsState by rememberUpdatedState(scanIntervalMs)

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

            val imageAnalysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            if (analysisResolution != AnalysisResolution.DEFAULT) {
                val targetSize = when (analysisResolution) {
                    AnalysisResolution.LOW -> android.util.Size(640, 480)
                    AnalysisResolution.HD -> android.util.Size(1280, 720)
                    AnalysisResolution.DEFAULT -> error("unreachable")
                }
                imageAnalysisBuilder.setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                targetSize,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
            }

            val imageAnalysis = imageAnalysisBuilder.build()

            var lastRun = 0L
            var busy = false

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                val now = SystemClock.elapsedRealtime()
                val throttleMs = scanIntervalMsState.toLong()
                var bmp: Bitmap? = null
                var rotated: Bitmap? = null

                try {
                    if (!detectionEnabledState) return@setAnalyzer

                    val shouldProcess = !busy && (now - lastRun >= throttleMs)
                    if (!shouldProcess) return@setAnalyzer

                    busy = true
                    lastRun = now
                    mainExecutor.execute { onProcessingChangedState(true) }

                    val t0 = System.nanoTime()

                    bmp = imageProxy.toBitmapSafe()
                    rotated = bmp.rotate(imageProxy.imageInfo.rotationDegrees)

                    var dets = detectorState.detectAll(
                        rotated,
                        scoreThreshold = thresholdState
                    )

                    // Perform OCR on detected plates
                    if (dets.isNotEmpty()) {
                        dets = dets.map { detection ->
                            try {
                                val croppedPlate = plateOCRState.cropToBounds(
                                    rotated,
                                    detection.leftPx,
                                    detection.topPx,
                                    detection.rightPx,
                                    detection.bottomPx
                                )
                                val ocrResult = plateOCRState.recognizePlate(croppedPlate)
                                if (croppedPlate !== rotated) {
                                    runCatching { croppedPlate.recycle() }
                                }
                                if (ocrResult != null) {
                                    detection.copy(
                                        ocrText = ocrResult.text,
                                        ocrConfidence = ocrResult.confidence
                                    )
                                } else {
                                    detection
                                }
                            } catch (e: Exception) {
                                Log.e("CameraAnalysis", "OCR failed for detection", e)
                                detection
                            }
                        }
                    }

                    if (collectTrainingDataState && dets.isNotEmpty()) {
                        trainingSaver.saveFrame(rotated, dets, appVersionState, modelIdState)
                    }

                    val ms = (System.nanoTime() - t0) / 1_000_000

                    val onLatestFrameState = onLatestFrame
                    val frameCopy = if (onLatestFrameState != null)
                        rotated?.copy(rotated.config ?: Bitmap.Config.ARGB_8888, false)
                    else null

                    mainExecutor.execute {
                        if (frameCopy != null) onLatestFrameState?.invoke(frameCopy)
                        onResultState(dets, rotated.width, rotated.height, ms)
                    }
                } catch (t: Throwable) {
                    Log.e("CameraAnalysis", "Analyzer failed", t)
                } finally {
                    busy = false
                    mainExecutor.execute { onProcessingChangedState(false) }
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
            val boundCamera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            mainExecutor.execute {
                onCameraReady(boundCamera, previewView.meteringPointFactory)
            }
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

fun sourceLabel(model: ModelSpec): String {
    return when (model.origin) {
        ModelOrigin.DEFAULT -> "Built into app"
        ModelOrigin.DOWNLOADED -> "Downloaded from server"
        ModelOrigin.CUSTOM -> "custom model"
        ModelOrigin.LEGACY_EXTERNAL -> "external file"
    }
}

@Composable
fun StorageBanner(
    text: String,
    isError: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    val bg = if (isError) Color(0xFFB71C1C) else Color(0xFFF57F17)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
