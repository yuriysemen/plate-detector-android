package com.github.yuriysemen.platesdetector.curation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private fun inactiveLabel(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 48 -> "${minutes / 60}h"
        else -> "${minutes / (60 * 24)}d"
    }
}

@Composable
fun NotProcessedTab(vm: CurationViewModel) {
    TabContent(vm.notProcessed, emptyText = "No unprocessed uploads.") { uploads ->
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(uploads, key = { it.key }) { u ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(u.filename, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${Format.shortSub(u.userSub)} · ${u.deviceId} · " +
                                "${Format.dateTime(u.lastModifiedMs)} · ${Format.size(u.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = { vm.startReview(u) }) { Text("Start reviewing") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InProgressTab(vm: CurationViewModel) {
    var confirmDiscard by remember { mutableStateOf<CurationManifest?>(null) }
    val now = System.currentTimeMillis()

    TabContent(vm.inProgress, emptyText = "Nothing in progress.") { entries ->
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries, key = { it.manifest.packageId }) { entry ->
                val m = entry.manifest
                val stale = entry.isStale(now)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(m.filename, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Started ${Format.iso(m.startedAt)}" +
                                (m.curatorEmail.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "✓ ${m.accepted}   ✗ ${m.rejected}   • ${m.pending} left  (of ${m.total})",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (stale) {
                            Text(
                                "⚠ Inactive ${inactiveLabel(entry.inactiveMs(now))} — may be abandoned",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { confirmDiscard = m }) {
                                Text(if (stale) "Discard" else "Release")
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { vm.openInProgress(m) }) {
                                Text(if (stale) "Take over" else "Review")
                            }
                            Button(
                                onClick = { vm.complete(m) {} },
                                enabled = m.pending == 0,
                            ) { Text("Complete") }
                        }
                    }
                }
            }
        }
    }

    confirmDiscard?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("Discard ${m.filename}?") },
            text = { Text("All review progress will be lost. The package returns to Not Processed.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = null; vm.release(m) {} }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun DoneTab(vm: CurationViewModel) {
    TabContent(vm.done, emptyText = "No completed packages.") { dones ->
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(dones, key = { it.packageId }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(d.filename, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Completed ${Format.iso(d.completedAt)}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("${d.total} total   ✓ ${d.accepted}   ✗ ${d.rejected}",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
