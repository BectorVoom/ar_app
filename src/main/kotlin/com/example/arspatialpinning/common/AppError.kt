package com.example.arspatialpinning.common

sealed interface AppError {
    val message: String
    val blocking: Boolean

    data class ArUnsupported(
        override val message: String = "This device does not support ARCore.",
        override val blocking: Boolean = true
    ) : AppError

    data class CameraPermissionDenied(
        override val message: String = "Camera permission is required to use AR.",
        override val blocking: Boolean = true
    ) : AppError

    data class ArCoreInstallOrUpdateRequired(
        override val message: String = "ARCore install or update is required.",
        override val blocking: Boolean = true
    ) : AppError

    data class InvalidImage(
        override val message: String = "Please select a valid PNG or JPEG image.",
        override val blocking: Boolean = false
    ) : AppError

    data class FileOpenFailed(
        override val message: String = "Unable to open the selected file.",
        override val blocking: Boolean = false
    ) : AppError

    data class PreviewRenderCreationFailed(
        override val message: String = "Preview/render creation failed.",
        override val blocking: Boolean = false
    ) : AppError

    data class NoValidPlane(
        override val message: String = "No valid plane is currently tracked at the reticle.",
        override val blocking: Boolean = false
    ) : AppError

    data class MicrophonePermissionDenied(
        override val message: String = "Microphone permission is required to record.",
        override val blocking: Boolean = false
    ) : AppError

    data class MediaProjectionDenied(
        override val message: String = "Screen capture consent was denied.",
        override val blocking: Boolean = false
    ) : AppError

    data class MediaProjectionConsentIntentFailed(
        override val message: String = "Unable to request screen capture consent.",
        override val blocking: Boolean = false
    ) : AppError

    data class RecorderStartFailed(
        override val message: String = "Unable to start recording.",
        override val blocking: Boolean = false
    ) : AppError

    data class RecorderStoppedUnexpectedly(
        override val message: String = "Recorder stopped unexpectedly.",
        override val blocking: Boolean = false
    ) : AppError

    data class RecorderStopFailed(
        override val message: String = "Recording failed during finalization.",
        override val blocking: Boolean = false
    ) : AppError

    data class RayneoAudioPolicySetupFailed(
        override val message: String = "E-REC-006: RayNeo X3 audio policy setup failed.",
        override val blocking: Boolean = false
    ) : AppError

    data class RecordedFileInvalid(
        override val message: String = "Recorded file failed validation.",
        override val blocking: Boolean = false
    ) : AppError

    data class OutputCreationFailed(
        override val message: String = "Failed to create recording output.",
        override val blocking: Boolean = false
    ) : AppError

    data class OutputFinalizeFailed(
        override val message: String = "Failed to finalize recording output.",
        override val blocking: Boolean = false
    ) : AppError

    data class OutputCleanupFailed(
        override val message: String = "Failed to clean up recording output.",
        override val blocking: Boolean = false
    ) : AppError

    data class DownloadExportFailed(
        override val message: String = "E-STORAGE-003: Failed to export recording.",
        override val blocking: Boolean = false
    ) : AppError

    data class StaleOrMissingPreparedAssetHandle(
        override val message: String = "Prepared render asset is stale or missing.",
        override val blocking: Boolean = false
    ) : AppError

    data class MetadataOnlySuccessAttempted(
        override val message: String = "Image metadata was loaded, but render preparation did not complete.",
        override val blocking: Boolean = false
    ) : AppError

    data class DimensionOnlySuccessAttempted(
        override val message: String = "Image dimensions were decoded, but render preparation did not complete.",
        override val blocking: Boolean = false
    ) : AppError

    data class PreviewIdentityMismatch(
        override val message: String = "Preview state identity does not match the prepared asset.",
        override val blocking: Boolean = false
    ) : AppError

    data class ArPlacementFailed(
        override val message: String = "Failed to place image in AR scene.",
        override val blocking: Boolean = false
    ) : AppError

    data class ArRepositionFailed(
        override val message: String = "Failed to reposition image.",
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
