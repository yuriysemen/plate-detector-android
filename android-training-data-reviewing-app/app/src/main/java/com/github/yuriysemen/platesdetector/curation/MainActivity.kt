package com.github.yuriysemen.platesdetector.curation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.github.yuriysemen.platesdetector.curation.ui.theme.CurationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CurationTheme {
                CurationApp()
            }
        }
    }
}
