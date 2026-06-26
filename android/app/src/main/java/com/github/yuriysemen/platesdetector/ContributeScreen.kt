package com.github.yuriysemen.platesdetector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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

@OptIn(ExperimentalMaterial3Api::class)
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
    autoUploadTime: String,
    onAutoUploadTimeChange: (String) -> Unit,
    autoUploadLastDate: String?,
    deviceId: String,
    isSignedIn: Boolean,
    signedInEmail: String,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val exporter = remember { DatasetExporter(context) }
    val editor = remember { DatasetEditor(context) }
    val scope = rememberCoroutineScope()

    var stats by remember { mutableStateOf(exporter.readStats()) }
    var storageUsageBytes by remember { mutableLongStateOf(0L) }
    val quotaBytes = storageQuotaMb.toLong() * 1024L * 1024L
    val usagePct = if (quotaBytes > 0) (storageUsageBytes * 100L / quotaBytes).toInt().coerceIn(0, 100) else 0

    LaunchedEffect(stats) {
        storageUsageBytes = withContext(Dispatchers.IO) { editor.trainingUsageBytes() }
    }

    var isUploading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showQuotaDialog by remember { mutableStateOf(false) }
    var quotaDialogText by remember(storageQuotaMb) { mutableStateOf(storageQuotaMb.toString()) }

    val timeHour   = remember(autoUploadTime) { autoUploadTime.split(":").getOrNull(0)?.toIntOrNull() ?: 2 }
    val timeMinute = remember(autoUploadTime) { autoUploadTime.split(":").getOrNull(1)?.toIntOrNull() ?: 0 }
    val timePickerState = rememberTimePickerState(
        initialHour = timeHour,
        initialMinute = timeMinute,
        is24Hour = true
    )



    val uploadConfigured = uploadServiceUrl.isNotBlank() && isSignedIn

    fun refresh() {
        stats = exporter.readStats()
    }

    fun enqueueUpload(zipFile: java.io.File) {
        val networkType = if (uploadOnMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
        val constraints = Constraints.Builder().setRequiredNetworkType(networkType).build()
        val request = OneTimeWorkRequestBuilder<UploadDatasetWorker>()
            .setInputData(workDataOf(
                UploadDatasetWorker.KEY_ZIP_PATH         to zipFile.absolutePath,
                UploadDatasetWorker.KEY_DEVICE_ID        to deviceId,
                UploadDatasetWorker.KEY_UPLOAD_URL       to uploadServiceUrl,
                UploadDatasetWorker.KEY_USER_ID          to UploadPrefs.getCognitoUserId(context),
                UploadDatasetWorker.KEY_USER_POOL_ID     to UploadPrefs.getUserPoolId(context),
                UploadDatasetWorker.KEY_IDENTITY_POOL_ID to UploadPrefs.getIdentityPoolId(context)
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

    if (showQuotaDialog) {
        AlertDialog(
            onDismissRequest = { showQuotaDialog = false },
            title = { Text("Storage limit") },
            text = {
                OutlinedTextField(
                    value = quotaDialogText,
                    onValueChange = { quotaDialogText = it.filter { c -> c.isDigit() } },
                    label = { Text("Limit (MB)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val mb = quotaDialogText.toIntOrNull()
                    if (mb != null && mb >= 100) onStorageQuotaMbChange(mb)
                    showQuotaDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showQuotaDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Daily upload time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    showTimePicker = false
                    val h = timePickerState.hour.toString().padStart(2, '0')
                    val m = timePickerState.minute.toString().padStart(2, '0')
                    onAutoUploadTimeChange("$h:$m")
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        )
    }

    BackHandler { onBack() }

    // Active upload jobs: list all ZIPs that have a non-idle status
    var exportsRefreshTick by remember { mutableStateOf(0) }
    val allExports = remember(stats, exportsRefreshTick) { exporter.listExports() }
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
                        val usageMb = storageUsageBytes / (1024f * 1024f)
                        Text("Frames collected: ${stats.totalFrames}")
                        Text("Total detections: ${stats.totalDetections}")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Storage used: ${"%.3f".format(usageMb)} / $storageQuotaMb MB",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(
                                onClick = {
                                    quotaDialogText = storageQuotaMb.toString()
                                    showQuotaDialog = true
                                },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit storage limit",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onEditDataset,
                                enabled = stats.totalFrames > 0,
                                modifier = Modifier.weight(1f)
                            ) { Text("View dataset") }
                            OutlinedButton(
                                onClick = { showResetDialog = true },
                                enabled = stats.totalFrames > 0,
                                modifier = Modifier.weight(1f)
                            ) { Text("Reset collected data") }
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

                        // Auth status
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (isSignedIn) {
                                    Text("Signed in", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        signedInEmail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text("Not signed in", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Sign in to enable upload",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (isSignedIn) {
                                TextButton(onClick = onSignOut) { Text("Sign out") }
                            } else {
                                TextButton(onClick = onSignIn) { Text("Sign in") }
                            }
                        }

                        HorizontalDivider()

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

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showTimePicker = true },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Daily auto-upload time", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                autoUploadTime,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Text(
                            if (autoUploadLastDate != null) "Last auto-upload: $autoUploadLastDate"
                            else "Not yet auto-uploaded",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                                stats.totalFrames == 0    -> "No data yet"
                                !isSignedIn               -> "Sign in to upload"
                                uploadServiceUrl.isBlank() -> "Configure upload URL first"
                                else                      -> "Upload collected data"
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
                            WorkInfo.State.SUCCEEDED                        -> UploadStatus.UPLOADED
                            WorkInfo.State.FAILED                           -> UploadStatus.FAILED
                            else                                            -> null
                        }
                    } ?: exportFile.uploadStatus

                    UploadJobCard(
                        item = exportFile,
                        uploadStatus = liveStatus,
                        onRetry = { enqueueUpload(exportFile.file); refresh() },
                        onSucceeded = { exportsRefreshTick++ }
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
    onRetry: () -> Unit,
    onSucceeded: () -> Unit
) {
    LaunchedEffect(uploadStatus) {
        if (uploadStatus == UploadStatus.UPLOADED) onSucceeded()
    }
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