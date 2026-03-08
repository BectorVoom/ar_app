package com.example.arspatialpinning.feature.ar

import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.domain.model.PlacedImageState
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.domain.model.RecordingState
import com.example.arspatialpinning.domain.model.SelectedImage

data class ReticleUiState(
    val hasValidHit: Boolean = false,
    val stabilizationFrames: Int = 0,
    val isStabilized: Boolean = false
)

data class ArUiState(
    val selectedImage: SelectedImage? = null,
    val placedImage: PlacedImageState? = null,
    val placementMode: PlacementMode = PlacementMode.WaitingForPlacement,
    val isArReady: Boolean = false,
    val isImagePrepared: Boolean = false,
    val reticle: ReticleUiState = ReticleUiState(),
    val recordingState: RecordingState = RecordingState.Idle,
    val cameraPermissionGranted: Boolean = false,
    val blockingError: AppError? = null
) {
    val canSelectImage: Boolean = !recordingState.blocksImageSelection
    val canPlace: Boolean = selectedImage != null &&
        isArReady &&
        isImagePrepared &&
        placementMode == PlacementMode.WaitingForPlacement &&
        reticle.isStabilized
    val canMove: Boolean = placedImage != null && placementMode == PlacementMode.Placed
    val canConfirmMove: Boolean = placementMode == PlacementMode.Repositioning && reticle.isStabilized
    val canDelete: Boolean = placedImage != null
}
