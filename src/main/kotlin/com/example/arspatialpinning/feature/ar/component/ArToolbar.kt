package com.example.arspatialpinning.feature.ar.component

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import com.example.arspatialpinning.domain.model.RecordingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArToolbar(
    recordingState: RecordingState,
    onBack: () -> Unit
) {
    val title = when (recordingState) {
        is RecordingState.Active -> "AR (Recording)"
        is RecordingState.Preparing -> "AR (Preparing Recording)"
        is RecordingState.Finalizing -> "AR (Finalizing Recording)"
        else -> "AR"
    }

    TopAppBar(
        title = { Text(text = title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        }
    )
}
