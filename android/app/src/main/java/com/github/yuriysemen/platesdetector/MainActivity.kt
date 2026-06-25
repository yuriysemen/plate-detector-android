package com.github.yuriysemen.platesdetector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.github.yuriysemen.platesdetector.ui.theme.PlatesDetectorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        val openContribute = intent.getBooleanExtra(EXTRA_OPEN_CONTRIBUTE, false)
        setContent {
            MaterialTheme {
                LivePlateDetectionScreen(openContribute = openContribute)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            DATASET_CHANNEL_ID,
            "Dataset",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Auto-upload status for collected training data"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val DATASET_CHANNEL_ID   = "dataset"
        const val EXTRA_OPEN_CONTRIBUTE = "open_contribute"
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    PlatesDetectorTheme {
        Greeting("Hi!")
    }
}