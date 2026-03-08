package com.example.arspatialpinning.feature.ar.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.arspatialpinning.domain.model.RecordingState

@Composable
fun RecordingOverlay(
    recordingState: RecordingState
) {
    val text = when (recordingState) {
        is RecordingState.Preparing -> "Preparing recording..."
        is RecordingState.Active -> "Recording..."
        is RecordingState.Finalizing -> "Finalizing recording..."
        else -> null
    }

    if (text != null) {
        Box(
            modifier = Modifier
                .background(Color(0xAA000000))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }
    }
}
