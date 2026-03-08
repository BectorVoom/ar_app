package com.example.arspatialpinning.domain.model

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Preparing : RecordingState
    data class Active(val startedAtMillis: Long) : RecordingState
    data object Finalizing : RecordingState
    data class Failed(val message: String) : RecordingState

    val blocksImageSelection: Boolean
        get() = this is Preparing || this is Active || this is Finalizing
}
