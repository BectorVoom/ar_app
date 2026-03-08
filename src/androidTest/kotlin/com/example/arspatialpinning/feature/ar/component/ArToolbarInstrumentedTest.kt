package com.example.arspatialpinning.feature.ar.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.arspatialpinning.domain.model.RecordingState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ArToolbarInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeRecording_showsTitle_andBackCallsCallback() {
        val backClicked = AtomicBoolean(false)

        composeRule.setContent {
            MaterialTheme {
                ArToolbar(
                    recordingState = RecordingState.Active(startedAtMillis = 1L),
                    onBack = { backClicked.set(true) }
                )
            }
        }

        composeRule.onNodeWithText("AR (Recording)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.runOnIdle {
            assertTrue(backClicked.get())
        }
    }

    @Test
    fun idle_showsDefaultTitle() {
        composeRule.setContent {
            MaterialTheme {
                ArToolbar(
                    recordingState = RecordingState.Idle,
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("AR").assertIsDisplayed()
    }
}
