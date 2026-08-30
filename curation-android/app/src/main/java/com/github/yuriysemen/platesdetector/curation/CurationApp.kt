package com.github.yuriysemen.platesdetector.curation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private sealed interface CurationRoute {
    data object Loading : CurationRoute
    data object SignedOut : CurationRoute
    data object AccessDenied : CurationRoute
    data object Home : CurationRoute
}

@Composable
fun CurationApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { CuratorAuthManager(context.applicationContext) }

    var route by remember { mutableStateOf<CurationRoute>(CurationRoute.Loading) }

    // Decide where a signed-in user goes: curator → Home, otherwise → AccessDenied.
    // Any auth failure (expired refresh token, offline) drops back to sign-in.
    suspend fun routeSignedInUser() {
        route = try {
            if (auth.isCurator()) CurationRoute.Home else CurationRoute.AccessDenied
        } catch (e: SessionExpiredException) {
            auth.markSessionExpired()
            CurationRoute.SignedOut
        } catch (e: Exception) {
            CurationRoute.SignedOut
        }
    }

    LaunchedEffect(Unit) {
        if (!auth.isConfigured()) {
            route = CurationRoute.SignedOut
            return@LaunchedEffect
        }
        if (auth.isSignedIn()) routeSignedInUser() else route = CurationRoute.SignedOut
    }

    // The XML theme is a hardcoded light window background; without an explicit Surface the
    // Compose color scheme (which follows the system dark/light setting) would paint light text
    // on that light window in dark mode. Surface pins a matching background under every screen.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (route) {
            CurationRoute.Loading -> LoadingScreen()

            CurationRoute.SignedOut -> AuthScreen(
                auth = auth,
                onSignedIn = { scope.launch { routeSignedInUser() } }
            )

            CurationRoute.AccessDenied -> AccessDeniedScreen(
                email = auth.currentUserEmail(),
                onSignOut = {
                    auth.signOut()
                    route = CurationRoute.SignedOut
                }
            )

            CurationRoute.Home -> HomeScreen(
                auth = auth,
                s3 = remember { S3Access(auth) },
                onSignOut = {
                    auth.signOut()
                    route = CurationRoute.SignedOut
                }
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Checking session…", style = MaterialTheme.typography.bodyMedium)
    }
}
