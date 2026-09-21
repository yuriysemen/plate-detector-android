package com.github.yuriysemen.platesdetector.training

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private data class GuidelineSection(val title: String, val body: String)

private val CAPTURE_GUIDELINES = listOf(
    GuidelineSection(
        "Use Manual Capture for what auto-capture misses",
        "If the live box isn't drawn over a real plate, tap Manual Capture anyway. Those are " +
            "the cases the model needs most — auto-capture can only ever save plates it already " +
            "detects, so it can't fix its own blind spots on its own."
    ),
    GuidelineSection(
        "Vary angle and distance",
        "Don't shoot every car straight-on. For some shots, move to the side, crouch, or hold " +
            "the phone a bit higher/lower, and mix close-up with farther-away shots."
    ),
    GuidelineSection(
        "Seek out hard cases, don't avoid them",
        "Low light or dusk, glare and reflections, and dirty or partly-blocked plates are all " +
            "valuable — the model already handles clean, well-lit, head-on shots well."
    ),
    GuidelineSection(
        "More vehicles, not more photos of the same one",
        "A couple of shots per vehicle is enough. Move on to the next car rather than " +
            "collecting many near-duplicate frames of the same one."
    ),
    GuidelineSection(
        "Stay public, stay respectful",
        "Only photograph in public spaces (streets, public lots). Avoid framing people or " +
            "faces. Respect posted no-photography signs, and don't linger in one spot."
    )
)

@Composable
fun CaptureGuidelinesScreen(onAcknowledge: () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = Color.Black,
        contentColor = Color.White
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("Capture guidelines", style = MaterialTheme.typography.titleLarge)
            Text(
                "A few rules for collecting useful training data, before the camera opens:",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CAPTURE_GUIDELINES.forEach { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            section.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
            Button(
                onClick = onAcknowledge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Text("Got it — start capturing")
            }
        }
    }
}
