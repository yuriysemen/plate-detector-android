package com.github.yuriysemen.platesdetector

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun ExportScreen(
    onBack: () -> Unit,
    onEditDataset: () -> Unit,
    storageQuotaMb: Int,
    exportMode: ExportMode,
    onExportModeChange: (ExportMode) -> Unit,
    uploadServiceUrl: String,
    onUploadServiceUrlChange: (String) -> Unit,
    uploadOnMobileData: Boolean,
    onUploadOnMobileDataChange: (Boolean) -> Unit,
    uploadConsentShown: Boolean,
    onUploadConsentShownAck: () -> Unit,
    deviceId: String
) {
    val context = LocalContext.current
    val exporter = remember { DatasetExporter(context) }
    val editor = remember { DatasetEditor(context) }
    val scope = rememberCoroutineScope()

    var stats by remember { mutableStateOf(exporter.readStats()) }
    var exports by remember { mutableStateOf(exporter.listExports()) }
    var storageUsageBytes by remember { mutableLongStateOf(0L) }
    val quotaBytes = storageQuotaMb.toLong() * 1024L * 1024L
    val usagePct = if (quotaBytes > 0) (storageUsageBytes * 100L / quotaBytes).toInt().coerceIn(0, 100) else 0

    LaunchedEffect(Unit) {
        storageUsageBytes = withContext(Dispatchers.IO) { editor.trainingUsageBytes() }
    }
    var isExporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DatasetExporter.ExportFile?>(null) }
    var renameTarget by remember { mutableStateOf<DatasetExporter.ExportFile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }

    var trainPct by rememberSaveable { mutableIntStateOf(70) }
    var valPct by rememberSaveable { mutableIntStateOf(20) }

    var showUploadConsentDialog by rememberSaveable { mutableStateOf(false) }
    var pendingExportModeName by rememberSaveable { mutableStateOf("") }
    val pendingExportMode = runCatching { ExportMode.valueOf(pendingExportModeName) }.getOrNull()

    BackHandler { onBack() }

    fun refresh() {
        stats = exporter.readStats()
        exports = exporter.listExports()
    }

    fun shareZip(file: DatasetExporter.ExportFile) {
        val uri = exporter.getShareUri(file.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share dataset"))
    }

    fun enqueueUpload(zipFile: java.io.File) {
        val networkType = if (uploadOnMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
        val constraints = Constraints.Builder().setRequiredNetworkType(networkType).build()
        val request = OneTimeWorkRequestBuilder<UploadDatasetWorker>()
            .setInputData(workDataOf(
                UploadDatasetWorker.KEY_ZIP_PATH   to zipFile.absolutePath,
                UploadDatasetWorker.KEY_DEVICE_ID  to deviceId,
                UploadDatasetWorker.KEY_UPLOAD_URL to uploadServiceUrl
            ))
            .addTag(zipFile.absolutePath)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            zipFile.absolutePath, ExistingWorkPolicy.KEEP, request
        )
        exporter.writeUploadStatus(zipFile, UploadStatus.PENDING)
    }

    fun doExport() {
        scope.launch {
            isExporting = true
            errorMessage = null
            val config = DatasetExporter.SplitConfig(trainPct / 100f, valPct / 100f)
            val result = withContext(Dispatchers.IO) { runCatching { exporter.exportSync(config) } }
            isExporting = false
            result.fold(
                onSuccess = { zipFile ->
                    refresh()
                    val isCloud = exportMode == ExportMode.CLOUD || exportMode == ExportMode.BOTH
                    val urlMissing = isCloud && uploadServiceUrl.isBlank()
                    if (isCloud && !urlMissing) {
                        enqueueUpload(zipFile)
                        refresh()
                    }
                    if (exportMode == ExportMode.MANUAL || exportMode == ExportMode.BOTH || urlMissing) {
                        shareZip(DatasetExporter.ExportFile(zipFile, zipFile.nameWithoutExtension, zipFile.length(), zipFile.lastModified()))
                    }
                },
                onFailure = { errorMessage = it.message ?: "Export failed" }
            )
        }
    }

    fun doReset() {
        scope.launch {
            withContext(Dispatchers.IO) { exporter.resetCollectedData() }
            refresh()
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset collected data") },
            text = { Text("Delete all ${stats.totalFrames} collected frames? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showResetDialog = false; doReset() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showUploadConsentDialog) {
        AlertDialog(
            onDismissRequest = { showUploadConsentDialog = false; pendingExportModeName = "" },
            title = { Text("Upload dataset to shared model training pool?") },
            text = {
                Text(
                    "Your collected frames (license plate images) will be uploaded to " +
                    "a private research server. Images are used only to improve the " +
                    "plate detection model.\n\n" +
                    "• Your uploads are stored under a private device identifier.\n" +
                    "• Other contributors cannot access your images.\n" +
                    "• You can request deletion by contacting the app administrator."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUploadConsentDialog = false
                    onUploadConsentShownAck()
                    pendingExportMode?.let { onExportModeChange(it) }
                    pendingExportModeName = ""
                }) { Text("I Agree — Enable Upload") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUploadConsentDialog = false
                    pendingExportModeName = ""
                }) { Text("Cancel") }
            }
        )
    }

    val toDelete = deleteTarget
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete export") },
            text = { Text("Delete ${toDelete.name}.zip?") },
            confirmButton = {
                TextButton(onClick = {
                    exporter.deleteExport(toDelete.file)
                    exports = exporter.listExports()
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text("Export dataset", style = MaterialTheme.typography.titleLarge)
            }

            if (usagePct >= 80) {
                item {
                    StorageBanner(
                        text = if (usagePct >= 100) "Storage limit reached ($storageQuotaMb MB). Export or edit your dataset."
                               else "Training storage at $usagePct% — consider exporting or editing your dataset.",
                        isError = usagePct >= 100,
                        actionLabel = null,
                        onAction = null
                    )
                }
            }

            val isCloudMode = exportMode == ExportMode.CLOUD || exportMode == ExportMode.BOTH
            if (isCloudMode && uploadServiceUrl.isBlank()) {
                item {
                    StorageBanner(
                        text = "Upload server URL not configured. Set it in Settings → Export action.",
                        isError = false,
                        actionLabel = null,
                        onAction = null
                    )
                }
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Frames collected: ${stats.totalFrames}")
                        Text("Total detections: ${stats.totalDetections}")
                        val from = stats.collectedFrom
                        val to = stats.collectedTo
                        if (from != null || to != null) {
                            Text(
                                "Date range: ${from ?: "—"} → ${to ?: "—"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Dataset split", style = MaterialTheme.typography.titleSmall)

                        val n = stats.totalFrames
                        val trainN = (n * trainPct / 100f).toInt()
                        val valN = (n * valPct / 100f).toInt()
                        val testPct = 100 - trainPct - valPct
                        val testN = n - trainN - valN

                        Text("Train: $trainPct%  ($trainN frames)")
                        Slider(
                            value = trainPct.toFloat(),
                            onValueChange = { v ->
                                trainPct = v.toInt()
                                val maxVal = 95 - trainPct
                                if (valPct > maxVal) valPct = maxVal
                            },
                            valueRange = 50f..90f
                        )

                        Text("Validation: $valPct%  ($valN frames)")
                        Slider(
                            value = valPct.toFloat(),
                            onValueChange = { valPct = it.toInt() },
                            valueRange = 5f..(95f - trainPct)
                        )

                        Text(
                            "Test: $testPct%  ($testN frames)",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Export action", style = MaterialTheme.typography.titleSmall)
                        listOf(
                            ExportMode.MANUAL to "Manual share",
                            ExportMode.CLOUD  to "Upload to shared dataset",
                            ExportMode.BOTH   to "Both"
                        ).forEach { (mode, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (mode != ExportMode.MANUAL && !uploadConsentShown) {
                                            pendingExportModeName = mode.name
                                            showUploadConsentDialog = true
                                        } else {
                                            onExportModeChange(mode)
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(
                                    selected = exportMode == mode,
                                    onClick = {
                                        if (mode != ExportMode.MANUAL && !uploadConsentShown) {
                                            pendingExportModeName = mode.name
                                            showUploadConsentDialog = true
                                        } else {
                                            onExportModeChange(mode)
                                        }
                                    }
                                )
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (exportMode != ExportMode.MANUAL) {
                            OutlinedTextField(
                                value = uploadServiceUrl,
                                onValueChange = onUploadServiceUrlChange,
                                label = { Text("Upload server URL") },
                                placeholder = { Text("https://…execute-api.amazonaws.com/prod") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Upload on mobile data", style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = uploadOnMobileData, onCheckedChange = onUploadOnMobileDataChange)
                            }
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = onEditDataset,
                    enabled = stats.totalFrames > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Edit dataset")
                }
            }

            item {
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    enabled = stats.totalFrames > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reset collected data")
                }
            }

            item {
                Button(
                    onClick = ::doExport,
                    enabled = !isExporting && stats.totalFrames > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Exporting…")
                    } else {
                        Text(if (stats.totalFrames == 0) "No data yet" else "Export dataset")
                    }
                }
            }

            val error = errorMessage
            if (error != null) {
                item {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            item {
                HorizontalDivider()
                Spacer(Modifier.width(4.dp))
                Text("Previous exports", style = MaterialTheme.typography.titleMedium)
            }

            if (exports.isEmpty()) {
                item {
                    Text(
                        "No exports yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                items(exports, key = { it.file.absolutePath }) { exportFile ->
                    val isRenaming = renameTarget?.file?.absolutePath == exportFile.file.absolutePath
                    val showUploadBadge = exportMode != ExportMode.MANUAL
                    val workInfos by WorkManager.getInstance(context)
                        .getWorkInfosByTagFlow(exportFile.file.absolutePath)
                        .collectAsState(initial = emptyList())
                    val liveStatus = workInfos.firstOrNull()?.let { info ->
                        when (info.state) {
                            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> UploadStatus.PENDING
                            WorkInfo.State.RUNNING -> UploadStatus.UPLOADING
                            WorkInfo.State.SUCCEEDED -> UploadStatus.UPLOADED
                            WorkInfo.State.FAILED -> UploadStatus.FAILED
                            else -> null
                        }
                    } ?: exportFile.uploadStatus
                    if (isRenaming) {
                        RenameCard(
                            text = renameText,
                            error = renameError,
                            onTextChange = { renameText = it; renameError = null },
                            onConfirm = {
                                val trimmed = renameText.trim()
                                when {
                                    trimmed.isEmpty() -> renameError = "Name cannot be empty"
                                    exporter.renameExport(exportFile.file, trimmed) -> {
                                        renameTarget = null
                                        exports = exporter.listExports()
                                    }
                                    else -> renameError = "Name already in use"
                                }
                            },
                            onCancel = { renameTarget = null; renameError = null }
                        )
                    } else {
                        ExportFileCard(
                            item = exportFile,
                            showUploadBadge = showUploadBadge,
                            uploadStatus = liveStatus,
                            canUpload = uploadServiceUrl.isNotBlank(),
                            onShare = { shareZip(exportFile) },
                            onRename = {
                                renameTarget = exportFile
                                renameText = exportFile.name
                                renameError = null
                            },
                            onDelete = { deleteTarget = exportFile },
                            onUpload = { enqueueUpload(exportFile.file); refresh() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportFileCard(
    item: DatasetExporter.ExportFile,
    showUploadBadge: Boolean,
    uploadStatus: UploadStatus,
    canUpload: Boolean,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onUpload: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "${formatFileSize(item.sizeBytes)} · ${formatFileDate(item.createdAt)}",
                style = MaterialTheme.typography.bodySmall
            )
            if (showUploadBadge && uploadStatus != UploadStatus.NOT_QUEUED) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    when (uploadStatus) {
                        UploadStatus.PENDING -> Text(
                            "Pending upload",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        UploadStatus.UPLOADING -> {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                            Text(
                                "Uploading…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        UploadStatus.UPLOADED -> Text(
                            "Uploaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        UploadStatus.FAILED -> {
                            Text(
                                "Upload failed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(
                                onClick = onUpload,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("Retry", style = MaterialTheme.typography.labelSmall) }
                        }
                        UploadStatus.NOT_QUEUED -> {}
                    }
                }
            }
            Row {
                TextButton(onClick = onShare) { Text("Share") }
                if (canUpload && uploadStatus == UploadStatus.NOT_QUEUED) {
                    TextButton(onClick = onUpload) { Text("Upload") }
                }
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun RenameCard(
    text: String,
    error: String?,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                label = { Text("File name") },
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                modifier = Modifier.fillMaxWidth()
            )
            Row {
                TextButton(onClick = onConfirm) { Text("Save") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

private fun formatFileDate(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
