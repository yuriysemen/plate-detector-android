package com.github.yuriysemen.platesdetector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
    onPickFile: () -> Unit,
    onDelete: (ModelSpec) -> Unit,
    confidenceForModel: (modelId: String) -> Float,
    onConfidenceChange: (modelId: String, conf: Float) -> Unit,
    collectTrainingData: Boolean,
    onCollectTrainingDataChange: (Boolean) -> Unit,
    collectFirstTimeShown: Boolean,
    onCollectFirstTimeShownAck: () -> Unit,
    analysisResolution: AnalysisResolution,
    onAnalysisResolutionChange: (AnalysisResolution) -> Unit,
    scanIntervalMs: Int,
    onScanIntervalMsChange: (Int) -> Unit,
    onNavigateToContribute: () -> Unit
) {
    var selectedId by rememberSaveable(selectedModelId) { mutableStateOf(selectedModelId) }
    var confOverrides by rememberSaveable { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var showCollectConsentDialog by rememberSaveable { mutableStateOf(false) }

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
                                Text(sourceLabel(m), style = MaterialTheme.typography.bodySmall)
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
                HorizontalDivider()
                TextButton(
                    onClick = onPickFile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Custom Model")
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

            if (showCollectConsentDialog) {
                AlertDialog(
                    onDismissRequest = { showCollectConsentDialog = false },
                    title = { Text("Contribute training data?") },
                    text = {
                        Text(
                            "When enabled, the app will:\n\n" +
                            "• Save camera frames to this device whenever a plate is detected.\n" +
                            "• Upload a packaged dataset to a private research server to improve plate detection.\n\n" +
                            "Images are stored under a private device identifier. " +
                            "To delete: open Contribute data and tap Reset collected data."
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

            // Contribute data row
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
                        if (collectTrainingData) "On — collecting and uploading frames"
                        else "Disabled — no frames are collected",
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
        }
    }
}
