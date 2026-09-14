package com.github.yuriysemen.platesdetector.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

private val SCAN_OPTIONS = listOf(
    5000 to "5 seconds",
    2000 to "2 seconds",
    1000 to "1 second",
    500  to "½ second",
    0    to "No delay"
)

private fun msToStep(ms: Int): Float =
    SCAN_OPTIONS.indexOfFirst { it.first == ms }.coerceAtLeast(0).toFloat()

private fun stepToMs(step: Float): Int =
    SCAN_OPTIONS.getOrNull(step.toInt().coerceIn(0, SCAN_OPTIONS.lastIndex))?.first ?: 1000

@Composable
fun SettingsScreen(
    models: List<ModelSpec>,
    selectedModelId: String,
    onPick: (ModelSpec) -> Unit,
    onDelete: (ModelSpec) -> Unit,
    confidenceForModel: (modelId: String) -> Float,
    onConfidenceChange: (modelId: String, conf: Float) -> Unit,
    collectTrainingData: Boolean,
    isSignedIn: Boolean,
    onCollectTrainingDataChange: (Boolean) -> Unit,
    collectFirstTimeShown: Boolean,
    onCollectFirstTimeShownAck: () -> Unit,
    analysisResolution: AnalysisResolution,
    onAnalysisResolutionChange: (AnalysisResolution) -> Unit,
    scanIntervalMs: Int,
    onScanIntervalMsChange: (Int) -> Unit,
    onNavigateToContribute: () -> Unit,
    onSignIn: () -> Unit,
    onOpenBackendConfig: () -> Unit,
    backendConfigured: Boolean,
    sessionExpired: Boolean = false,
    latestModelVersion: String = "",
    compatibleModelVersion: String = "",
    lastModelCheckTime: Long = 0L,
    onCheckNow: (suspend () -> Unit)? = null
) {
    var selectedId by rememberSaveable(selectedModelId) { mutableStateOf(selectedModelId) }
    var confOverrides by rememberSaveable { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var showCollectConsentDialog by rememberSaveable { mutableStateOf(false) }
    val logEntries by ModelUpdateLog.entries.collectAsState()
    val latestLogEntry = logEntries.lastOrNull()
    var showLogDialog by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun confFor(model: ModelSpec): Float = confOverrides[model.id] ?: confidenceForModel(model.id)

    val applySelection = {
        val base = models.firstOrNull { it.id == selectedId } ?: models.firstOrNull()
        if (base != null) onPick(base)
    }

    BackHandler { applySelection() }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            if (sessionExpired) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Your sign-in has expired. Model updates and uploads are paused until you sign in again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onNavigateToContribute) { Text("Sign in") }
                    }
                }
            }

            Text("Select model", style = MaterialTheme.typography.titleMedium)

            // Model list + custom model button inside one card
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(models) { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (selectedId != m.id) selectedId = m.id }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = (selectedId == m.id),
                                onClick = { if (selectedId != m.id) selectedId = m.id }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(m.title, style = MaterialTheme.typography.titleMedium)
                                val label = if (m.version != null)
                                    "${sourceLabel(m)} · v${m.version}"
                                else
                                    sourceLabel(m)
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                if (selectedId == m.id) {
                                    val detail = when (m.origin) {
                                        ModelOrigin.DEFAULT    -> "Bundled with the app · always available"
                                        ModelOrigin.DOWNLOADED -> "On-device copy · updates automatically when signed in"
                                        else                   -> null
                                    }
                                    if (detail != null) {
                                        Text(
                                            detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                if (!m.description.isNullOrBlank()) {
                                    Text(
                                        m.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (m.isDeletable) {
                                IconButton(onClick = { onDelete(m) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete model")
                                }
                            }
                        }
                    }
                }
            }

            // Model activity — one-line status + Details + Check now
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = latestLogEntry?.message ?: "No model activity since app start",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (latestLogEntry?.level) {
                        ModelUpdateLog.Level.ERROR   -> MaterialTheme.colorScheme.error
                        ModelUpdateLog.Level.SUCCESS -> Color(0xFF4CAF50)
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.weight(1f)
                )
                if (logEntries.isNotEmpty()) {
                    TextButton(onClick = { showLogDialog = true }) {
                        Text("Details", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (onCheckNow != null) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .padding(start = 8.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(onClick = {
                            scope.launch {
                                isChecking = true
                                try { onCheckNow() } catch (_: Exception) {}
                                isChecking = false
                            }
                        }) {
                            Text("Check now", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (lastModelCheckTime > 0L) {
                val now = System.currentTimeMillis()
                val agoMs = now - lastModelCheckTime
                val lastStr = when {
                    agoMs < 60_000L          -> "just now"
                    agoMs < 3_600_000L       -> "${agoMs / 60_000} min ago"
                    agoMs < 86_400_000L      -> "${agoMs / 3_600_000} h ago"
                    else                     -> "${agoMs / 86_400_000} d ago"
                }
                val nextMs = lastModelCheckTime + 3_600_000L - now
                val nextStr = when {
                    nextMs <= 0              -> "soon"
                    nextMs < 3_600_000L      -> "in ${nextMs / 60_000} min"
                    else                     -> "in ${nextMs / 3_600_000} h"
                }
                Text(
                    "Model check: last $lastStr · next $nextStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (latestModelVersion.isNotEmpty() && latestModelVersion != compatibleModelVersion) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Model v$latestModelVersion is available but requires a newer app version.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Confidence threshold for selected model
            val selected = models.firstOrNull { it.id == selectedId }
            if (selected != null) {
                val currentConf = confFor(selected)
                Text(
                    "Confidence threshold: ${"%.2f".format(currentConf)}",
                    style = MaterialTheme.typography.titleMedium
                )
                Slider(
                    value = currentConf,
                    onValueChange = { newValue ->
                        confOverrides = confOverrides + (selected.id to newValue)
                        onConfidenceChange(selected.id, newValue)
                    },
                    valueRange = 0.05f..0.95f
                )
            }

            // Scan interval
            val scanLabel = SCAN_OPTIONS.firstOrNull { it.first == scanIntervalMs }?.second ?: "1 second"
            Text(
                "Scan interval: $scanLabel",
                style = MaterialTheme.typography.titleMedium
            )
            Slider(
                value = msToStep(scanIntervalMs),
                onValueChange = { onScanIntervalMsChange(stepToMs(it)) },
                valueRange = 0f..(SCAN_OPTIONS.lastIndex.toFloat()),
                steps = SCAN_OPTIONS.lastIndex - 1
            )

            // Analysis resolution
            Text("Analysis resolution", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                AnalysisResolution.entries.forEach { res ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAnalysisResolutionChange(res) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(
                            selected = analysisResolution == res,
                            onClick = { onAnalysisResolutionChange(res) }
                        )
                        Column {
                            Text(res.label, style = MaterialTheme.typography.bodyMedium)
                            Text(res.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (showLogDialog) {
                AlertDialog(
                    onDismissRequest = { showLogDialog = false },
                    title = { Text("Model update log") },
                    text = {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (logEntries.isEmpty()) {
                                Text(
                                    "No activity recorded yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            } else {
                                logEntries.reversed().forEach { entry ->
                                    val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
                                        .format(Date(entry.timeMs))
                                    val color = when (entry.level) {
                                        ModelUpdateLog.Level.ERROR   -> MaterialTheme.colorScheme.error
                                        ModelUpdateLog.Level.SUCCESS -> Color(0xFF4CAF50)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                    Text(
                                        "$time  ${entry.message}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = color
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLogDialog = false }) { Text("Close") }
                    }
                )
            }

            if (showCollectConsentDialog) {
                AlertDialog(
                    onDismissRequest = { showCollectConsentDialog = false },
                    title = { Text("Contribute training data?") },
                    text = {
                        Text(
                            "When enabled and you are signed in, the app will:\n\n" +
                            "• Save camera frames whenever a plate is detected.\n" +
                            "• Upload them to a private research server to improve plate detection.\n\n" +
                            "Frames are held on this device only until the next upload, then deleted. " +
                            "Nothing is saved while you are signed out."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showCollectConsentDialog = false
                            onCollectFirstTimeShownAck()
                            onCollectTrainingDataChange(true)
                        }) { Text("Enable") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCollectConsentDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // "Contribute data" is only shown to a signed-in user — it's useless without an
            // account (REQ-026 gates capture on sign-in). When signed out, this row is a plain
            // "Sign in" entry instead, so authentication is still reachable from Settings.
            if (isSignedIn) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToContribute() }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Contribute data", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (collectTrainingData) "On — saving and uploading frames"
                            else "Off — no frames are saved",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = collectTrainingData,
                        onCheckedChange = { enable ->
                            if (enable && !collectFirstTimeShown) showCollectConsentDialog = true
                            else onCollectTrainingDataChange(enable)
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSignIn() }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sign in", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (sessionExpired) "Session expired — sign in to sync again"
                            else "Sign in to contribute data and get model updates",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            // Always reachable — you need this configured before sign-in works at all, and it
            // may need to change later without a rebuild (REQ-032).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenBackendConfig() }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Backend configuration", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (backendConfigured) "Configured" else "Not configured — required before sign-in",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}
