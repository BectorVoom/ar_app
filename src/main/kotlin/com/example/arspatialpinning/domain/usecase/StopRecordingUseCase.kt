package com.example.arspatialpinning.domain.usecase

import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.domain.model.RecordedVideoArtifact
import com.example.arspatialpinning.platform.media.RecordingController

class StopRecordingUseCase(
    private val recordingController: RecordingController
) {
    suspend operator fun invoke(): AppResult<RecordedVideoArtifact?> = recordingController.stopRecording()
}
