package com.github.yuriysemen.platesdetector

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainFlowShowsNoModelsMessaging() {
        composeRule.onNodeWithText("No TFLite models found").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Add at least one model file",
            substring = true
        ).assertIsDisplayed()
    }

    @Test
    fun retryKeepsNoModelsState() {
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("No TFLite models found").assertIsDisplayed()
    }
}
