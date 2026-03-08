package com.example.arspatialpinning.feature.ar

import android.net.Uri
import com.example.arspatialpinning.domain.model.ArAvailability
import com.example.arspatialpinning.domain.model.DebugRenderStatus
import com.example.arspatialpinning.domain.model.HitTestUiModel
import com.example.arspatialpinning.domain.model.PreviewRenderState

sealed interface ArUiEvent {
    data object OnSelectImageClick : ArUiEvent
    data class OnImageSelected(val uri: Uri?) : ArUiEvent
    data object OnPlaceClick : ArUiEvent
    data object OnRepositionClick : ArUiEvent
    data object OnConfirmRepositionClick : ArUiEvent
    data object OnCancelRepositionClick : ArUiEvent
    data object OnDeleteClick : ArUiEvent
    data class OnScaleGesture(val factor: Float) : ArUiEvent
    data class OnRotateGesture(val deltaDeg: Float) : ArUiEvent
    data object OnRecordClick : ArUiEvent
    data object OnStopRecordClick : ArUiEvent
    data object OnDownloadRecordingClick : ArUiEvent
    data class OnDownloadDestinationSelected(val uri: Uri?) : ArUiEvent
    data class OnFrameHitUpdated(val hit: HitTestUiModel) : ArUiEvent
    data class OnPreviewRenderStateChanged(val state: PreviewRenderState) : ArUiEvent
    data class OnDebugRenderStatusChanged(val status: DebugRenderStatus) : ArUiEvent
    data class OnArAvailabilityResolved(val availability: ArAvailability) : ArUiEvent
    data class OnCameraTrackingChanged(val isTracking: Boolean) : ArUiEvent
    data object OnBackClick : ArUiEvent
}
