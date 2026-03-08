package com.example.arspatialpinning.feature.ar

import android.content.Intent

sealed interface ArSideEffect {
    data object LaunchImagePicker : ArSideEffect
    data object RequestCameraPermission : ArSideEffect
    data object RequestRecordAudioPermission : ArSideEffect
    data class RequestMediaProjectionConsent(val intent: Intent) : ArSideEffect
    data class ShowSnackbar(val message: String) : ArSideEffect
    data object NavigateBack : ArSideEffect
}
