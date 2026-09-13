package com.github.yuriysemen.platesdetector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay
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
    collectTrainingData: Boolean,
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
    sessionExpired: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val exporter = remember { DatasetExporter(context) }
    val scope = rememberCoroutineScope()

    var stats by remember { mutableStateOf(exporter.readStats()) }
    var storageUsageBytes by remember { mutableLongStateOf(0L) }
    val quotaBytes = storageQuotaMb.toLong() * 1024L * 1024L
    val usagePct = if (quotaBytes > 0) (storageUsageBytes * 100L / quotaBytes).toInt().coerceIn(0, 100) else 0

    LaunchedEffect(stats) {
        storageUsageBytes = withContext(Dispatchers.IO) { exporter.trainingUsageBytes() }
    }

    var isUploading by remember { mutableStateOf(false) }
    var cooldownActive by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showQuotaDialog by remember { mutableStateOf(false) }
    var quotaDialogText by remember(storageQuotaMb) { mutableStateOf(storageQuotaMb.toString()) }
    var exportsRefreshTick by remember { mutableStateOf(0) }
    var showUploadLogDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { UploadLog.ensureLoaded(context) }
    val uploadLog by UploadLog.entries.collectAsState()

    // Ticks periodically so stuck-session restart eligibility (based on elapsed time since
    // the last status update) becomes available live, without requiring the user to leave
    // and re-enter the screen.
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            nowTick = System.currentTimeMillis()
        }
    }

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

    fun enqueueUpload(
        zipFile: java.io.File,
        frameCount: Int,
        forceAnyNetwork: Boolean = false,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        val networkType = if (forceAnyNetwork || uploadOnMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
        val constraints = Constraints.Builder().setRequiredNetworkType(networkType).build()
        val request = OneTimeWorkRequestBuilder<UploadDatasetWorker>()
            .setInputData(workDataOf(
                UploadDatasetWorker.KEY_ZIP_PATH         to zipFile.absolutePath,
                UploadDatasetWorker.KEY_DEVICE_ID        to deviceId,
                UploadDatasetWorker.KEY_UPLOAD_URL       to uploadServiceUrl,
                UploadDatasetWorker.KEY_USER_ID          to UploadPrefs.getCognitoUserId(context),
                UploadDatasetWorker.KEY_USER_POOL_ID     to UploadPrefs.getUserPoolId(context),
                UploadDatasetWorker.KEY_IDENTITY_POOL_ID to UploadPrefs.getIdentityPoolId(context),
                UploadDatasetWorker.KEY_FRAME_COUNT      to frameCount
            ))
            .addTag(zipFile.absolutePath)
            .addTag(UploadDatasetWorker.TAG_DATASET_UPLOAD)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            zipFile.absolutePath, policy, request
        )
        exporter.writeUploadStatus(zipFile, UploadStatus.PENDING)
    }

    fun doUpload() {
        scope.launch {
            cooldownActive = true
            errorMessage = null
            // Restart semantics: cancel every outstanding upload job (manual or auto) and
            // discard whatever ZIPs/sidecars they left behind before starting fresh.
            WorkManager.getInstance(context).cancelAllWorkByTag(UploadDatasetWorker.TAG_DATASET_UPLOAD)
            isUploading = true
            val config = DatasetExporter.SplitConfig()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    exporter.deleteAllExports()
                    exporter.exportSync(config)
                }
            }
            isUploading = false
            result.fold(
                onSuccess = { zipFile ->
                    enqueueUpload(zipFile, stats.totalFrames, forceAnyNetwork = true)
                    UploadLog.log(context, "Upload queued — ${stats.totalFrames} frames")
                    refresh()
                },
                onFailure = {
                    errorMessage = it.message ?: "Export failed"
                    UploadLog.log(
                        context,
                        "Packaging failed: ${it.message ?: it.javaClass.simpleName}",
                        UploadLog.Level.ERROR
                    )
                }
            )
            delay(60_000)
            cooldownActive = false
        }
    }

    fun restartUpload(zipFile: java.io.File) {
        scope.launch {
            // REPLACE cancels-and-inserts atomically in one WorkManager transaction — unlike a
            // separate cancelUniqueWork() + enqueueUniqueWork(..., KEEP) pair, there's no window
            // where the pending cancellation is still in flight and KEEP silently drops the new
            // enqueue because it still sees the old job as "pending".
            enqueueUpload(zipFile, stats.totalFrames, forceAnyNetwork = true, policy = ExistingWorkPolicy.REPLACE)
            UploadLog.log(context, "Upload restarted: ${zipFile.nameWithoutExtension}")
            exportsRefreshTick++
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
            text = { Text("Delete ${stats.totalFrames} buffered frames? This cannot be undone.") },
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

    if (showUploadLogDialog) {
        AlertDialog(
            onDismissRequest = { showUploadLogDialog = false },
            title = { Text("Upload activity") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (uploadLog.isEmpty()) {
                        Text(
                            "No upload activity recorded yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        uploadLog.reversed().forEach { entry ->
                            val time = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
                                .format(Date(entry.timeMs))
                            Text(
                                "$time  ${entry.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = when (entry.level) {
                                    UploadLog.Level.ERROR   -> MaterialTheme.colorScheme.error
                                    UploadLog.Level.SUCCESS -> Color(0xFF4CAF50)
                                    else                    -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUploadLogDialog = false }) { Text("Close") }
            },
            dismissButton = {
                if (uploadLog.isNotEmpty()) {
                    TextButton(onClick = { UploadLog.clear(context) }) { Text("Clear") }
                }
            }
        )
    }

    BackHandler { onBack() }

    // Active upload jobs: list all ZIPs that have a non-idle status. Successfully uploaded
    // entries are deleted (ZIP + sidecar) as soon as they succeed, so this is effectively
    // an "active uploads" list rather than a permanent history.
    val allExports = remember(stats, exportsRefreshTick) { exporter.listExports() }
    val sessionJobs = allExports.filter { it.uploadStatus != UploadStatus.NOT_QUEUED }
    val visibleJobs = sessionJobs.sortedByDescending { it.createdAt }.take(10)
    val hiddenCount = (sessionJobs.size - visibleJobs.size).coerceAtLeast(0)

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
                            "Storage limit reached ($storageQuotaMb MB). Upload your buffered frames to keep collecting."
                        else
                            "Training storage at $usagePct% — upload your buffered frames soon.",
                        isError = usagePct >= 100,
                        actionLabel = null,
                        onAction = null
                    )
                }
            }

            // Session expired warning takes priority over the generic "not configured" one —
            // it's the more specific, more actionable message.
            if (sessionExpired) {
                item {
                    StorageBanner(
                        text = "Your session expired — sign in again to resume uploads.",
                        isError = true,
                        actionLabel = "Sign in",
                        onAction = onSignIn
                    )
                }
            } else if (!isSignedIn) {
                item {
                    StorageBanner(
                        text = "Sign in to save captured frames — nothing is saved while signed out.",
                        isError = false,
                        actionLabel = "Sign in",
                        onAction = onSignIn
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
                        Text("Frames waiting to upload: ${stats.totalFrames}")
                        Text("Total boxes: ${stats.totalDetections}")

                        // Capture status — whether frames are actually being saved right now.
                        val captureStatus: Pair<String, Boolean> = when {
                            !collectTrainingData -> "Image saving is off" to false
                            sessionExpired       -> "Paused — session expired" to true
                            !isSignedIn          -> "Paused — sign in to save frames" to true
                            else                 -> "Saving frames — signed in as $signedInEmail" to false
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                captureStatus.first,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (captureStatus.second) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (collectTrainingData && (!isSignedIn || sessionExpired)) {
                                TextButton(onClick = onSignIn) { Text("Sign in") }
                            }
                        }
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
                        OutlinedButton(
                            onClick = { showResetDialog = true },
                            enabled = stats.totalFrames > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Reset collected data") }
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
                                when {
                                    isSignedIn -> {
                                        Text("Signed in", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            signedInEmail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    sessionExpired -> {
                                        Text(
                                            "Session expired",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            if (signedInEmail.isNotBlank()) "Sign in again as $signedInEmail" else "Sign in again to resume uploads",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    else -> {
                                        Text("Not signed in", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "Sign in to enable upload",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            if (isSignedIn) {
                                // "Sign in again" re-runs the sign-in flow for the same account to
                                // mint fresh tokens without signing out first — the fix for a
                                // wedged session that still reports "Signed in".
                                Column(horizontalAlignment = Alignment.End) {
                                    TextButton(onClick = onSignIn) { Text("Sign in again") }
                                    TextButton(onClick = onSignOut) { Text("Sign out") }
                                }
                            } else {
                                TextButton(onClick = onSignIn) { Text("Sign in") }
                            }
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Use mobile data", style = MaterialTheme.typography.bodyMedium)
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
                    enabled = !cooldownActive && stats.totalFrames > 0 && uploadConfigured,
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
                                sessionExpired          -> "Session expired — sign in"
                                !isSignedIn             -> "Sign in to upload"
                                else                    -> "Upload collected data"
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

            // Upload activity — one-line status + "Details" for the full log (mirrors the
            // model-update activity line in Settings). Visible whenever anything has been logged,
            // even after the per-upload history rows are gone (they're deleted on success).
            if (uploadLog.isNotEmpty()) {
                item {
                    val latest = uploadLog.last()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            latest.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (latest.level) {
                                UploadLog.Level.ERROR   -> MaterialTheme.colorScheme.error
                                UploadLog.Level.SUCCESS -> Color(0xFF4CAF50)
                                else                    -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showUploadLogDialog = true }) { Text("Details") }
                    }
                }
            }

            // Upload history — active + up to 10 most recent entries
            if (sessionJobs.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Spacer(Modifier.width(4.dp))
                    Text("Upload history", style = MaterialTheme.typography.titleMedium)
                }

                items(visibleJobs, key = { it.file.absolutePath }) { exportFile ->
                    val workInfos by WorkManager.getInstance(context)
                        .getWorkInfosByTagFlow(exportFile.file.absolutePath)
                        .collectAsState(initial = emptyList())
                    val liveStatus = workInfos.firstOrNull()?.let { info ->
                        when (info.state) {
                            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED     -> UploadStatus.PENDING
                            WorkInfo.State.RUNNING                             -> UploadStatus.UPLOADING
                            WorkInfo.State.SUCCEEDED                           -> UploadStatus.UPLOADED
                            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED    -> UploadStatus.FAILED
                            else                                               -> null
                        }
                    } ?: exportFile.uploadStatus

                    val progressData = workInfos.firstOrNull()?.progress
                    val progressBytes = progressData?.getLong(UploadDatasetWorker.KEY_PROGRESS_BYTES, -1L) ?: -1L
                    val progressTotal = progressData?.getLong(UploadDatasetWorker.KEY_PROGRESS_TOTAL, -1L) ?: -1L
                    val uploadProgress = if (progressBytes >= 0 && progressTotal > 0)
                        (progressBytes.toFloat() / progressTotal.toFloat()).coerceIn(0f, 1f)
                    else null

                    // A session is stuck — and thus restartable — if it failed outright, or if
                    // it's "pending" or "uploading" with no status update in over 30 minutes
                    // (the worker process died, was killed, is otherwise wedged, or is ENQUEUED
                    // waiting on a network constraint that's never going to be satisfied).
                    val canRestart = when (liveStatus) {
                        UploadStatus.FAILED -> true
                        UploadStatus.PENDING, UploadStatus.UPLOADING ->
                            nowTick - exportFile.statusUpdatedAt >= DatasetExporter.STALE_UPLOAD_TIMEOUT_MS
                        else -> false
                    }

                    UploadJobCard(
                        item = exportFile,
                        uploadStatus = liveStatus,
                        uploadProgress = uploadProgress,
                        canRestart = canRestart,
                        onSucceeded = { exportsRefreshTick++ },
                        onRestart = { restartUpload(exportFile.file) }
                    )
                }

                if (hiddenCount > 0) {
                    item {
                        Text(
                            "+ $hiddenCount more uploads not shown",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadJobCard(
    item: DatasetExporter.ExportFile,
    uploadStatus: UploadStatus,
    uploadProgress: Float?,
    canRestart: Boolean,
    onSucceeded: () -> Unit,
    onRestart: () -> Unit
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
            val subtitle = if (item.sizeBytes > 0)
                "${formatBytes(item.sizeBytes)} · ${formatDate(item.createdAt)}"
            else formatDate(item.createdAt)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    when (uploadStatus) {
                        UploadStatus.PENDING -> Text(
                            if (canRestart) "Stuck pending — no progress in 30+ min" else "Pending upload",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (canRestart) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        UploadStatus.UPLOADING -> {
                            if (!canRestart) {
                                if (uploadProgress != null) {
                                    CircularProgressIndicator(
                                        progress = { uploadProgress },
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                }
                            }
                            Text(
                                when {
                                    canRestart -> "Stuck uploading — no progress in 30+ min"
                                    uploadProgress != null -> "Uploading… ${(uploadProgress * 100).toInt()}%"
                                    else -> "Uploading…"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (canRestart) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary
                            )
                        }
                        UploadStatus.FAILED -> Text(
                            "Upload failed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        UploadStatus.UPLOADED -> Text(
                            "✓ Uploaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        else -> {}
                    }
                }
                if (canRestart) {
                    TextButton(onClick = onRestart) { Text("Restart") }
                }
            }

            // The recorded reason for a failed / stuck upload — this is the "why".
            val detail = item.statusDetail
            if (detail != null && (uploadStatus == UploadStatus.FAILED || canRestart)) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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