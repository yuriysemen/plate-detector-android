package com.github.yuriysemen.platesdetector.training

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Lets a user view/edit the Cognito + API backend this app talks to, without a rebuild (REQ-032).
 * A saved value is "pinned" (see [UploadPrefs]) — AppConfig.seedPrefsIfNeeded() will never
 * overwrite it again, even if a later build carries different local.properties values.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendConfigScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onIdentityChanged: () -> Unit
) {
    val context = LocalContext.current
    var userPoolId by rememberSaveable { mutableStateOf(UploadPrefs.getUserPoolId(context)) }
    var appClientId by rememberSaveable { mutableStateOf(UploadPrefs.getAppClientId(context)) }
    var identityPoolId by rememberSaveable { mutableStateOf(UploadPrefs.getIdentityPoolId(context)) }
    var uploadUrl by rememberSaveable { mutableStateOf(UploadPrefs.getUploadUrl(context)) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun save() {
        val pool = userPoolId.trim()
        val client = appClientId.trim()
        val identity = identityPoolId.trim()
        val url = uploadUrl.trim()

        if (pool.isEmpty() || client.isEmpty() || identity.isEmpty() || url.isEmpty()) {
            error = "All four fields are required."
            return
        }
        if (!AppConfig.IDENTITY_POOL_ID_PATTERN.matches(identity)) {
            error = "Identity Pool ID must look like <region>:<uuid>, " +
                "e.g. us-east-1:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
            return
        }
        error = null

        val identityChanged =
            pool != UploadPrefs.getUserPoolId(context) ||
            client != UploadPrefs.getAppClientId(context) ||
            identity != UploadPrefs.getIdentityPoolId(context)

        UploadPrefs.setUserPoolIdManual(context, pool)
        UploadPrefs.setAppClientIdManual(context, client)
        UploadPrefs.setIdentityPoolIdManual(context, identity)
        UploadPrefs.setUploadUrlManual(context, url)

        if (identityChanged) onIdentityChanged()
        onSaved()
    }

    BackHandler(onBack = onBack)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Backend configuration") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Points this app at the Cognito/API backend deployed by aws-training-infra/aws. Copy these " +
                    "values from the `sam deploy` stack outputs — see aws-training-infra/aws/README.md.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            OutlinedTextField(
                value = userPoolId,
                onValueChange = { userPoolId = it },
                label = { Text("Cognito User Pool ID") },
                supportingText = { Text("The pool of accounts used to sign in. Stack output: UserPoolId") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = appClientId,
                onValueChange = { appClientId = it },
                label = { Text("Cognito App Client ID") },
                supportingText = { Text("Identifies this app to the pool. Stack output: UserPoolClientId") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = identityPoolId,
                onValueChange = { identityPoolId = it },
                label = { Text("Cognito Identity Pool ID") },
                supportingText = {
                    Text(
                        "Format <region>:<uuid> — exchanges a sign-in for temporary AWS " +
                            "credentials; the AWS region for every call comes from this prefix. " +
                            "Stack output: IdentityPoolId"
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uploadUrl,
                onValueChange = { uploadUrl = it },
                label = { Text("Upload service URL") },
                supportingText = { Text("The API Gateway base URL. Stack output: UploadServiceUrl") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                "None of these are secrets — no static AWS keys are ever stored here. Changing " +
                    "the User Pool / App Client / Identity Pool signs you out, since a cached " +
                    "session belongs to the previous backend.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
        }
    }
}
