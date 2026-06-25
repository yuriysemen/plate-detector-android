package com.github.yuriysemen.platesdetector

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.collectAsState

@Composable
fun ContributeScreen(
    onBack: () -> Unit,
    onEditDataset: () -> Unit,
    storageQuotaMb: Int,
    onStorageQuotaMbChange: (Int) -> Unit,
    uploadServiceUrl: String,
    onUploadServiceUrlChange: (String) -> Unit,
    uploadOnMobileData: Boolean,
    onUploadOnMobileDataChange: (Boolean) -> Unit,
    deviceId: String
) {
    val context = LocalContext.current
    val exporter = remember { DatasetExporter(context) }
    val editor = remember { DatasetEditor(context) }
    val scope = rememberCoroutineScope()

    var stats by remember { mutableStateOf(exporter.readStats()) }
    var storageUsageBytes by remember { mutableLongStateOf(0L) }
    val quotaBytes = storageQuotaMb.toLong() * 1024L * 1024L
    val usagePct = if (quotaBytes > 0) (storageUsageBytes * 100L / quotaBytes).toInt().coerceIn(0, 100) else 0

    LaunchedEffect(Unit) {
        storageUsageBytes = withContext(Dispatchers.IO) { editor.trainingUsageBytes() }
    }

    var isUploading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }

    var quotaUnit by rememberSaveable { mutableStateOf(if (storageQuotaMb >= 1024) "GB" else "MB") }
    var quotaText by rememberSaveable(storageQuotaMb) {
        mutableStateOf(
            if (storageQuotaMb >= 1024) (storageQuotaMb / 1024).toString() else storageQuotaMb.toString()
        )
    }

    val uploadConfigured = uploadServiceUrl.isNotBlank()

    fun refresh() {
        stats = exporter.readStats()
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

    fun doUpload() {
        scope.launch {
            isUploading = true
            errorMessage = null
            val config = DatasetExporter.SplitConfig()
            val result = withContext(Dispatchers.IO) { runCatching { exporter.exportSync(config) } }
            isUploading = false
            result.fold(
                onSuccess = { zipFile ->
                    enqueueUpload(zipFile)
                    refresh()
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

    BackHandler { onBack() }

    // Active upload jobs: list all ZIPs that have a non-idle status
    val allExports = remember(stats) { exporter.listExports() }
    val sessionJobs = allExports.filter {
        it.uploadStatus != UploadStatus.NOT_QUEUED && it.uploadStatus != UploadStatus.UPLOADED
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
                Text("Contribute data", style = MaterialTheme.typography.titleLarge)
            }

            // Storage quota banners
            if (usagePct >= 80) {
                item {
                    StorageBanner(
                        text = if (usagePct >= 100)
                            "Storage limit reached ($storageQuotaMb MB). Upload or edit your dataset."
                        else
                            "Training storage at $usagePct% — consider uploading or editing your dataset.",
                        isError = usagePct >= 100,
                        actionLabel = null,
                        onAction = null
                    )
                }
            }

            // Upload config warning
            if (!uploadConfigured) {
                item {
                    StorageBanner(
                        text = "Upload not configured — data will be collected but not sent.",
                        isError = false,
                        actionLabel = null,
                        onAction = null
                    )
                }
            }

            // Stats card
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Frames collected: ${stats.totalFrames}")
                                Text("Total detections: ${stats.totalDetections}")
                            }
                            OutlinedButton(
                                onClick = onEditDataset,
                                enabled = stats.totalFrames > 0
                            ) { Text("View dataset") }
                        }
                        OutlinedButton(
                            onClick = { showResetDialog = true },
                            enabled = stats.totalFrames > 0,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) { Text("Reset collected data") }
                    }
                }
            }

            // Storage limit
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Storage limit", style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = quotaText,
                                onValueChange = { v ->
                                    quotaText = v.filter { it.isDigit() }
                                    val n = quotaText.toIntOrNull() ?: return@OutlinedTextField
                                    val mb = if (quotaUnit == "GB") n * 1024 else n
                                    if (mb >= 100) onStorageQuotaMbChange(mb)
                                },
                                label = { Text("Limit") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            listOf("MB", "GB").forEach { unit ->
                                val sel = unit == quotaUnit
                                TextButton(
                                    onClick = {
                                        if (unit != quotaUnit) {
                                            quotaUnit = unit
                                            quotaText = if (unit == "GB") (storageQuotaMb / 1024).coerceAtLeast(1).toString()
                                                       else storageQuotaMb.toString()
                                            val newMb = if (unit == "GB") quotaText.toInt() * 1024 else quotaText.toIntOrNull() ?: 0
                                            if (newMb >= 100) onStorageQuotaMbChange(newMb)
                                        }
                                    },
                                    colors = ButtonDefaults.textButtonColors(
                                        containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer
                                                        else androidx.compose.ui.graphics.Color.Transparent
                                    )
                                ) { Text(unit) }
                            }
                        }
                    }
                }
            }

            // Upload configuration
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Upload configuration", style = MaterialTheme.typography.titleSmall)

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
                            Switch(
                                checked = uploadOnMobileData,
                                onCheckedChange = onUploadOnMobileDataChange
                            )
                        }
                    }
                }
            }

            // Actions
            item {
                Button(
                    onClick = ::doUpload,
                    enabled = !isUploading && stats.totalFrames > 0 && uploadConfigured,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Preparing…")
                    } else {
                        Text(
                            when {
                                stats.totalFrames == 0 -> "No data yet"
                                !uploadConfigured      -> "Configure upload URL first"
                                else                   -> "Upload collected data"
                            }
                        )
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

            // Session in progress — only when there are active/failed jobs
            if (sessionJobs.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Spacer(Modifier.width(4.dp))
                    Text("Session in progress", style = MaterialTheme.typography.titleMedium)
                }

                items(sessionJobs, key = { it.file.absolutePath }) { exportFile ->
                    val workInfos by WorkManager.getInstance(context)
                        .getWorkInfosByTagFlow(exportFile.file.absolutePath)
                        .collectAsState(initial = emptyList())
                    val liveStatus = workInfos.firstOrNull()?.let { info ->
                        when (info.state) {
                            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> UploadStatus.PENDING
                            WorkInfo.State.RUNNING                          -> UploadStatus.UPLOADING
                            WorkInfo.State.FAILED                           -> UploadStatus.FAILED
                            else                                            -> null
                        }
                    } ?: exportFile.uploadStatus

                    UploadJobCard(
                        item = exportFile,
                        uploadStatus = liveStatus,
                        onRetry = { enqueueUpload(exportFile.file); refresh() }
                    )
                }
            }
        }
    }
}

@Composable
private fun UploadJobCard(
    item: DatasetExporter.ExportFile,
    uploadStatus: UploadStatus,
    onRetry: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "${formatBytes(item.sizeBytes)} · ${formatDate(item.createdAt)}",
                style = MaterialTheme.typography.bodySmall
            )
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
                    UploadStatus.FAILED -> {
                        Text(
                            "Upload failed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(
                            onClick = onRetry,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("Retry", style = MaterialTheme.typography.labelSmall) }
                    }
                    else -> {}
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))