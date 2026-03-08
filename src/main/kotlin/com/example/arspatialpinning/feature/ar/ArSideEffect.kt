package com.example.arspatialpinning.feature.ar

sealed interface ArSideEffect {
    data object LaunchImagePicker : ArSideEffect
    data object RequestCameraPermission : ArSideEffect
    data class ShowSnackbar(val message: String) : ArSideEffect
    data object NavigateBack : ArSideEffect
}
