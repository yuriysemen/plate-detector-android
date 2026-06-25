package com.github.yuriysemen.platesdetector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    models: List<ModelSpec>,
    selectedModelId: String,
    onPick: (ModelSpec) -> Unit,
    onPickFile: () -> Unit,
    onDelete: (ModelSpec) -> Unit,
    confidenceForModel: (modelId: String) -> Float,
    onConfidenceChange: (modelId: String, conf: Float) -> Unit,
    enableOCR: Boolean,
    onEnableOCRChange: (Boolean) -> Unit,
    collectTrainingData: Boolean,
    onCollectTrainingDataChange: (Boolean) -> Unit,
    collectFirstTimeShown: Boolean,
    onCollectFirstTimeShownAck: () -> Unit,
    storageQuotaMb: Int,
    onStorageQuotaMbChange: (Int) -> Unit,
    analysisResolution: AnalysisResolution,
    onAnalysisResolutionChange: (AnalysisResolution) -> Unit,
    targetFps: Int,
    onTargetFpsChange: (Int) -> Unit,
    onExportDataset: () -> Unit
) {
    var selectedId by rememberSaveable(selectedModelId) { mutableStateOf(selectedModelId) }
    var confOverrides by rememberSaveable { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var showCollectConsentDialog by rememberSaveable { mutableStateOf(false) }

    // Quota editing state — work in MB, convert to/from GB for display
    var quotaUnit by rememberSaveable { mutableStateOf(if (storageQuotaMb >= 1024) "GB" else "MB") }
    var quotaText by rememberSaveable(storageQuotaMb) {
        mutableStateOf(
            if (storageQuotaMb >= 1024) (storageQuotaMb / 1024).toString() else storageQuotaMb.toString()
        )
    }

    fun confFor(model: ModelSpec): Float = confOverrides[model.id] ?: confidenceForModel(model.id)

    val applySelection = {
        val base = models.firstOrNull { it.id == selectedId } ?: models.firstOrNull()
        if (base != null) {
            onPick(base)
        }
    }

    BackHandler {
        applySelection()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing
    ) { padding ->
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

            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(models) { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selectedId != m.id) {
                                        selectedId = m.id
                                    }
                                }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = (selectedId == m.id),
                                onClick = {
                                    if (selectedId != m.id) {
                                        selectedId = m.id
                                    }
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(m.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    sourceLabel(m),
                                    style = MaterialTheme.typography.bodySmall
                                )
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
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete model"
                                    )
                                }
                            }
                        }
                    }
                }
            }

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = enableOCR,
                    onCheckedChange = onEnableOCRChange
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Enable OCR", style = MaterialTheme.typography.bodyMedium)
            }

            Text(
                "Frame rate: $targetFps fps (~${1000 / targetFps.coerceAtLeast(1)} ms/frame)",
                style = MaterialTheme.typography.titleMedium
            )
            Slider(
                value = targetFps.toFloat(),
                onValueChange = { onTargetFpsChange(it.roundToInt()) },
                valueRange = 1f..15f,
                steps = 13
            )

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
                    title = { Text("Collect training data?") },
                    text = {
                        Text(
                            "The app will save camera frames and bounding boxes to this device " +
                            "whenever a plate is detected.\n\n" +
                            "• Saved to: device storage only — nothing is uploaded.\n" +
                            "• To delete: open Export dataset and tap Reset collected data."
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

            // Storage limit
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
                    label = { Text("Storage limit") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                listOf("MB", "GB").forEach { unit ->
                    val selected = unit == quotaUnit
                    TextButton(
                        onClick = {
                            if (unit != quotaUnit) {
                                val cur = quotaText.toIntOrNull() ?: 0
                                quotaUnit = unit
                                val newMb = if (unit == "GB") cur * 1024 else cur
                                quotaText = if (unit == "GB") (storageQuotaMb / 1024).coerceAtLeast(1).toString()
                                           else storageQuotaMb.toString()
                                if (newMb >= 100) onStorageQuotaMbChange(newMb)
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent
                        )
                    ) {
                        Text(unit)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Collect training data", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = collectTrainingData,
                    onCheckedChange = { enable ->
                        if (enable && !collectFirstTimeShown) {
                            showCollectConsentDialog = true
                        } else {
                            onCollectTrainingDataChange(enable)
                        }
                    }
                )
            }

            TextButton(onClick = onExportDataset) {
                Icon(
                    imageVector = Icons.Default.Archive,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export dataset")
            }

            TextButton(onClick = onPickFile) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Choose custom model"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Custom Model")
            }
        }
    }
}
