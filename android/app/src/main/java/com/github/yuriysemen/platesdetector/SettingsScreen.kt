package com.github.yuriysemen.platesdetector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing

@Composable
fun SettingsScreen(
    models: List<ModelSpec>,
    onPick: (ModelSpec) -> Unit,
    onPickFile: () -> Unit
) {
    var selectedId by rememberSaveable { mutableStateOf(models.first().id) }

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
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            Text("Change model", style = MaterialTheme.typography.titleMedium)

            OutlinedButton(onClick = onPickFile) {
                Text("Add model from file")
            }

            models.forEach { m ->
                OutlinedCard(
                    onClick = { selectedId = m.id },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(
                            selected = (selectedId == m.id),
                            onClick = { selectedId = m.id }
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

            Spacer(Modifier.height(4.dp))

            Button(onClick = {
                val base = models.first { it.id == selectedId }
                onPick(base)
            }) {
                Text("Use selected model")
            }
        }
    }
}
