package com.example.arspatialpinning.platform.media

import android.content.Intent
import android.graphics.Rect
import com.example.arspatialpinning.common.AppResult

interface RecordingController {
    var onProjectionStopped: (() -> Unit)?

    fun createConsentIntent(): Intent

    suspend fun startRecording(
        consentResultCode: Int,
        consentData: Intent,
        maximumWindowBounds: Rect
    ): AppResult<Unit>

    suspend fun stopRecording(): AppResult<Unit>

    fun release()
}
