package com.github.yuriysemen.platesdetector

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainAppFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun clearPrefs() {
        composeRule.activity
            .getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun showsNoModelsScreenWhenAssetsEmpty() {
        composeRule.setContent {
            LivePlateDetectionScreen(
                modelProvider = { emptyList() }
            )
        }

        composeRule.onNodeWithText("No TFLite models found").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun showsModelPickerAndContinuesToLiveUi() {
        val models = listOf(
            ModelSpec(
                id = "test-model",
                title = "test-model",
                assetPath = "models/test-model.tflite",
                coordFormat = CoordFormat.XYXY_SCORE_CLASS,
                conf = 0.5f
            )
        )

        composeRule.setContent {
            LivePlateDetectionScreen(
                modelProvider = { models },
                hasPermissionOverride = true,
                cameraContent = { _, _, _ -> Box(Modifier.fillMaxSize()) }
            )
        }

        composeRule.onNodeWithText("Select model").assertIsDisplayed()
        composeRule.onNodeWithText("Continue").performClick()

        composeRule.onNodeWithText("Model: test-model").assertIsDisplayed()
        composeRule.onNodeWithText("Pause").assertIsDisplayed()
    }
}
