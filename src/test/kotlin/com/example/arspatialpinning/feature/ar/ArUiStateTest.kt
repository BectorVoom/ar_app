package com.example.arspatialpinning.feature.ar

import android.net.Uri
import com.example.arspatialpinning.domain.model.DebugRenderStatus
import com.example.arspatialpinning.domain.model.HitTestUiModel
import com.example.arspatialpinning.domain.model.ImageFormat
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.domain.model.PreparedRenderAsset
import com.example.arspatialpinning.domain.model.PreviewRenderState
import com.example.arspatialpinning.domain.model.RecordingState
import com.example.arspatialpinning.domain.model.RenderAssetState
import com.example.arspatialpinning.domain.model.SelectedImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArUiStateTest {

    @Test
    fun `canPlace requires ready asset and visible preview with matching handle and debug identity`() {
        val selected = createSelectedImage(selectionRevision = 1L)
        val asset = PreparedRenderAsset(
            assetHandleId = "asset-1",
            widthPx = 800,
            heightPx = 400,
            aspectRatio = 2f,
            selectionRevision = 1L
        )
        val debug = DebugRenderStatus(
            previewNodeExists = true,
            previewNodeAttached = true,
            previewNodeVisible = true,
            previewPoseUpdateFrameCount = 3L,
            preparedAssetHandleId = "asset-1",
            previewAssetHandleId = "asset-1",
            previewPoseUpdatedForAssetHandleId = "asset-1"
        )

        val validState = ArUiState(
            isArReady = true,
            selectedImage = selected,
            renderAssetState = RenderAssetState.Ready(asset),
            previewRenderState = PreviewRenderState.Visible("asset-1"),
            currentHit = HitTestUiModel(
                hasValidHit = true,
                stabilizationFrames = 3,
                hasStableHit = true,
                trackableId = "plane-1"
            ),
            placementMode = PlacementMode.WaitingForPlacement,
            debugRenderStatus = debug
        )
        assertTrue(validState.canPlace)

        assertFalse(validState.copy(isArReady = false).canPlace)
        assertFalse(validState.copy(selectedImage = null).canPlace)
        assertFalse(validState.copy(renderAssetState = RenderAssetState.Preparing).canPlace)
        assertFalse(validState.copy(previewRenderState = PreviewRenderState.HiddenNoStableHit).canPlace)
        assertFalse(validState.copy(currentHit = validState.currentHit.copy(hasStableHit = false)).canPlace)
        assertFalse(validState.copy(placementMode = PlacementMode.Placed).canPlace)
    }

    @Test
    fun `canPlace rejects preview visible when debug identity mismatches ready handle`() {
        val selected = createSelectedImage(selectionRevision = 2L)
        val readyAsset = PreparedRenderAsset(
            assetHandleId = "asset-new",
            widthPx = 1200,
            heightPx = 1200,
            aspectRatio = 1f,
            selectionRevision = 2L
        )
        val mismatchedDebug = DebugRenderStatus(
            previewNodeExists = true,
            previewNodeAttached = true,
            previewNodeVisible = true,
            previewPoseUpdateFrameCount = 8L,
            preparedAssetHandleId = "asset-new",
            previewAssetHandleId = "asset-old",
            previewPoseUpdatedForAssetHandleId = "asset-old"
        )

        val state = ArUiState(
            isArReady = true,
            selectedImage = selected,
            renderAssetState = RenderAssetState.Ready(readyAsset),
            previewRenderState = PreviewRenderState.Visible("asset-new"),
            currentHit = HitTestUiModel(
                hasValidHit = true,
                stabilizationFrames = 4,
                hasStableHit = true,
                trackableId = "plane-1"
            ),
            placementMode = PlacementMode.WaitingForPlacement,
            debugRenderStatus = mismatchedDebug
        )

        assertFalse(state.canPlace)
    }

    @Test
    fun `record button is enabled only while AR is ready and recording state is idle`() {
        val ready = ArUiState(isArReady = true)
        assertTrue(ready.canRecord)

        assertFalse(ready.copy(isArReady = false).canRecord)
        assertFalse(ready.copy(recordingState = RecordingState.Preparing).canRecord)
        assertFalse(ready.copy(recordingState = RecordingState.Active(startedAtMillis = 1L)).canRecord)
        assertFalse(ready.copy(recordingState = RecordingState.Finalizing).canRecord)
    }

    private fun createSelectedImage(selectionRevision: Long): SelectedImage {
        return SelectedImage(
            uri = Uri.parse("content://test/image.png"),
            displayName = "image.png",
            mimeType = "image/png",
            widthPx = 100,
            heightPx = 100,
            format = ImageFormat.Png,
            selectionRevision = selectionRevision
        )
    }
}
