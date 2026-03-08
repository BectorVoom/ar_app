package com.example.arspatialpinning.feature.ar.component

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.arspatialpinning.domain.model.DebugRenderStatus
import com.example.arspatialpinning.domain.model.HitTestUiModel
import com.example.arspatialpinning.domain.model.ImageFormat
import com.example.arspatialpinning.domain.model.PlacedImageState
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.domain.model.PlacementTransform
import com.example.arspatialpinning.domain.model.PreparedRenderAsset
import com.example.arspatialpinning.domain.model.PreviewRenderState
import com.example.arspatialpinning.domain.model.RecordedVideoArtifact
import com.example.arspatialpinning.domain.model.RecordingState
import com.example.arspatialpinning.domain.model.RenderAssetState
import com.example.arspatialpinning.domain.model.SelectedImage
import com.example.arspatialpinning.feature.ar.ArUiEvent
import com.example.arspatialpinning.feature.ar.ArUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArControlsInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun place_isDisabled_untilReadyVisibleStableHit_andMatchingDebugIdentity() {
        val selected = createSelectedImage(revision = 10L)
        val readyAsset = PreparedRenderAsset(
            assetHandleId = "asset-10",
            widthPx = 1000,
            heightPx = 500,
            aspectRatio = 2f,
            selectionRevision = 10L
        )
        val base = ArUiState(
            selectedImage = selected,
            isArReady = true,
            renderAssetState = RenderAssetState.Ready(readyAsset),
            currentHit = HitTestUiModel(
                hasValidHit = true,
                stabilizationFrames = 3,
                hasStableHit = true,
                trackableId = "plane"
            ),
            placementMode = PlacementMode.WaitingForPlacement
        )
        var currentState by mutableStateOf(base.copy(previewRenderState = PreviewRenderState.HiddenNoStableHit))

        composeRule.setContent {
            MaterialTheme {
                ArControls(
                    uiState = currentState,
                    onEvent = {}
                )
            }
        }
        composeRule.onNodeWithText("Place").assertIsNotEnabled()

        composeRule.runOnIdle {
            currentState = base.copy(
                previewRenderState = PreviewRenderState.Visible("asset-10"),
                debugRenderStatus = DebugRenderStatus(
                    previewNodeExists = true,
                    previewNodeAttached = true,
                    previewNodeVisible = true,
                    previewPoseUpdateFrameCount = 2L,
                    preparedAssetHandleId = "asset-10",
                    previewAssetHandleId = "asset-10",
                    previewPoseUpdatedForAssetHandleId = "asset-10"
                )
            )
        }
        composeRule.onNodeWithText("Place").assertIsEnabled()
    }

    @Test
    fun stalePreviewDebugData_fromOldImage_cannotEnablePlace_forNewImage() {
        val selected = createSelectedImage(revision = 11L)
        val readyAsset = PreparedRenderAsset(
            assetHandleId = "asset-new",
            widthPx = 1000,
            heightPx = 1000,
            aspectRatio = 1f,
            selectionRevision = 11L
        )

        val uiState = ArUiState(
            selectedImage = selected,
            isArReady = true,
            renderAssetState = RenderAssetState.Ready(readyAsset),
            previewRenderState = PreviewRenderState.Visible("asset-new"),
            currentHit = HitTestUiModel(
                hasValidHit = true,
                stabilizationFrames = 5,
                hasStableHit = true,
                trackableId = "plane"
            ),
            placementMode = PlacementMode.WaitingForPlacement,
            debugRenderStatus = DebugRenderStatus(
                previewNodeExists = true,
                previewNodeAttached = true,
                previewNodeVisible = true,
                previewPoseUpdateFrameCount = 9L,
                preparedAssetHandleId = "asset-new",
                previewAssetHandleId = "asset-old",
                previewPoseUpdatedForAssetHandleId = "asset-old"
            )
        )

        composeRule.setContent {
            MaterialTheme {
                ArControls(
                    uiState = uiState,
                    onEvent = {}
                )
            }
        }
        composeRule.onNodeWithText("Place").assertIsNotEnabled()
    }

    @Test
    fun repositionMode_showsConfirmAndCancelButtons() {
        val events = mutableListOf<ArUiEvent>()
        val uiState = ArUiState(
            selectedImage = createSelectedImage(revision = 20L),
            placedImage = PlacedImageState(
                anchorId = "anchor",
                widthMeters = 0.3f,
                heightMeters = 0.3f,
                transform = PlacementTransform()
            ),
            placementMode = PlacementMode.Repositioning,
            currentHit = HitTestUiModel(
                hasValidHit = true,
                stabilizationFrames = 3,
                hasStableHit = true,
                trackableId = "plane"
            )
        )

        composeRule.setContent {
            MaterialTheme {
                ArControls(
                    uiState = uiState,
                    onEvent = { events += it }
                )
            }
        }

        composeRule.onNodeWithText("Confirm Reposition").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel Reposition").assertIsDisplayed()

        composeRule.onNodeWithText("Confirm Reposition").performClick()
        composeRule.onNodeWithText("Cancel Reposition").performClick()

        composeRule.runOnIdle {
            assertTrue(events.contains(ArUiEvent.OnConfirmRepositionClick))
            assertTrue(events.contains(ArUiEvent.OnCancelRepositionClick))
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

    @Test
    fun deleteButton_emitsDeleteEvent_whenImagePlaced() {
        val events = mutableListOf<ArUiEvent>()
        val uiState = ArUiState(
            placedImage = PlacedImageState(
                anchorId = "anchor",
                widthMeters = 0.3f,
                heightMeters = 0.3f,
                transform = PlacementTransform()
            ),
            placementMode = PlacementMode.Placed
        )

        composeRule.setContent {
            MaterialTheme {
                ArControls(
                    uiState = uiState,
                    onEvent = { events += it }
                )
            }
        }

        composeRule.onNodeWithText("Delete").performClick()

        composeRule.runOnIdle {
            assertTrue(events.contains(ArUiEvent.OnDeleteClick))
        }
    }

    @Test
    fun downloadButton_disabledBeforeValidatedRecording_andEnabledAfterValidation() {
        var uiState by mutableStateOf(ArUiState(recordingState = RecordingState.Idle))

        composeRule.setContent {
            MaterialTheme {
                ArControls(
                    uiState = uiState,
                    onEvent = {}
                )
            }
        }

        composeRule.onNodeWithText("Download Recording").assertIsDisplayed().assertIsNotEnabled()

        composeRule.runOnIdle {
            uiState = uiState.copy(
                lastCompletedRecording = RecordedVideoArtifact(
                    sourceUri = Uri.parse("content://recordings/validated.mp4"),
                    displayName = "validated.mp4"
                )
            )
        }

        composeRule.onNodeWithText("Download Recording").assertIsEnabled()
    }

    private fun createSelectedImage(revision: Long): SelectedImage {
        return SelectedImage(
            uri = Uri.parse("content://test/image.png"),
            displayName = "image.png",
            mimeType = "image/png",
            widthPx = 4,
            heightPx = 4,
            format = ImageFormat.Png,
            selectionRevision = revision
        )
    }
}
