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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    models: List<ModelSpec>,
    selectedModelId: String,
    showClassNames: Boolean,
    onShowClassNamesChange: (Boolean) -> Unit,
    onPick: (ModelSpec) -> Unit,
    onPickFile: () -> Unit
) {
    var selectedId by rememberSaveable { mutableStateOf(selectedModelId) }
    var showLabels by rememberSaveable { mutableStateOf(showClassNames) }
    val applySelection = {
        val base = models.first { it.id == selectedId }
        onShowClassNamesChange(showLabels)
        onPick(base)
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
                                        showLabels = false
                                        onShowClassNamesChange(false)
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
                                        showLabels = false
                                        onShowClassNamesChange(false)
                                    }
                                }
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
            }

            TextButton(onClick = onPickFile) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Choose custom model"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Custom Model")
            }

            Text("Labels", style = MaterialTheme.typography.titleMedium)

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showLabels = false
                            onShowClassNamesChange(false)
                        },
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioButton(
                        selected = !showLabels,
                        onClick = {
                            showLabels = false
                            onShowClassNamesChange(false)
                        }
                    )
                    Text("Without title", style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showLabels = true
                            onShowClassNamesChange(true)
                        },
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioButton(
                        selected = showLabels,
                        onClick = {
                            showLabels = true
                            onShowClassNamesChange(true)
                        }
                    )
                    Text("Show cocoClassName", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
