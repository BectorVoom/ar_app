package com.example.arspatialpinning.feature.start

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class StartScreenInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startButton_invokesNavigationCallback() {
        val clicked = AtomicBoolean(false)

        composeRule.setContent {
            MaterialTheme {
                StartScreen(
                    onStartAr = { clicked.set(true) }
                )
            }
        }

        composeRule.onNodeWithText("AR Spatial Pinning").assertIsDisplayed()
        composeRule.onNodeWithText("Start AR").assertIsDisplayed()
        composeRule.onNodeWithText("Start AR").performClick()

        composeRule.runOnIdle {
            assertTrue(clicked.get())
        }
    }
}
