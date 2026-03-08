package com.example.arspatialpinning.feature.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.arspatialpinning.domain.model.RecordingState
import com.example.arspatialpinning.platform.media.SharedRecordingUiState

@Composable
fun StartScreen(
    recordingUiState: SharedRecordingUiState,
    onStartAr: () -> Unit,
    onRecordClick: () -> Unit,
    onStopRecordClick: () -> Unit,
    onDownloadRecordingClick: () -> Unit
) {
    val isRecording = recordingUiState.recordingState is RecordingState.Active ||
        recordingUiState.recordingState is RecordingState.Preparing ||
        recordingUiState.recordingState is RecordingState.Finalizing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "AR Spatial Pinning",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Select one image, preview it in AR with a stable center reticle, then place and transform it.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp)
        )

        Button(
            onClick = onStartAr,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Text(text = "Start AR Session")
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = recordingUiState.canRecord || isRecording,
                onClick = {
                    if (isRecording) {
                        onStopRecordClick()
                    } else {
                        onRecordClick()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isRecording) "Stop Recording" else "Record")
            }

            Button(
                enabled = recordingUiState.canDownloadRecording,
                onClick = onDownloadRecordingClick,
                modifier = Modifier.weight(1f)
            ) {
                Text("Download Recording")
            }
        }
    }
}
