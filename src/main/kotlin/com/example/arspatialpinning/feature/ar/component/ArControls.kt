package com.example.arspatialpinning.feature.ar.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.domain.model.RecordingState
import com.example.arspatialpinning.feature.ar.ArUiEvent
import com.example.arspatialpinning.feature.ar.ArUiState

@Composable
fun ArControls(
    uiState: ArUiState,
    onEvent: (ArUiEvent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = uiState.canSelectImage,
                onClick = { onEvent(ArUiEvent.SelectImageClicked) },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Select Image")
            }

            if (uiState.placementMode == PlacementMode.WaitingForPlacement) {
                Button(
                    enabled = uiState.canPlace,
                    onClick = { onEvent(ArUiEvent.PlaceClicked) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "Place")
                }
            } else if (uiState.placementMode == PlacementMode.Repositioning) {
                Button(
                    enabled = uiState.canConfirmReposition,
                    onClick = { onEvent(ArUiEvent.ConfirmRepositionClicked) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "Confirm Reposition")
                }
            } else {
                Button(
                    enabled = uiState.canReposition,
                    onClick = { onEvent(ArUiEvent.RepositionClicked) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "Reposition")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = uiState.canDelete,
                onClick = { onEvent(ArUiEvent.DeleteClicked) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Delete")
            }

            val isRecording = uiState.recordingState is RecordingState.Active ||
                uiState.recordingState is RecordingState.Preparing ||
                uiState.recordingState is RecordingState.Finalizing

            Button(
                enabled = uiState.canRecord || isRecording,
                onClick = {
                    if (isRecording) {
                        onEvent(ArUiEvent.StopRecordingClicked)
                    } else {
                        onEvent(ArUiEvent.RecordClicked)
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = if (isRecording) "Stop Recording" else "Record")
            }
        }

        if (uiState.placementMode == PlacementMode.Repositioning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = uiState.canCancelReposition,
                    onClick = { onEvent(ArUiEvent.CancelRepositionClicked) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "Cancel Reposition")
                }
            }
        }
    }
}
