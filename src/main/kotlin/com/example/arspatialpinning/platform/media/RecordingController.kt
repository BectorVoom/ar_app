package com.example.arspatialpinning.platform.media

import android.content.Intent
import android.graphics.Rect
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.domain.model.RecordedVideoArtifact

interface RecordingController {
    var onProjectionStopped: (() -> Unit)?

    fun createConsentIntent(): AppResult<Intent>

    suspend fun startRecording(
        consentResultCode: Int,
        consentData: Intent,
        maximumWindowBounds: Rect
    ): AppResult<Unit>

    suspend fun stopRecording(): AppResult<RecordedVideoArtifact?>

    fun release()
}
