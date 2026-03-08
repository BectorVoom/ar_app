package com.example.arspatialpinning.feature.start

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.arspatialpinning.platform.media.SharedRecordingUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class StartScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun startButton_invokesNavigationCallback() {
        val clicked = AtomicBoolean(false)

        composeRule.setContent {
            MaterialTheme {
                StartScreen(
                    recordingUiState = SharedRecordingUiState(isAppResumed = true),
                    onStartAr = { clicked.set(true) },
                    onRecordClick = {},
                    onStopRecordClick = {},
                    onDownloadRecordingClick = {}
                )
            }
        }

        composeRule.onNodeWithText("AR Spatial Pinning").assertIsDisplayed()
        composeRule.onNodeWithText("Start AR Session").assertIsDisplayed()
        composeRule.onNodeWithText("Record").assertIsDisplayed()
        composeRule.onNodeWithText("Download Recording").assertIsDisplayed()
        composeRule.onNodeWithText("Start AR Session").performClick()

        composeRule.runOnIdle {
            assertTrue(clicked.get())
        }
    }
}
