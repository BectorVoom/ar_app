package com.example.arspatialpinning.feature.ar

sealed interface ArUiEvent {
    data object SelectImageClicked : ArUiEvent
    data object PlaceClicked : ArUiEvent
    data object RepositionClicked : ArUiEvent
    data object ConfirmRepositionClicked : ArUiEvent
    data object CancelRepositionClicked : ArUiEvent
    data object DeleteClicked : ArUiEvent
    data object RecordClicked : ArUiEvent
    data object StopRecordingClicked : ArUiEvent
    data object BackClicked : ArUiEvent
}
