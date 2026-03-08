package com.example.arspatialpinning.domain.usecase

import com.example.arspatialpinning.domain.model.RecordingState

class RequestRecordingUseCase {
    operator fun invoke(recordingState: RecordingState): Boolean {
        return recordingState is RecordingState.Idle
    }
}
