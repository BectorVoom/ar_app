package com.example.arspatialpinning.domain.usecase

import android.content.Intent
import android.graphics.Rect
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.platform.media.RecordingController

class StartRecordingUseCase(
    private val recordingController: RecordingController
) {
    suspend operator fun invoke(
        consentResultCode: Int,
        consentData: Intent,
        maximumWindowBounds: Rect
    ): AppResult<Unit> {
        return recordingController.startRecording(
            consentResultCode = consentResultCode,
            consentData = consentData,
            maximumWindowBounds = maximumWindowBounds
        )
    }
}
