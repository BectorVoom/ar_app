package com.example.arspatialpinning.common

sealed interface AppError {
    val message: String
    val blocking: Boolean

    data class PermissionDenied(
        override val message: String,
        override val blocking: Boolean = true
    ) : AppError

    data class ArUnavailable(
        override val message: String,
        override val blocking: Boolean = true
    ) : AppError

    data class InvalidImage(
        override val message: String,
        override val blocking: Boolean = false
    ) : AppError

    data class RecordingFailure(
        override val message: String,
        override val blocking: Boolean = false
    ) : AppError

    data class Unexpected(
        override val message: String,
        override val blocking: Boolean = true
    ) : AppError
}
