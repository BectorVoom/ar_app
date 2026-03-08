package com.example.arspatialpinning.feature.ar

import com.example.arspatialpinning.domain.model.ArAvailability
import com.example.arspatialpinning.domain.model.DebugRenderStatus
import com.example.arspatialpinning.domain.model.HitTestUiModel
import com.example.arspatialpinning.domain.model.PlacedImageState
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.domain.model.PreviewRenderState
import com.example.arspatialpinning.domain.model.RecordedVideoArtifact
import com.example.arspatialpinning.domain.model.RecordingState
import com.example.arspatialpinning.domain.model.RenderAssetState
import com.example.arspatialpinning.domain.model.SelectedImage

data class ArUiState(
    val hasCameraPermission: Boolean = false,
    val hasRecordAudioPermission: Boolean = false,
    val arAvailability: ArAvailability = ArAvailability.Unknown,
    val isArReady: Boolean = false,
    val isCameraTracking: Boolean = false,
    val selectedImage: SelectedImage? = null,
    val renderAssetState: RenderAssetState = RenderAssetState.None,
    val previewRenderState: PreviewRenderState = PreviewRenderState.HiddenNoSelection,
    val placedImage: PlacedImageState? = null,
    val placementMode: PlacementMode = PlacementMode.WaitingForPlacement,
    val currentHit: HitTestUiModel = HitTestUiModel(),
    val recordingState: RecordingState = RecordingState.Idle,
    val lastCompletedRecording: RecordedVideoArtifact? = null,
    val debugRenderStatus: DebugRenderStatus = DebugRenderStatus(),
    val blockingMessage: String? = null,
    val transientMessage: String? = null
) {
    val canSelectImage: Boolean = isArReady && !recordingState.blocksImageSelection

    val canPlace: Boolean
        get() {
            val readyAsset = (renderAssetState as? RenderAssetState.Ready)?.asset ?: return false
            val visibleHandle = (previewRenderState as? PreviewRenderState.Visible)?.assetHandleId ?: return false
            val handleMatches = readyAsset.assetHandleId == visibleHandle
            val debugMatches = debugRenderStatus.preparedAssetHandleId == readyAsset.assetHandleId &&
                debugRenderStatus.previewAssetHandleId == readyAsset.assetHandleId &&
                debugRenderStatus.previewPoseUpdatedForAssetHandleId == readyAsset.assetHandleId &&
                debugRenderStatus.previewNodeAttached &&
                debugRenderStatus.previewNodeVisible &&
                debugRenderStatus.previewPoseUpdateFrameCount > 0L
            return isArReady &&
                selectedImage != null &&
                handleMatches &&
                debugMatches &&
                currentHit.hasStableHit &&
                placementMode == PlacementMode.WaitingForPlacement
        }

    val canReposition: Boolean = placedImage != null && placementMode == PlacementMode.Placed
    val canConfirmReposition: Boolean =
        placedImage != null &&
            placementMode == PlacementMode.Repositioning &&
            currentHit.hasStableHit
    val canCancelReposition: Boolean = placementMode == PlacementMode.Repositioning
    val canDelete: Boolean = placedImage != null
    val canRecord: Boolean = recordingState is RecordingState.Idle
    val canDownloadRecording: Boolean =
        recordingState is RecordingState.Idle &&
            lastCompletedRecording != null
    val isRecordingBusy: Boolean =
        recordingState is RecordingState.Preparing ||
            recordingState is RecordingState.Active ||
            recordingState is RecordingState.Finalizing
}
