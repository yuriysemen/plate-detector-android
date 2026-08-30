package com.github.yuriysemen.platesdetector.curation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private enum class Tab(val label: String) { NOT_PROCESSED("Not Processed"), IN_PROGRESS("In Progress"), DONE("Done") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurationHomeScreen(
    vm: CurationViewModel,
    curatorEmail: String,
    onSignOut: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = Tab.entries

    LaunchedEffect(Unit) { vm.refreshAll() }

    // Full-screen review takes over the whole surface.
    val session = vm.session
    if (session != null) {
        ReviewScreen(
            vm = vm,
            onBack = { vm.closeSession() },
        )
        BusyDialog(vm.busy)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dataset curation") },
                actions = {
                    IconButton(onClick = {
                        when (tabs[tab]) {
                            Tab.NOT_PROCESSED -> vm.refreshNotProcessed()
                            Tab.IN_PROGRESS -> vm.refreshInProgress()
                            Tab.DONE -> vm.refreshDone()
                        }
                    }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    TextButton(onClick = onSignOut) { Text("Sign out") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    val count = when (t) {
                        Tab.NOT_PROCESSED -> (vm.notProcessed as? TabState.Data)?.items?.size
                        Tab.IN_PROGRESS -> (vm.inProgress as? TabState.Data)?.items?.size
                        Tab.DONE -> null
                    }
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            BadgedBox(badge = {
                                if (count != null && count > 0) Badge { Text("$count") }
                            }) {
                                Icon(
                                    when (t) {
                                        Tab.NOT_PROCESSED -> Icons.Default.Inbox
                                        Tab.IN_PROGRESS -> Icons.Default.Pending
                                        Tab.DONE -> Icons.Default.CheckCircle
                                    },
                                    contentDescription = t.label,
                                )
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tabs[tab]) {
                Tab.NOT_PROCESSED -> NotProcessedTab(vm)
                Tab.IN_PROGRESS -> InProgressTab(vm)
                Tab.DONE -> DoneTab(vm)
            }

            vm.errorBanner?.let { msg ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    action = { TextButton(onClick = { vm.clearErrorBanner() }) { Text("Dismiss") } },
                ) { Text(msg) }
            }
        }
    }

    BusyDialog(vm.busy)
}

@Composable
fun BusyDialog(busy: BusyState?) {
    if (busy == null) return
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text(busy.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy.total > 1) {
                    LinearProgressIndicator(
                        progress = { busy.current.toFloat() / busy.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${busy.current} / ${busy.total}", style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
    )
}

/** Shared loading / error / empty scaffolding for the three tabs. */
@Composable
fun <T> TabContent(
    state: TabState<T>,
    emptyText: String,
    content: @Composable (List<T>) -> Unit,
) {
    when (state) {
        TabState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is TabState.Error -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        is TabState.Data -> {
            if (state.items.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(emptyText, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                content(state.items)
            }
        }
    }
}
