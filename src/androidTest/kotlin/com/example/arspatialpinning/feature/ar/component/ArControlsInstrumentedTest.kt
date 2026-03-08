package com.example.arspatialpinning.feature.ar.component

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.domain.model.RecordingState
import com.example.arspatialpinning.domain.model.SelectedImage
import com.example.arspatialpinning.feature.ar.ArUiEvent
import com.example.arspatialpinning.feature.ar.ArUiState
import com.example.arspatialpinning.feature.ar.ReticleUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArControlsInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stabilizedReticleWithSelectedImage_enablesPlace_andEmitsEvent() {
        val receivedEvents = mutableListOf<ArUiEvent>()
        val uiState = ArUiState(
            selectedImage = createSelectedImage(),
            placementMode = PlacementMode.WaitingForPlacement,
            isArReady = true,
            isImagePrepared = true,
            reticle = ReticleUiState(
                hasValidHit = true,
                stabilizationFrames = 3,
                isStabilized = true
            )
        )

        composeRule.setContent {
            MaterialTheme {
                ArControls(
                    uiState = uiState,
                    onEvent = { event -> receivedEvents += event }
                )
            }
        }

        composeRule.onNodeWithText("Place").assertIsEnabled()
        composeRule.onNodeWithText("Place").performClick()

        composeRule.runOnIdle {
            assertTrue(receivedEvents.contains(ArUiEvent.PlaceClicked))
        }
    }

    @Test
    fun activeRecording_showsStopRecordingAction() {
        val uiState = ArUiState(
            recordingState = RecordingState.Active(startedAtMillis = 1L)
        )

        composeRule.setContent {
            MaterialTheme {
                ArControls(
                    uiState = uiState,
                    onEvent = {}
                )
            }
        }

        composeRule.onNodeWithText("Stop Recording").assertIsDisplayed()
    }

    private fun createSelectedImage(): SelectedImage {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        return SelectedImage(
            uri = Uri.parse("content://test/image.png"),
            bitmap = bitmap
        )
    }
}
