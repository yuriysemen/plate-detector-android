package com.github.yuriysemen.platesdetector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private enum class AuthMode { SIGN_IN, SIGN_UP, CONFIRM }

private fun friendlyAuthError(e: Throwable): String = when (e.javaClass.simpleName) {
    "UsernameExistsException"    -> "An account with this email already exists. Try signing in instead."
    "InvalidPasswordException"   -> e.message
        ?.substringAfter("failed to satisfy constraint: ", "")
        ?.replaceFirstChar { it.uppercase() }
        ?.takeIf { it.isNotBlank() }
        ?: "Password must be at least 8 characters and include upper and lowercase letters, a number, and a symbol."
    "InvalidParameterException"  -> if (e.message?.contains("email", ignoreCase = true) == true)
        "Please enter a valid email address."
    else
        e.message ?: "Invalid input."
    "NotAuthorizedException"     -> "Incorrect email or password."
    "UserNotFoundException"      -> "No account found with this email."
    "UserNotConfirmedException"  -> "Your email isn't verified yet."
    "CodeMismatchException"      -> "Incorrect verification code. Please try again."
    "ExpiredCodeException"       -> "This code has expired. Tap 'Resend code' to get a new one."
    "LimitExceededException",
    "TooManyRequestsException"   -> "Too many attempts. Please wait a moment and try again."
    "UnknownHostException",
    "SocketTimeoutException"     -> "No internet connection."
    "IllegalArgumentException"   -> e.message ?: "Invalid input."
    else                         -> e.message?.takeIf { it.isNotBlank() } ?: "An unexpected error occurred (${e.javaClass.simpleName})."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    authManager: CognitoAuthManager,
    onSignedIn: (userId: String) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler(onBack = onCancel)

    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf(AuthMode.SIGN_IN) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var successMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val title = when (mode) {
        AuthMode.SIGN_IN  -> "Sign in"
        AuthMode.SIGN_UP  -> "Create account"
        AuthMode.CONFIRM  -> "Verify email"
    }

    fun launch(block: suspend () -> Unit) {
        if (isLoading) return
        errorMessage = null
        successMessage = null
        isLoading = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorMessage = friendlyAuthError(e)
            } finally {
                isLoading = false
            }
        }
    }

    fun doSignIn() = launch {
        if (email.isBlank() || password.isBlank()) {
            throw IllegalArgumentException("Email and password are required")
        }
        try {
            val userId = authManager.signIn(email.trim(), password)
            onSignedIn(userId)
        } catch (e: Exception) {
            if (e.javaClass.simpleName == "UserNotConfirmedException") {
                // Account exists but email not yet verified — go straight to confirm screen
                mode = AuthMode.CONFIRM
                errorMessage = "Please verify your email. Enter the code sent to ${email.trim()}."
            } else {
                throw e
            }
        }
    }

    fun doSignUp() = launch {
        if (email.isBlank() || password.isBlank()) {
            throw IllegalArgumentException("Email and password are required")
        }
        if (password != confirmPassword) {
            throw IllegalArgumentException("Passwords do not match")
        }
        authManager.signUp(email.trim(), password)
        mode = AuthMode.CONFIRM
    }

    fun doConfirm() = launch {
        if (code.isBlank()) throw IllegalArgumentException("Verification code is required")
        authManager.confirmSignUp(email.trim(), code.trim())
        val userId = authManager.signIn(email.trim(), password)
        onSignedIn(userId)
    }

    fun doResend() = launch {
        authManager.resendConfirmationCode(email.trim())
        successMessage = "A new code has been sent to ${email.trim()}."
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (mode) {
                AuthMode.SIGN_IN, AuthMode.SIGN_UP -> {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; errorMessage = null },
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorMessage = null },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = if (mode == AuthMode.SIGN_IN) ImeAction.Done else ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (mode == AuthMode.SIGN_UP) {
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it; errorMessage = null },
                            label = { Text("Confirm password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                AuthMode.CONFIRM -> {
                    Text(
                        "A verification code was sent to ${email.trim()}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; errorMessage = null },
                        label = { Text("Verification code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            errorMessage?.let { msg ->
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            successMessage?.let { msg ->
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = {
                    when (mode) {
                        AuthMode.SIGN_IN -> doSignIn()
                        AuthMode.SIGN_UP -> doSignUp()
                        AuthMode.CONFIRM -> doConfirm()
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when (mode) {
                        AuthMode.SIGN_IN -> if (isLoading) "Signing in…" else "Sign in"
                        AuthMode.SIGN_UP -> if (isLoading) "Creating account…" else "Create account"
                        AuthMode.CONFIRM -> if (isLoading) "Verifying…" else "Verify"
                    }
                )
            }

            when (mode) {
                AuthMode.SIGN_IN -> TextButton(
                    onClick = { mode = AuthMode.SIGN_UP; errorMessage = null },
                    enabled = !isLoading
                ) { Text("Don't have an account? Create one") }

                AuthMode.SIGN_UP -> TextButton(
                    onClick = { mode = AuthMode.SIGN_IN; errorMessage = null },
                    enabled = !isLoading
                ) { Text("Already have an account? Sign in") }

                AuthMode.CONFIRM -> TextButton(
                    onClick = { doResend() },
                    enabled = !isLoading
                ) { Text("Resend code") }
            }
        }
    }
}
