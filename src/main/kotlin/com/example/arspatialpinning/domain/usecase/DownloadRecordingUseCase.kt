package com.example.arspatialpinning.domain.usecase

import android.net.Uri
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.platform.media.RecordingExporter

class DownloadRecordingUseCase(
    private val recordingExporter: RecordingExporter
) {
    suspend operator fun invoke(sourceUri: Uri, destinationUri: Uri): AppResult<Unit> {
        return recordingExporter.exportRecording(sourceUri, destinationUri)
    }
}
