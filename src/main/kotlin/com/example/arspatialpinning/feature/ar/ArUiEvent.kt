package com.example.arspatialpinning.feature.ar

sealed interface ArUiEvent {
    data object SelectImageClicked : ArUiEvent
    data object PlaceClicked : ArUiEvent
    data object MoveClicked : ArUiEvent
    data object ConfirmMoveClicked : ArUiEvent
    data object DeleteClicked : ArUiEvent
    data object RecordClicked : ArUiEvent
    data object StopRecordingClicked : ArUiEvent
    data object BackClicked : ArUiEvent
}
