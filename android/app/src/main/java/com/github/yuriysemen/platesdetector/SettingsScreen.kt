package com.github.yuriysemen.platesdetector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

@Composable
fun SettingsScreen(
    models: List<ModelSpec>,
    selectedModelId: String,
    onPick: (ModelSpec) -> Unit,
    onPickFile: () -> Unit,
    onDelete: (ModelSpec) -> Unit,
    onConfidenceChange: (modelId: String, conf: Float) -> Unit
) {
    var selectedId by rememberSaveable(selectedModelId) { mutableStateOf(selectedModelId) }
    var confOverrides by rememberSaveable { mutableStateOf<Map<String, Float>>(emptyMap()) }

    fun confFor(model: ModelSpec): Float = confOverrides[model.id] ?: model.conf

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
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            Text("Select model", style = MaterialTheme.typography.titleMedium)

            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
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
