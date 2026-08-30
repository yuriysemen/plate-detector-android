package com.github.yuriysemen.platesdetector.curation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private sealed interface S3CheckState {
    data object Running : S3CheckState
    data class Ok(val keyCount: Int) : S3CheckState
    data class Failed(val message: String) : S3CheckState
}

/**
 * REQ-022 placeholder home. Proves `CuratorRole` works with a live `ListObjectsV2`, and lists
 * the three workflow screens that land in REQ-023.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    auth: CuratorAuthManager,
    s3: S3Access,
    onSignOut: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var check by remember { mutableStateOf<S3CheckState>(S3CheckState.Running) }

    fun runCheck() {
        check = S3CheckState.Running
        scope.launch {
            check = s3.checkAccess().fold(
                onSuccess = { S3CheckState.Ok(it) },
                onFailure = {
                    if (it is SessionExpiredException) auth.markSessionExpired()
                    val staleToken = it.message?.contains("not authorized", ignoreCase = true) == true &&
                        runCatching { !auth.tokenHasCuratorRoleClaim() }.getOrDefault(false)
                    val hint = if (staleToken)
                        "\n\nYour sign-in token was issued before this account got curator access. " +
                            "Sign out and sign in again to refresh it."
                    else ""
                    S3CheckState.Failed((it.message ?: it.javaClass.simpleName) + hint)
                }
            )
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { runCheck() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dataset curation") },
                actions = { TextButton(onClick = onSignOut) { Text("Sign out") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Signed in as ${auth.currentUserEmail().ifBlank { "curator" }}",
                style = MaterialTheme.typography.bodyMedium
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("S3 access", style = MaterialTheme.typography.titleMedium)
                    when (val c = check) {
                        S3CheckState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(0.dp))
                            Text(
                                "  Checking CuratorRole access to ${CurationConfig.datasetBucket}…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        is S3CheckState.Ok -> Text(
                            "OK — reached ${CurationConfig.datasetBucket} (uploads/ keys visible: ${c.keyCount}).",
                            style = MaterialTheme.typography.bodySmall
                        )
                        is S3CheckState.Failed -> {
                            Text(
                                "Failed: ${c.message}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            OutlinedButton(onClick = { runCheck() }) { Text("Retry") }
                        }
                    }
                }
            }

            Text("Workflow", style = MaterialTheme.typography.titleMedium)
            listOf(
                "Not Processed" to "Raw uploads ready to review",
                "In Progress" to "Packages you're reviewing",
                "Done" to "Completed, training-ready packages"
            ).forEach { (title, subtitle) ->
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = { Text(subtitle) },
                    trailingContent = { Text("Soon", color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                "The three screens above arrive with REQ-023.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}
